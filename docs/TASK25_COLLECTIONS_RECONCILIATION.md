# Task 25 - Live collections and order reconciliation

## Status and signed scope

**LIVE COLLECTIONS BLOCKED - BOTH OPERATORS NOT_CONNECTED.** Approved Yas/Flooz initiation, status-query and raw-callback specifications and authorized sandbox access were not provided or verified in this session. No provider request, sandbox payment, failure, timeout or replay evidence is claimed. The Task24 gate remains closed. This task completes independent reconciliation and investigation gaps; live acceptance remains outstanding.

Read the signed source `/Users/muhirwagabooreste/Downloads/contrat_mvp_sequo_signed-2.pdf`:27 (page3) requires payment or payment validation before definitive confirmation;42-44 (page4) cover integration/preparation, paid-before-validation and electronic transfers;113 (page12) repeats payment before validation. Relevant complete pages and audit rows27/42-44/113 were read. The PDF controls scope; the audit and existing diagrams locate code, not provider requirements.

Normal adapter, webhook, checkout, customer order-history and admin Payments entry points and current diffs were inspected first. Baseline local checks passed30 API tests and13 admin tests. Existing receipt persistence and checkout locks already enforce provider, payment-reference, amount and XOF matching, frozen checkout pricing, owner reads and paid fulfillment. Remaining concrete gaps: the AFTER_COMMIT listener discarded reconciliation outcomes, concurrent JPA merge could overwrite canonical receipt evidence, terminal conflicting events were misleadingly labelled fulfilled, and admin could not locate a pending checkout that had no callback. Customer history retained old records beneath a refresh error.

## Provider prerequisites and separate results

The [Task24 operator matrix](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/docs/TASK24_PROVIDER_PREPARATION.md) remains authoritative for the missing inputs. Task-scoped tracked documentation and provider-named local documents supplied no approved guide or sandbox configuration reference. Existing launch notes still had unchecked documentation/account-access requests. No `.env`, secret store or production database was opened. A request for the approved documentation location and secure configuration reference was presented to the user while independent work continued; no credential values should be pasted into this report.

| Provider | Actual initiation / query / authenticated callback | Sandbox success / failure / timeout / replay | Outstanding inputs |
| --- | --- | --- | --- |
| Yas Togo / mixx by Yas (`yas_togo`) | BLOCKED, NOT_CONNECTED | UNVERIFIED, no operator evidence | Approved versioned specifications for all three operations; sandbox URL, SEQUO merchant/payee account, authorized test credentials/certificates and test wallets; callback registration/auth/canonicalization; idempotency/finality/reconciliation rules and permitted rollout acceptance. |
| Moov Africa Togo / Flooz (`moov_africa`) | BLOCKED, NOT_CONNECTED | UNVERIFIED, no operator evidence | The corresponding Flooz Togo specifications, Corporate merchant/payee account, authorized sandbox access, callback/auth material, reference/amount/currency/finality rules and rollout acceptance. |

The user explicitly confirmed during Task25: **neither operator's approved specifications or authorized sandbox access is available yet**. Actual merchant/payee binding cannot be verified from the existing internal normalized envelope or business marketing pages. A later approved mapper must check the operator merchant account, original intent/order reference, transaction identity, amount, currency and final status against the persisted server intent before authorizing reconciliation. This task does not add guessed endpoints, auth fields or request JSON. Internal HMAC fixtures are test-source-only and do not establish connectivity.

## Independent implementation

New verified normalized receipts have durable PENDING reconciliation state. Processing records a fixed outcome/reason/time in the same transaction as fulfillment and benefit consumption/release. The transaction explicitly rolls back checked exceptions too, including Jackson parsing failures; a separate safe recorder marks ERROR after rollback. A slower failure recorder cannot downgrade a successful terminal outcome. An identical newly verified callback retry uses stored canonical evidence and may resume eligible pending/error work. Duplicate or conflicting event IDs cannot overwrite the original receipt. Normalized status, signature verification, XOF matching and checkout ownership remain server decisions. New order chronology uses original server receivedAt; provider occurredAt remains immutable evidence.

Accepted evidence and business fulfillment are separate. PENDING after a completed order does not create a second order; failed/cancelled or changed-transaction evidence against a paid checkout requires investigation. Late success after failed/cancelled checkout does not reopen fulfillment automatically. No unknown provider finality/reversal behavior is invented. A timeout with uncertain provider outcome must remain unresolved until authoritative operator evidence exists; starting another payment blindly is not a recovery procedure.

A unique claim for an explicit normalized provider transaction reference prevents it funding another checkout. The current integration has one SEQUO merchant-account slot per provider; normalized provider reference means transaction identity within that integration. Existing paid-order references are checked conservatively. This internal guard does not prove actual operator payee/account binding or define the external operator's reference namespace. A future multi-account adapter needs an approved, explicit account namespace and migration policy. Supported optional legacy references remain readable; no legacy transfer is manufactured.

Admin's existing Payments read now includes bounded recent checkout summaries, including records with no event. Exact-ID investigation reuses the same admin read surface and returns real order linkage plus safe normalized receipt/outcome fields. It excludes customer identity/contact/destination, request/pricing payloads, raw bodies, signatures, hashes and credentials. The panel is read-only: it cannot mark paid, execute provider queries, trigger refunds/transfers or bypass reconciliation. Empty, loading, failure and session-clearing behavior are explicit.

Customer history clears pending checkout/order lists at refresh start or failed reads and publishes only after both authenticated reads succeed. Session-specific loading/error state prevents another account's cancelled read affecting the current one. Existing pending, failed and paid states come from persisted owner-scoped server records. Preparation still requires verified payment.

## Migration and operator decisions

V51 adds durable reconciliation metadata and explicit transaction-reference ownership. Existing receipt rows become LEGACY_REVIEW_REQUIRED; migration does not backfill payment claims, replay old events, create orders, change amounts or rewrite financial history. Existing paid orders and supported legacy request/pricing records remain readable. There is no scheduler or automatic historical replay and no new production-enabling switch. Keep callback ingress disabled during a coordinated schema/application deployment; do not rely on mixed old/new consumers to enforce the new policy. The database default marks writes from older code as legacy review rather than assuming verified processing under this policy.

Before relying on a live adapter, operators must reconcile old receipts, pending checkouts, existing provider references and actual operator statements under an approved migration/rollout policy. A receipt with no processed outcome may represent a previously fulfilled order, rejected evidence or work interrupted after commit; do not infer which without matching the persisted checkout/order and authoritative statement. Review late conflicting success, changed references, shared references and terminal failures rather than overriding payment state by hand.

The receipt stores safe normalized evidence and a payload hash, not the original provider raw body. Actual provider ingestion must define approved evidence retention/redaction and durable intake/retry before launch. Persist the server intent before initiating an external request, so an early callback can bind to an existing checkout; the current stub preparation does not prove this external race is solved. Recovery of pending/error evidence currently depends on an identical authenticated provider retry or an explicitly approved operational procedure; no silent background reprocessing of historical transactions is enabled.

## Acceptance and local verification

| Check | Result |
| --- | --- |
| Actual provider initiation/status/callback and sandbox success/failure/timeout/replay | BLOCKED separately for Yas and Flooz by the user-confirmed missing prerequisites. No connectivity, payee verification or permitted production rollout is claimed. |
| Canonical matching and terminal events | Local tests for both provider IDs verify amount/reference mismatches, pending, success, failure/cancellation, pending after success, failed after success and late success after failure. Original paid order/money is preserved; conflicting outcomes require review. Missing legacy pending references use known paid-order references, and inconsistent known history is not silently accepted. |
| Rejected/repeated/concurrent evidence | Unverified runtime callbacks still fail closed. Same event ID with competing bodies preserves one original hash/checkout/time and one paid order. Explicit transaction-reference reuse across concurrent checkouts creates at most one paid order; retry identifies the losing evidence for review. Existing paid references without a new claim also block reuse. |
| Interrupted processing/restart | A checked parse failure rolls back the claim and order/benefit transaction and records only safe ERROR. Identical verified retry recovers. A persistent fixture spans three application starts: pending receipt survives, authenticated retry fulfills once, legacy evidence stays review-required, and final outcome/order survive another restart. |
| Customer authoritative views | Seven focused client tests pass, including pending/failed/cancelled/paid/legacy reads and failed requests. Refresh clears old records and session flags are isolated. Android/iOS main compiles pass. Full customer suite remains blocked by its preserved pre-existing malformed pricing test. |
| Admin investigation | Exact pending checkout with no event is visible; real order linkage is read from persistence, never inferred. Safe fields, role denial,404,empty/failure,legacy API versions and normalized event/outcome distinctions pass. UI actions are all GET; local browser refresh and missing/expired/non-admin session checks pass. |

Read paths: existing `GET /api/admin/web/payments?limit=50` now adds recent checkouts; new admin-only `GET /api/admin/web/payments/checkouts/{checkoutId}` reads exact summary plus latest100 events. Both are no-store. Customer `/api/orders/checkouts` and `/api/orders` remain owner-scoped and authoritative.

API baseline30 tests passed before changes. Final focused command passed59 tests,0 failures/errors:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test \
  --tests '*PaymentReconciliation*Test' --tests '*PaymentWebhook*Test' \
  --tests '*PendingPaymentCheckoutServiceTest' --tests '*CustomerCartCheckoutHttpTest' \
  --tests '*BargainingCheckoutHttpTest' --tests '*DeliveryBenefitsCheckoutHttpTest' \
  --tests '*SettlementJourneyHttpTest' --tests '*MerchantBusinessReadsHttpTest' \
  --tests '*PaymentProviderReadinessHttpTest' --tests '*PaymentReadinessConfigurationTest' \
  --tests '*AdminWebReadHttpTest' --tests '*AdminWebControllerAuthorizationTest' \
  --tests '*AdminPaymentInvestigationHttpTest' --tests '*PaymentSimulationGuardTest'
```

Main Kotlin compilation passed. Regression iteration caught the checked-exception rollback issue before commit, now covered by the safe ERROR/recovery test. A new restart fixture constructor initially skipped an optional JPA relation position; it was corrected to named arguments. Independent read-only reviews checked lock order, canonical evidence, historical references, no runtime enabling, admin scope/privacy and UI session behavior.

Fresh PostgreSQL verification passed5 tests (four reconciliation/concurrency scenarios and the three-start restart scenario), using real FlywayV51 and Hibernate validation:

```sh
SEQUO_RECONCILIATION_TEST_URL=jdbc:postgresql://127.0.0.1:55434/sequo_task14_payment_reconcile \
  JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test \
  --tests '*PaymentReconciliation*Test'
```

Admin commands from `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin`:

```sh
node --experimental-strip-types --test tests/*.test.mjs
# Baseline PASS:13 tests.
node --test tests/*.test.mjs
# PASS:17 tests.
npm run type-check
VITE_SEQUO_API_BASE_URL='' npm run build-only
NODE_PATH=/opt/homebrew/lib/node_modules/@google/gemini-cli/node_modules \
  node scripts/payment-investigation-browser-check.cjs
# PASS:type check, production build and local mock-HTTP browser checks.
```

The admin screenshot `/tmp/sequo-task25-admin-payments.png` was visually checked for readable layout. Browser fixtures cover pending without callback, ignored/review/error/legacy outcomes, real linkage, refresh, older exact-ID lookup,404,empty/error and missing/expired/non-admin sessions; they are not operator connectivity evidence.

Customer commands from `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo`:

```sh
./gradlew --offline -I /tmp/sequo-task21-client-tests.init.gradle \
  :shared:testAndroidHostTest --tests '*CheckoutHistoryClientTest' \
  --tests '*BenefitsClientTest' --tests '*PaymentProviderClientTest' \
  :shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64
# PASS:7 tests, Android/iOS main compile.
./gradlew --offline :shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64
# PASS after final session-keyed loading/error change.
```

The unchanged uncommitted `shared/src/commonTest/kotlin/dev/orestegabo/sequo/domain/DeliveryPricingTest.kt` contains `calculateDeliver yPricing`, so full host-test compilation remains blocked. Focused host tests use the temporary exclusion already documented in Task24, not a source edit. Its SHA-256 remains `6e8d46cb71af4de574a91941df80245871412b17e83c7ff8d367fb17a0830eb4`. Device, authorized remote staging and both operator sandbox checks remain UNVERIFIED.

Legacy migration and production packaging also passed:

```sh
SEQUO_PAYMENT_CALLBACK_TEST_URL=jdbc:postgresql://127.0.0.1:55434/sequo_task14_payment_reconcile_legacy \
  JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test \
  --tests '*PaymentWebhookFailClosedHttpTest' bootJar
```

This disposable database was cloned from Task24's synthetic V50 fixture history before V51. SQL confirms migration51 succeeded, all8 original receipts are LEGACY_REVIEW_REQUIRED, both original paid orders remain, and no transaction claim was backfilled. Pre/post fingerprints of order payment status/total/reference and original receipt hash/amount/status are identical (`/tmp/sequo-task25-legacy-{before,after}.txt`). The fresh reconciliation database has no transaction reference claimed by multiple checkouts; its final fixture outcomes include fulfilled, ignored and review-required, with no residual processing error/pending from the recovery tests. Production jar inspection confirms V51 is packaged and test verifier/restart/CallbackFixtures classes are absent. The task-owned PostgreSQL cluster was stopped after verification.

Local logs: `/tmp/sequo-task25-{baseline,compile,api-tests,postgres,legacy-migration}.log`; `/tmp/sequo-task25-admin-{baseline,tests,type,build,browser}.log`; `/tmp/sequo-task25-customer-{checks,final-compile}.log`. XML snapshots: `/tmp/sequo-task25-h2-results/`, `/tmp/sequo-task25-postgres-results/`, `/tmp/sequo-task25-legacy-results/`. `git diff --check` passes in all three touched repositories. No production secret, account, database or live money was used.

## Authorized provider/staging reproduction - UNVERIFIED

1. Supply the operator-approved guide/version and authorized sandbox configuration through the agreed secure channel. Confirm SEQUO's account/payee, operator scopes, callback registration, amount/currency units and allowed acceptance scenarios. Keep production NOT_CONNECTED. Repeat each remaining step separately for Yas and Flooz; record the actual provider transaction/reference and redacted evidence location.
2. Implement the approved adapter using the existing processor/verifier seam, persist the server-owned intent before external initiation, and retain a stable retry key. Verify actual initiation and authoritative status query against the same merchant account and intent; neither client totals nor a client success flag can authorize an order.
3. From an authenticated staging customer, submit a reviewed basket. Exercise operator success, refusal/cancellation and a network timeout/unknown outcome. On timeout, use the approved status/reconciliation query before retrying; verify one provider intent and at most one paid order. Refresh/restart customer views and compare persisted states. Another customer must not read it.
4. Send authenticated operator callbacks for pending, success, failure, duplicate, reordered and late evidence. Include wrong amount, currency, checkout/order reference, merchant/payee and transaction identity plus invalid auth/replay timestamp, according to approved operator behavior. Invalid proof cannot create fulfillment. One canonical receipt/outcome and reference claim must explain every accepted event; conflicts require review and preserve the original evidence.
5. Re-deliver identical evidence after interrupted reconciliation and restart. Confirm the same stored event and checkout produce one order/merchant queue entry and one benefit consumption. Repeat concurrent submissions, same event ID with altered evidence, and same transaction reference against another checkout. Do not auto-replay legacy review-required receipts.
6. As admin, open Payments, refresh the recent checkout queue, investigate the exact checkout ID and compare amount, provider/reference, persisted state, linked order and receipt outcomes. Verify a pending checkout with no callback can be found. Missing/expired/customer/merchant/rider sessions must fail closed. Disconnect the network, refresh and sign into another authorized session: prior records must disappear on failed/session-changed reads.
7. Compare operator statements/status with durable outcomes and resolve all unknown/error/review records through approved operations. Verify secrets, raw callbacks and customer contact are absent from browser responses/logs. Only then perform the permitted operator/SEQUO rollout check with approved production configuration, monitoring, support, rollback and reconciliation. Task25 has not performed that check or enabled production.

## What subsequent work can safely depend on

The provider gate remains NOT_CONNECTED; internal fixtures are not live evidence. Existing provider IDs and request/result seams, immutable server checkout amounts and owner boundaries remain the integration contract. New receipts and admin reads distinguish verification receipt from business reconciliation, and historical events require explicit review. A subsequent task can use these investigation records and local regression harness; it must obtain official specifications and authorized sandbox evidence before claiming collection or payee validation, activating operations or executing a real refund/payout. The current claims protect the normalized callback path: any future immediate initiation/status-query success path must enter the same canonical reconciliation/ownership policy before enablement; the existing stub-backed direct-success branch is not evidence that this has been integrated.


## Changed files and focused commits

sequo-api (14 files):

- [EXTERNAL_LAUNCH_SETUP.md](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/EXTERNAL_LAUNCH_SETUP.md)
- [docs/TASK25_COLLECTIONS_RECONCILIATION.md](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/docs/TASK25_COLLECTIONS_RECONCILIATION.md)
- [src/main/kotlin/dev/orestegabo/sequo_api/api/ApiErrors.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/api/ApiErrors.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/admin/AdminWebSurface.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/admin/AdminWebSurface.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/order/PendingPaymentCheckout.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/order/PendingPaymentCheckout.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/payment/PaymentReconciliationAudit.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/payment/PaymentReconciliationAudit.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/payment/PaymentWebhook.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/payment/PaymentWebhook.kt)
- [src/main/resources/db/migration/V51__durable_payment_reconciliation.sql](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/resources/db/migration/V51__durable_payment_reconciliation.sql)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/admin/AdminPaymentInvestigationHttpTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/admin/AdminPaymentInvestigationHttpTest.kt)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/order/CustomerCartCheckoutHttpTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/order/CustomerCartCheckoutHttpTest.kt)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/payment/PaymentReconciliationHttpTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/payment/PaymentReconciliationHttpTest.kt)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/payment/PaymentReconciliationRestartTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/payment/PaymentReconciliationRestartTest.kt)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/payment/PaymentWebhookGateHttpTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/payment/PaymentWebhookGateHttpTest.kt)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/payment/PaymentWebhookServiceTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/payment/PaymentWebhookServiceTest.kt)

Sequo (2 files):

- [shared/src/androidHostTest/kotlin/dev/orestegabo/sequo/CheckoutHistoryClientTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/androidHostTest/kotlin/dev/orestegabo/sequo/CheckoutHistoryClientTest.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/orders/OrdersContent.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/orders/OrdersContent.kt)

sequo-admin (6 files):

- [scripts/payment-investigation-browser-check.cjs](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin/scripts/payment-investigation-browser-check.cjs)
- [src/App.vue](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin/src/App.vue)
- [src/PaymentInvestigationPanel.vue](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin/src/PaymentInvestigationPanel.vue)
- [src/adminReads.ts](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin/src/adminReads.ts)
- [src/paymentInvestigationReads.ts](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin/src/paymentInvestigationReads.ts)
- [tests/paymentInvestigationReads.test.mjs](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin/tests/paymentInvestigationReads.test.mjs)

Focused commits: API `b215c74` (durable canonical reconciliation, ownership, rollback and V51), `691f7df` (admin investigation read/privacy tests); customer `13ef45e` (safe history refresh); admin `d04ed16` (read-only investigation UI/tests). This report and payment-only launch checklist corrections are committed separately.

Pre-existing output/audit/invoice artifacts, the intentionally uncommitted Task16 decision note and the customer pricing-test edit remain untouched. No provider transport, production activation, live transfer or unrelated Vendor/Rider source change is included.
