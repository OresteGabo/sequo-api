# Task 21 — Subscriptions, referral and effective parameters

## Scope and inspected runtime

The signed source `/Users/muhirwagabooreste/Downloads/contrat_mvp_sequo_signed-2.pdf` controls scope: page 4 clauses 41 and 46–49, page 13 Annex A.6 clauses 131–140. The audit rows for those clauses were read as location/evidence hints, not current authority. Existing diffs and runtime entry points were inspected first in sequo-api, Sequo and sequo-admin; unrelated files were preserved.

Initial verification: 39 existing tests passed across SubscriptionBenefitsServiceTest, ReferralDeliveryCreditServiceTest, DeliveryPricingServiceTest, PlatformCommerceServiceTest, CustomerCartCheckoutHttpTest and ContractBusinessParameterReadinessTest. The pure rules already existed, but real `/api/cart/quote` did not apply them. Subscription signup immediately activated any existing plan, referral issuance hardcoded the example 500 CFA, and the customer account unconditionally claimed active subscription/15%/500 CFA. The admin inventory covered only part of Annex A.6.

## Result against the contract and acceptance checks

| Contract / acceptance | Result and evidence |
| --- | --- |
| 46 Monthly subscription base | Existing persisted plans, customer subscriptions and period dates are reused. Public plans require active plus written approval reference. Customer signup is an idempotent PAUSED request, never proof of payment. Verified activation/renewal remains an authorized operations task using actual paid-period evidence. No billing provider execution is claimed. |
| 47 Automatic percentage delivery discount | Authenticated quote resolves the customer's approved plan and verified active paid period server-side. Local fixture base 400 CFA → 20% discount 80 CFA → delivery 320 CFA. Product/options/service prices are not discounted. Client discount, credit and total fields cannot select benefits. |
| 48 Optional long-term rule | Disabled without approved plan rule and separately verified continuous paid tenure. Local fixture 20% extra reduction after the ordinary reduction produces 64 CFA extra savings, final delivery 256 CFA. The existing technical 30-day-month tenure rule is exercised only as a labelled fixture; SEQUO must approve its production rule/threshold/rate. `startedAt` alone does not prove paid continuity. Combined subscription and loyalty savings respect the monthly cap, including reservations. |
| Paid period / cancellation | At-period-end cancellation preserves benefits during the verified paid period; both paid-through and period-end boundaries are exclusive. It does not renew or charge a subscription. Expired periods give no benefit and invalidate a new stale checkout quote. |
| 49 Delivery-only referral credit, once | Approved owned credit applies only after subscription/loyalty reduction, capped at the remaining delivery charge. Fixture 500 CFA grant applies 320 CFA to delivery, product subtotal remains 4000 CFA, final delivery 0, total 4000, unused credit 180 CFA. Pending/invalid events do not spend credit; verified payment consumes once, including concurrent/replayed events. No cash, product-price deduction, wallet transfer or refund redemption is introduced. |
| 41 SEQUO coverage | A discounted paid order retains the original undiscounted courier estimate. Fixture dispatch has customer fee 0, independent estimate 400 and SEQUO shortfall 400. Existing workforce policy keeps salaried courier payable/shortfall at zero and permits an independently approved actual courier estimate; no new courier tariff is created. |
| 131–140 Authenticated effective inventory | Existing admin business-parameters read now exposes all ten references, current values, approval/configuration state, runtime sources, who may change them, procedures and verification. Default/future-effective pricing reads agree with the pricing engine. Admin/Super-admin permitted; missing/expired/non-admin sessions fail closed. See [the complete mapping and procedures](TASK21_EFFECTIVE_PARAMETERS.md). |
| Customer/admin UI | Account uses an authenticated server benefits read and loading/refresh/error states. Basket shows server base fee, subscription, loyalty, referral and final amounts. Fake active-pass claims are removed from the real account entry. Return-window copy points to server-owned order eligibility. Admin Settings renders the live ten-entry inventory, distinguishing configured examples from effective approved values. |

All fixture amounts, rates, loyalty conditions, coordinates and approval labels above are **LOCAL_FIXTURE_ONLY**, not business approval or production defaults. The PDF's 500 CFA is an example.

## Runtime and effective settings

- Existing subscription plans retain their nominal fields. V49 adds a nullable `approval_reference`; seed plans remain unapproved, hidden from public signup and ineligible for pricing until SEQUO explicitly approves them. An ACTIVE legacy subscription alone does not prove payment.
- Customer subscription activation requires ACTIVE status, nonblank `activation_reference`, a stored `current_period_starts_at`, current server time within the paid period, and an approved active plan. `loyalty_tenure_verified` defaults false. Customer bodies cannot set those fields. Starting the same plan again returns its original request instead of resetting tenure or granting a free month.
- New referral award configuration is `REFERRAL_CREDIT_CFA=0`, `REFERRAL_APPROVAL_REFERENCE=` and `REFERRAL_EXPIRY_DAYS=0` by default. Zero/missing approval disables new awards; zero expiry means no configured policy expiry. Positive award/expiry values require approved business terms. A requested award remains RESERVED and cannot be self-approved. Repeated requests for the same referrer/referred pair return the canonical record.
- Existing persisted credits require AVAILABLE status, their own approved reference, correct referrer ownership, positive remaining balance and non-expiry to apply. The referred party cannot spend the referrer's credit or read its new remaining balance. The safe own summary is `GET /api/subscriptions/benefits`, with no other participant identity or balance.
- Each quote applies one eligible grant, ordered by earliest expiry then creation/id. Remaining credit is preserved; several grants are not combined in one checkout. The account summary totals eligible available balances, minus pending holds.
- Delivery pricing remains 400 CFA through 5 km, then 100 per additional whole started kilometer, using server distance. Existing persisted pricing profiles are effective only when active and `effective_from <= now`; a future active row no longer applies early.
- Bargaining remains three attempts per side and 24-hour accepted-price validity by default, return requests 72 hours, relay fee eligibility strictly after 8 days and review at 30 days. Actual configured values and category rules are read by the admin inventory, not invented here. No relay tariff, return-refund deadline unit, or Click-and-collect policy is supplied by this task.

## Reservation, payment and legacy behavior

The existing customer row lock serializes submit/re-review/reservation per owner. A new `delivery_benefit_applications` row is keyed by the server-derived checkout ID and freezes subscription/loyalty/credit amounts and paid-period identity. Pending reservations count against the subscription's combined monthly cap and available credit but do not decrement stored credit. Verified reconciliation applies exactly once; FAILED/CANCELLED reconciliation releases a hold atomically. Wrong provider reference/amount/currency and PENDING events neither fulfill nor spend credit. An unresolved payment keeps its hold until trusted reconciliation; no unapproved timeout/cancellation rule is invented.

Quote revision includes relevant benefit state. A changed balance, cap, plan/period or approval makes a new stale submission return 409 for customer review. An existing pending checkout retains its reviewed price and retry identity. New legacy `/api/orders/process` calls cannot claim PrimeMonthly/PrimeMultiYear or referral amounts; use the authenticated basket. Historical pending requests, including old pricing payloads without loyalty fields, remain readable and retain their frozen terms. No retroactive debit or recalculation is made.

V49 preserves existing subscription/credit records and amounts, defaults approval and tenure verification to absent/false, records remaining balances without authorizing them, and adds immutable checkout benefit amounts/status plus persisted order loyalty amount. Before enabling existing subscriptions/credits, SEQUO operations must reconcile actual paid-period history, past credit usage, recipient eligibility, grant conditions and remaining balance. Do not approve an old row solely because its status is ACTIVE/AVAILABLE.

Production decisions still required: approved monthly prices, percentage/mode/cap conditions, long-term tenure/rounding/rate rule, referral eligibility/amount/expiry and written references. Use a new inactive plan/version for a changed offer, review current paid-period commitments, then activate the approved version deliberately. Existing pending payments keep their price; no automatic subscription billing, renewal or grant-qualification provider is implemented.

For an authorized activation, verify the source payment first, then record the approved plan reference and actual subscription period start/end and activation evidence through controlled operations. Set loyalty verification only after checking continuous paid tenure against the approved rule. For a referral, verify the qualifying event and ownership, reconcile remaining balance, then record the per-credit approval and AVAILABLE status. Those fields are not public request fields. SQL/config access must use authorized staging/operations accounts; do not put payment credentials into references.

## Verification commands

Final focused checks passed as follows. API uses Java 21 and offline Gradle; all identities/providers/coordinates in automated journeys are isolated fixtures. No production credentials or database were accessed.

## Exact external reproduction (UNVERIFIED)

1. On authorized staging, configure a trusted routing origin/address and approved active plan/paid customer period as above. Keep a second customer without a subscription. Read `/api/subscriptions/benefits` in both sessions and quote the same ordinary basket/address through `/api/cart/quote`. Compare item/option/service amounts, delivery base, subscription/loyalty/referral reductions and final total. Refresh the account and basket after restart.
2. If long-term behavior is approved, configure that exact rule and verify paid tenure from source records. Compare false/true tenure-verification controls and the threshold boundary; verify combined monthly cap across concurrent pending/paid orders. Without that approval, leave loyalty disabled.
3. Request a referral using `POST /api/referrals` with `referredUserId`; it stays RESERVED. After authorized verification/approval, obtain a fresh quote. Submit its unchanged revision with `/api/orders/checkouts`. Before verified payment, stored remaining credit must be unchanged and a second quote must subtract the hold. Send trusted staging PENDING, wrong-amount/currency/reference, FAILED/CANCELLED and valid evidence through the existing webhook adapter. Only a valid event may fulfill and spend the frozen delivery credit once. Replay with the same and a new provider event ID; inspect checkout/application/credit readback.
4. Prepare the paid merchant sub-order and dispatch through existing operations. Confirm discounted customer fee versus the independent courier estimate/shortfall; verify salaried assignment still has zero independent payable. Do not use a customer discount as a reduction to an already agreed courier fee.
5. As ADMIN/SUPER_ADMIN, refresh Settings or `/api/admin/web/business-parameters` and verify exactly references 131–140. Compare actual effective pricing, approved/unapproved plan states and referral issuance gate. Retry without a token, with an expired token and with a customer token; no inventory may be disclosed. The screen must clear private rows on failed session refresh.

Remote staging accounts, real devices and subscription/payment providers were unavailable and remain UNVERIFIED. Source-approved production business values were not supplied; no production entitlement was activated. The existing unrelated malformed customer pricing-test edit remains untouched and excluded only in the isolated network-test compilation.

## What later work may depend on

Later prompts can use the authoritative additive quote fields, safe customer benefits summary, approval/paid-period gates, unique checkout benefit application and complete authenticated parameter inventory. They must not assume approved production rates, automatic subscription purchase/renewal, automatic referral qualification, multi-grant combination, a provider refund, a refund deadline unit or an automatic release of unresolved payment holds.

- API baseline: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test --tests '*SubscriptionBenefitsServiceTest' --tests '*ReferralDeliveryCreditServiceTest' --tests '*DeliveryPricingServiceTest' --tests '*PlatformCommerceServiceTest' --tests '*CustomerCartCheckoutHttpTest' --tests '*ContractBusinessParameterReadinessTest'` — PASS, 39 tests before changes.
- API compile: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline compileKotlin` — PASS.
- API final: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test --tests '*DeliveryBenefitsCheckoutHttpTest' --tests '*SubscriptionBenefitsServiceTest' --tests '*ReferralDeliveryCreditServiceTest' --tests '*DeliveryPricing*Test' --tests '*PlatformCommerceServiceTest' --tests '*CustomerCartCheckoutHttpTest' --tests '*AdminBusinessParameterInventoryHttpTest' --tests '*AdminWeb*Test' --tests '*PendingPaymentCheckout*Test' --tests '*DeliveryAssignment*Test' --tests '*DeliveryReadinessDispatch*Test' --tests '*ContractBusinessParameterReadinessTest'` — PASS, 64 tests. Includes non-default parameter inventory, invalid/missing/expired auth, future-active pricing, credit holds/expiry/ownership, concurrent validated callbacks, combined caps, paid cancellation and unchanged merchant/product prices.
- API/PostgreSQL: `SEQUO_BENEFITS_JOURNEY_TEST_URL=jdbc:postgresql://127.0.0.1:55434/sequo_task14_delivery_benefits JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test --tests '*DeliveryBenefitsCheckoutHttpTest'` — PASS, all 4 end-to-end fixture scenarios on PostgreSQL with Flyway through V49 and Hibernate schema validation. Loopback port 55434 belongs only to this disposable task-owned cluster, user sequo_fixture with local trust; no production datasource. SQL readback found no negative/over-limit credit balance and confirmed the customer0/courier-estimate400/shortfall400 mission. Each replayed payment retained its one application row.
- Admin: `node --experimental-strip-types --test tests/*.test.mjs` — PASS, 6 tests; `npm run type-check` — PASS; `VITE_SEQUO_API_BASE_URL='' npm run build-only` — PASS.
- Admin browser: `NODE_PATH=/opt/homebrew/lib/node_modules/@google/gemini-cli/node_modules node scripts/business-parameters-browser-check.cjs` — PASS: authenticated ten-entry Settings inventory, pending configured discount distinct from effective zero, refreshed server activation, expired-session clearing. Mock HTTP only. Screenshot `/tmp/sequo-task21-admin-inventory.png` was visually checked.
- Sequo: `./gradlew --offline :shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64` — PASS; rerun after the last return-copy change also passed.
- Sequo final network checks: `./gradlew --offline --no-configuration-cache -I /tmp/sequo-task21-client-tests.init.gradle :shared:testAndroidHostTest --tests '*BenefitsClientTest' --tests '*ReturnClientTest'` — PASS, 4 tests. Standard host-test compilation is blocked by the pre-existing malformed `calculateDeliver yPricing` in the already modified DeliveryPricingTest.kt; it was not edited or staged. The external temporary init script contains only `gradle.projectsEvaluated { rootProject.findProject(':shared').tasks.getByName('compileAndroidHostTest').exclude('**/DeliveryPricingTest.kt') }`.
- `git diff --check` in all three touched repositories — PASS.

Local logs are `/tmp/sequo-task21-{baseline,compile,api-tests,postgres,admin-tests,admin-type,admin-build,admin-browser,client-compile,client-copy-compile,client-tests,client-final-tests}.log`; final64-test XML is retained under `/tmp/sequo-task21-h2-results/`. Temporary fixture artifacts are not committed. The task-owned PostgreSQL cluster was stopped after final verification. Initial iteration caught a test nullable-lookup compile error and a detached-version fixture update; both were corrected before the final successful runs.

## Changed files

sequo-api:

- [docs/TASK21_EFFECTIVE_PARAMETERS.md](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/docs/TASK21_EFFECTIVE_PARAMETERS.md)
- [docs/TASK21_SUBSCRIPTIONS_REFERRAL.md](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/docs/TASK21_SUBSCRIPTIONS_REFERRAL.md)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/admin/AdminWebSurface.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/admin/AdminWebSurface.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/commerce/CartDeliveryQuote.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/commerce/CartDeliveryQuote.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/DeliveryOptions.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/DeliveryOptions.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/DeliveryReadinessDispatch.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/DeliveryReadinessDispatch.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/order/CustomerCartCheckout.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/order/CustomerCartCheckout.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/order/OrderController.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/order/OrderController.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/order/OrderFulfillmentPersistence.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/order/OrderFulfillmentPersistence.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/order/PendingPaymentCheckout.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/order/PendingPaymentCheckout.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/platform/PlatformCommerceApi.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/platform/PlatformCommerceApi.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/pricing/DeliveryPricing.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/pricing/DeliveryPricing.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/subscription/DeliveryBenefits.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/subscription/DeliveryBenefits.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/subscription/SubscriptionBenefits.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/subscription/SubscriptionBenefits.kt)
- [src/main/resources/application.properties](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/resources/application.properties)
- [src/main/resources/db/migration/V49__verified_delivery_benefits.sql](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/resources/db/migration/V49__verified_delivery_benefits.sql)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/admin/AdminBusinessParameterInventoryHttpTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/admin/AdminBusinessParameterInventoryHttpTest.kt)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/order/CustomerCartCheckoutHttpTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/order/CustomerCartCheckoutHttpTest.kt)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/platform/PlatformCommerceServiceTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/platform/PlatformCommerceServiceTest.kt)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/pricing/DeliveryPricingPersistenceTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/pricing/DeliveryPricingPersistenceTest.kt)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/subscription/DeliveryBenefitsCheckoutHttpTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/subscription/DeliveryBenefitsCheckoutHttpTest.kt)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/subscription/SubscriptionBenefitsServiceTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/subscription/SubscriptionBenefitsServiceTest.kt)

Sequo:

- [DELIVERY_PRICING.md](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/DELIVERY_PRICING.md)
- [PAYMENTS_AND_REFERRALS.md](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/PAYMENTS_AND_REFERRALS.md)
- [README.md](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/README.md)
- [shared/src/androidHostTest/kotlin/dev/orestegabo/sequo/BenefitsClientTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/androidHostTest/kotlin/dev/orestegabo/sequo/BenefitsClientTest.kt)
- [shared/src/commonMain/composeResources/values-fr/strings.xml](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/commonMain/composeResources/values-fr/strings.xml)
- [shared/src/commonMain/composeResources/values/strings.xml](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/commonMain/composeResources/values/strings.xml)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequo/core/catalog/CartApiClient.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/core/catalog/CartApiClient.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequo/feature/settings/AppText.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/feature/settings/AppText.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/account/AccountContent.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/account/AccountContent.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/app/SequoShell.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/app/SequoShell.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/basket/BasketContent.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/basket/BasketContent.kt)

sequo-admin:

- [scripts/business-parameters-browser-check.cjs](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin/scripts/business-parameters-browser-check.cjs)
- [src/App.vue](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin/src/App.vue)
- [src/adminReads.ts](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin/src/adminReads.ts)
- [tests/businessParameters.test.mjs](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin/tests/businessParameters.test.mjs)

All unrelated work, including the malformed customer pricing test, output audit/invoices and the intentionally uncommitted `TASK16-SETTINGS-TO-DECIDE.md`, remains untouched.

## Focused commits

- API `0cb5552` — verified benefit records, safe enrollment/issuance and pricing rules.
- API `e803a5e` — authoritative quote, benefit reservation/reconciliation and courier-subsidy integration.
- API `9874aee` — ten-entry authenticated inventory and effective-parameter change procedures.
- Admin `65495b7` — live inventory presentation and browser/unit checks.
- Sequo `d097768` — actual customer benefits, itemized reductions, localized states and client documentation.
- This evidence report is committed separately so the implementation commits remain focused.
