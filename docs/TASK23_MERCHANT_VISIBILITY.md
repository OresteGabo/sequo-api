# Task 23 - Merchant sales, return and payment visibility

## Scope and source evidence

The signed source `/Users/muhirwagabooreste/Downloads/contrat_mvp_sequo_signed-2.pdf` controls scope: clause14 (page2) requires the Vendor mobile interface on iOS/Android;106 (page12) includes merchant order status;120 (page12) includes sales, returns and payment status according to the merchant's rights. Audit rows14/106/120 were used as code pointers, not as proof of current behavior. Current diffs, the Vendor dashboard/order entry points and all three existing backend reads were inspected first.

Baseline checks passed before edits:22 API tests covering merchant fulfillment, return custody and settlement;2 Vendor fulfillment HTTP tests. Existing merchant return and payout reads already supplied the needed persisted projections. The narrower gap was a merchant order history read: the normal list defaulted to the active preparation queue and omitted parent order/payment metadata. Vendor also displayed queue-derived figures as today's sales/pending finance and had placeholder return/payment cards.

## Runtime contract

The Vendor dashboard now has compact Sales, Returns and Settlements tabs with manual refresh. Its authenticated client reads these existing routes as one UI snapshot:

- `GET /api/merchant/sub-orders?merchantId={own}&history=true`
- `GET /api/returns/merchant?merchantId={own}`
- `GET /api/settlements/merchant-payouts?merchantId={own}`

The optional `history=true` extends the existing sub-order read to all preparation states. Its default active queue and mutation prerequisites remain unchanged; an explicit `status` still filters. Known unpaid parent orders are excluded. An orphan legacy sub-order can remain readable in its owner's history with unknown parent metadata, but cannot enter the paid active queue or authorize preparation. Production foreign keys are not relaxed to manufacture legacy records in tests.

Sales show the own-store quantities, frozen product subtotal, commission and merchant net, preparation state, actual parent order/payment state, order date and delivery date where stored. Optional parent fields are nullable for compatibility with older records/responses. Queue figures are labelled as listed orders/order-net estimates, not today's sales or money due. The Account screen links to these records instead of presenting invented financial totals or unsupported payment-rail claims.

The API authorizes the requested store against authenticated merchant scope. Guessing a different merchant ID or sub-order ID does not grant access. Mixed-order lines/amounts are seller-filtered. Merchant returns omit customer identity/contact, reason, PIN, custody actors, refund amount and other-store lines. Merchant payout ledger rows are additionally filtered to the accrual's merchant, including incorrectly linked legacy sources; unrelated courier/relay identifiers are redacted from merchant responses. The admin raw ledger remains intact. These reads use `Cache-Control: no-store`.

The client publishes no partial three-read snapshot. Refresh, read errors and account/store changes clear prior activity; completion checks the user/store/local-mode identity before publishing. Empty results have explicit empty states, and denied/offline/expired-session reads show existing localized errors with refresh. Explicit local demo mode does not fabricate payment or return records. The separate preparation queue and order detail also clear stale real-session records after failed reads.

## Status meanings and limits

Customer payment, refund instruction, refund payment and merchant settlement remain separate facts:

| Record | Display meaning |
| --- | --- |
| Order `Validated` | Recorded customer payment accepted by the server; not a merchant transfer. |
| Return `REQUESTED` + `NOT_SUBMITTED` | A durable refund instruction exists; no outgoing refund confirmation. |
| Return `LEGACY_REVIEW_REQUIRED` | Old refund-pending state or refund retry key without a new instruction needs reconciliation. It is not labelled uninstructed or paid. |
| Payout `Accrued`, `HeldReturnWindow`, `HeldDispute`, `Eligible`, `Batched`, `Approved` | Pending recorded settlement, with raw status and recorded holds/dates. Batch/approval is not provider submission. |
| Payout `SentToProvider` | Recorded submitted/issued instruction, not confirmed receipt. |
| Payout `Paid` | Recorded settled state, alongside an explicit notice that this view provides no provider transfer confirmation. |
| Payout `Failed`, `Adjusted`, unknown | Distinct failure/adjustment/unknown display; never inferred paid. |

Task23 is read-only business visibility. It introduces no payout/refund execution, eligibility release, new tariff, commission recalculation, schema migration or financial-history rewrite. Existing whole-order returns show each seller's own portion; no unsupported item-level refund allocation is invented. Settlement eligibility/due dates are stored dates, not a new promised payment SLA. Provider execution/reconciliation remains subsequent payment work. Unapproved subscription/referral benefits remain inactive in production, following the user's Task21 clarification.

## Acceptance evidence

| Acceptance check | Verified local result |
| --- | --- |
| Two merchants / multiple orders | Actual persisted fixture accounts with separate merchant memberships read multiple own orders and a shared order. StoreA's shared subtotal3000, commission450, net2550 and quantity2 remain distinct from storeB's7000/1050/5950 and quantity1. Delivered/rejected/cancelled history is visible; unpaid orders stay out. Default active queue and fulfillment regressions still pass. |
| Returned order | Shared delivered order follows the real return API through receipt, inspection and refund instruction. Each merchant reads only its own lines and the same persisted return/status. Instruction REQUESTED, refund NOT_SUBMITTED and original customer payment Validated remain distinct. A legacy refund key requires review in both canonical and merchant projections and cannot authorize a fresh instruction. |
| Payment/settlement agreement | Persisted accrual fixtures expose eight recorded states with frozen net1700 and commission300 per2000 line. Client tests cover all ten current payout states plus unknown; Batched/Approved stay pending, SentToProvider issued and Paid recorded settled. No provider transfer was performed. |
| Merchant isolation | Foreign list IDs, guessed foreign detail IDs and forged merchantId fail403. Missing, malformed, expired and customer sessions fail401/403 without records. Mixed-order customer/other-store/private payment data is absent. Foreign legacy ledger rows are hidden; own legacy courier/relay IDs are redacted while admin ledger and underlying records remain intact. |
| Empty / failure / refresh | Empty merchant reads return empty arrays. Client tests distinguish success-empty from401/403/503 on each route, reject mismatched-store payloads, preserve cancellation, and retry the same own-store read with a refreshed token. Repeat API reads match persisted records. UI loading/error clearing and account keys were reviewed; device rendering remains unverified. |

## Commands and results

Run API commands from `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api`:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test \
  --tests '*MerchantFulfillmentControllerTest' --tests '*MerchantFulfillmentServiceTest' \
  --tests '*ReturnCustodyHttpTest' --tests '*SettlementControllerTest' \
  --tests '*SettlementJourneyHttpTest'
# Baseline PASS:22 tests before edits.

JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test \
  --tests '*MerchantBusinessReadsHttpTest' --tests '*MerchantFulfillment*Test' \
  --tests '*ReturnCustodyHttpTest' --tests '*SettlementControllerTest' \
  --tests '*SettlementJourneyHttpTest'
# PASS:33 tests,0 failures/errors, including3 new HTTP journeys.

SEQUO_MERCHANT_READS_TEST_URL=jdbc:postgresql://127.0.0.1:55434/sequo_task14_merchant_reads \
  JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test \
  --tests '*MerchantBusinessReadsHttpTest'
# PASS:3 journeys with Flyway throughV50 and Hibernate schema validation.
```

The PostgreSQL test URL is guarded to a disposable loopback fixture database, with local-trust `sequo_fixture` credentials. SQL readback confirmed Flyway50 success, one durable refund instruction and eight payout accruals. The task-owned server was stopped after verification. No production or remote staging database was used. The first new fixture compile needed explicit JWT issuer/audience parameters; those fixture arguments were corrected before the passing runs.

Run Vendor commands from `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor`:

```sh
./gradlew --offline :shared:testAndroidHostTest --tests '*VendorFulfillmentClientTest'
# Baseline PASS:2 tests.
./gradlew --offline :shared:testAndroidHostTest \
  --tests '*VendorActivityClientTest' --tests '*VendorFulfillmentClientTest'
# PASS:8 tests (6 new activity regressions,2 existing fulfillment tests).
./gradlew --offline :shared:testAndroidHostTest :shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64
# PASS:26 host tests,0 failures/errors/skips; Android and iOS simulator main compile.
./gradlew --offline :shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64
# PASS after the final UI read-error guard and again after action-error invalidation.
```

Independent read-only review checked nullable legacy metadata, status mapping, account/store async guards and disclosure boundaries. Root review additionally required failed preparation actions to invalidate previous financial activity, because a timeout can follow a successful server mutation. No extra tariff or mutation route was added. The existing Compose tab API emits deprecation warnings but compilation succeeds.

Local logs are `/tmp/sequo-task23-{baseline,compile,api-tests,postgres}.log` and `/tmp/sequo-task23-vendor-{baseline,checks,full-checks,final-compile,final-action-compile}.log`; API XML copies are `/tmp/sequo-task23-h2-results/` and `/tmp/sequo-task23-postgres-results/`. `git diff --check` passes in both repositories.

## Authorized staging/device reproduction - UNVERIFIED

No remote staging credentials, live provider access or authenticated Android/iOS devices were available in this task. Use authorized test identities and ordinary staging products; do not copy production data or fixed fixture PINs.

1. Sign in separately as merchantA, merchantB, customer and SEQUO operator. Make several paid staging orders, including a two-store order, through the normal checkout/trusted staging reconciliation. Prepare and deliver one with the normal role-scoped workflow. Leave one checkout unpaid.
2. In each Vendor Dashboard, refresh Sales and compare with `GET /api/merchant/sub-orders?merchantId={own}&history=true`. Verify own quantities, frozen commission/net, delivered/preparation/payment states and no unpaid checkout or other-store lines. Default `GET /api/merchant/sub-orders?merchantId={own}` must still be only the actionable active queue.
3. As the customer request a return on the delivered order. Through the existing authorized relay/SEQUO custody and inspection flow, create its refund instruction without claiming provider payment. Refresh both merchant Returns tabs and compare with `GET /api/returns/merchant?merchantId={own}`: own lines only, same return ID/status, instruction REQUESTED and refund NOT_SUBMITTED. No reason, PIN, customer contact or whole-order refund amount should be disclosed.
4. Read Settlements and compare exact IDs, amounts, holds and raw statuses with `GET /api/settlements/merchant-payouts?merchantId={own}` and the admin ledger for each source item. Use only authorized recorded test history for issued/settled display; do not mark real payouts paid to exercise a read screen. Batched/Approved remain pending; SentToProvider is issued; Paid is recorded settled with the provider-confirmation limitation.
5. With merchantA's token, substitute merchantB's ID on all three list routes and merchantB's sub-order ID on the detail route, including a forged merchantId query: expect403. Try absent/expired credentials and a customer token: expect401/403 without rows. Reload the app and sign into merchantB; no merchantA records should remain.
6. Use a merchant with no history: all three tabs must explicitly say empty. Disable network or return an error on any of the three reads, then refresh: prior activity must disappear and a readable error appear; no partial finance snapshot. Restore network/session and refresh to recover. Check order detail loading/error behavior and the localized French/English copy on Android and iOS.

## What subsequent work can depend on

The merchant read contract reuses existing routes, stored order snapshots and server membership. History adds only optional fields/query behavior and keeps the preparation queue compatible. The Vendor dashboard is the entry point for own sales, returns and recorded settlement states. Financial automation must continue to use the persisted server state machine and preserve holds, frozen amounts, idempotency and merchant scope; it cannot treat this display as transfer evidence. Legacy review-required refunds or imported ledger inconsistencies need authorized reconciliation, not automatic backfill by this task.

## Changed files and focused commits

sequo-api:

- [MerchantFulfillmentController.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/MerchantFulfillmentController.kt) - history query and private reads.
- [MerchantFulfillmentService.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/MerchantFulfillmentService.kt) - compatible history and parent status projection.
- [ReturnPersistence.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/returns/ReturnPersistence.kt) - consistent legacy refund-key review state.
- [SettlementController.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/settlement/SettlementController.kt) - private merchant response and counterpart ID redaction.
- [SettlementPersistence.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/settlement/SettlementPersistence.kt) - own-store ledger filtering.
- [MerchantBusinessReadsHttpTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/commerce/MerchantBusinessReadsHttpTest.kt) - persisted two-store HTTP regression fixtures, H2/PostgreSQL.
- [TASK23_MERCHANT_VISIBILITY.md](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/docs/TASK23_MERCHANT_VISIBILITY.md) - this report.

SequoVendor:

- [MerchantActivityApiClient.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/activity/MerchantActivityApiClient.kt) - three-read snapshot and recorded state mapping.
- [VendorActivityClientTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/src/androidHostTest/kotlin/dev/orestegabo/sequovendor/VendorActivityClientTest.kt) - six HTTP/client regressions.
- [MerchantFulfillmentDtos.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/fulfillment/MerchantFulfillmentDtos.kt), [VendorModels.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/model/VendorModels.kt) - optional parent order metadata.
- [MerchantActivityPanel.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/ui/dashboard/MerchantActivityPanel.kt), [DashboardContent.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/ui/dashboard/DashboardContent.kt) - scoped lists and truthful labels.
- [SequoVendorShell.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/ui/app/SequoVendorShell.kt) - authenticated client lifecycle, refresh and safe clearing.
- [OrdersContent.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/ui/orders/OrdersContent.kt) - parent states/dates and detail loading.
- [AccountContent.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/ui/account/AccountContent.kt) - dashboard entry point in place of placeholders.
- [AppText.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/feature/settings/AppText.kt), [English strings](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/src/commonMain/composeResources/values/strings.xml), [French strings](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/src/commonMain/composeResources/values-fr/strings.xml) - existing localization mechanism.

Focused commits: API `d44e8c1` (history/privacy/regressions); Vendor `089118e` (read client/models/HTTP tests), `60bd7c6` (dashboard, localization and safe refresh/error UI). This evidence report is committed separately.

Existing output/audit/invoice artifacts and the intentionally uncommitted `TASK16-SETTINGS-TO-DECIDE.md` are untouched. Vendor's pre-existing Xcode project edits are excluded; its SHA-256 remains `a7f7b50149460a7df096ea86593ca2644a0d463d0add8e59f42cd0c1c16e3af9`.
