# Task 18 — Customer and seller negotiation

## Signed scope and runtime findings

Read the signed `/Users/muhirwagabooreste/Downloads/contrat_mvp_sequo_signed-2.pdf` pages 4 and 12–13. Audit rows 50–55/111/136/137 located the pure BargainingService and disconnected app repositories; the source PDF defines scope.

| Reference | Contract point | Evidence |
| --- | --- | --- |
| 50 / 111 | Customer proposes a lower price for an eligible product. | Authenticated customer thread/offer UI and persisted API; positive below-catalog proposals, active seller/product and product bargaining switch validated server-side. |
| 51 / 136 | Three attempts per side by default; settings modifiable/prepared. | Effective runtime configuration is 3 customer proposals, 3 merchant counters; server DTOs expose used/max counts. All legacy threads for the same parties/product contribute to the budget. |
| 52 | Lock negotiation for the same parties after exhaustion. | Third pending proposal/counter can still be accepted/rejected; rejection at exhaustion locks further proposals. New thread IDs, changed retry keys, reload and omitted action fields cannot reset it. |
| 53 / 137 | Accepted-price validity defaults to 24 hours and is configurable. | Acceptance stores server time/expiry; owned cart, quote and new checkout validate the lock. Exact before/at/after expiry checks and stale checkout rejection pass. |
| 54 | Seller can disable negotiation per product. | Existing merchant catalog setting is reused and read back; new and existing monetary actions are refused while disabled/inactive. Already accepted agreements remain usable until expiry while the product/seller and price remain valid. |
| 55 | Historical minimum display only if SEQUO approves. | Excluded: no approval was supplied. Removed local historical-minimum display/promises and suppressed the seeded historical-minimum catalog hint/private merchant floor in customer catalog DTOs. Own negotiation transcript/accepted amount remain visible to its parties. |

The old live CommerceService could repeatedly create threads and append customer prices without attempt limits, turn checks or seller actions. The pure domain defaults were not connected to those writes. Customer LocalBargainingRepository made a local offer look accepted; Vendor used a fake negotiation repository in real sessions. Those runtime gaps are fixed using the existing thread/offer tables and routes.

## Implemented flow and boundaries

- Customer: existing make-offer entries open product detail. Signed-in sessions use NetworkBargainingRepository; guests are asked to sign in. The panel loads persisted status/history, submits an offer, refreshes, and accepts/rejects the latest seller counter. Attempts, eligibility, accepted price and expiry come from the server. Errors never create a local acceptance. Stable intent keys survive retry within the screen's saved state.
- Vendor: real sessions use a network repository bound to the authorized selected merchant. Existing Account negotiation cards show live transcripts, amounts, attempts, status and expiry, plus reply/counter/accept/reject/refresh. A mutation must be read back from the server before the UI confirms it. Failed/stale reads disable actions until refresh. Merchant/account switching resets visible state; demo data remains only in explicit local mode. English/French localization is reused.
- API: `/api/bargaining/threads` is customer-scoped unless an authorized merchantId query is present; detail/actions authorize the actual thread parties. Merchant ID and customer ID cannot be forged through request bodies. Monetary actions enforce turns and current product policy. Counter/accept/reject require expectedOfferId so a stale screen cannot respond to a different offer.
- Writers serialize on the customer row, then product/thread, so concurrent attempts cannot exceed the budget or create duplicate scope threads. Offer rows carry an actor-scoped idempotency key and request fingerprint. Same-key same-intent replay returns current persisted state without consuming another attempt; changed intent is rejected. A global reused merchant key across two customers produces a controlled conflict, not two counters or a server error. Legacy duplicate threads/history remain stored and counted; no destructive canonicalization was performed.
- Configuration uses the existing BargainingPolicy defaults through `sequo.bargaining.max-customer-attempts`, `max-merchant-attempts`, `accepted-price-lock-hours`. Environment variables are BARGAINING_MAX_CUSTOMER_ATTEMPTS, BARGAINING_MAX_MERCHANT_ATTEMPTS and BARGAINING_ACCEPTED_PRICE_LOCK_HOURS. Defaults are **3 / 3 / 24 hours**. Invalid nonpositive values fail policy initialization. No automatic cooldown/reset policy was invented.
- Acceptance locks the negotiated product base amount. The existing food-option rules still compute required selections, availability and extra prices. Cart PUT saves only bargainingThreadId, never a claimed negotiated amount. Its agreement must match the same customer/merchant/product, have a stored acceptance and finite unexpired validity, and remain compatible with the current product price. Catalog and negotiated configurations have distinct IDs; quantity changes preserve the reference and cannot overwrite the other line.
- Existing cart quote returns authoritative discounted product totals, options, delivery and total. Revision includes the agreement ID/expiry. Checkout revalidates it and records list unit price, negotiated unit price and the actual thread lock reference through pending-payment serialization and the order pricing snapshot. Client totals/distance/negotiated amounts cannot override this flow.
- An invalid/expired agreement stays as an unavailable basket line; it does not silently become a more expensive order. Remove it and explicitly re-add/review the catalog price. A new stale checkout returns 409 without a pending record. A pending checkout already validly created before expiry retains its immutable total and retry identity afterward; this task does not invent a payment-provider deadline.
- The legacy `/api/orders/process` route now rejects claimed negotiated fields and new real catalog-product orders, requiring the authoritative basket quote/checkout. This also prevents stripping bargaining fields and supplying a fake catalog unit price. Existing noncatalog legacy processing, historical order reads and pending-payment reconciliation are retained.
- UI uses simple refresh. Product-level bargaining is the MVP scope; option supplements are separate, and no variant-specific lock, new notification system or historical aggregate-price feature is claimed.

## Acceptance results

| Check | Local result |
| --- | --- |
| Separate sessions offer/counter/accept | PASS: persisted customer and merchant-owner accounts with real memberships and `/api/auth/me` readback share one thread. Customer 1400 → seller 1600 → customer acceptance. Both sessions reload the same history/agreement. |
| Exhaustion and bypass | PASS: three proposals and three counters, then reject → LOCKED_ATTEMPTS_EXHAUSTED. Same-thread, new-thread, new-key, omitted-action and reload bypasses fail without another offer. Other customers/products retain their own budgets. |
| Valid negotiated checkout | PASS: quantity 2 at 1600 base = 3200 products + existing 400 delivery = 3600 XOF, persisted AWAITING_WEBHOOK with negotiated unit/reference. Claimed client totals of 1 are ignored. Mixed catalog/negotiated lines remain distinct through quantity updates/removal. |
| Expired and boundary | PASS: validation just before expiry succeeds; at and after expiry fails. HTTP expired basket remains unavailable, quote fails, stale new checkout returns 409; pending checkout retry remains the same valid original. Explicit catalog review gives 4000 + 400 = 4400. |
| Merchant/customer isolation | PASS: guessed thread IDs, another merchant's list/actions, customer merchant-list query and vendor-only customer-thread creation are denied. Another customer cannot attach the agreement to its cart. Disabled/inactive products reject new and existing monetary actions. |
| Repeated/concurrent submits | PASS: concurrent same-key open/counter requests consume one attempt; accept replay does not extend expiry or duplicate history. Changed body with same key and stale expectedOfferId are denied. A shared merchant key across customers yields one counter and one controlled 400/409. |
| Legacy order bypass | PASS: typed real-controller requests with negotiated price, lock, both, or a stripped-field catalog unit price of 1 are rejected with mapped 400 and no pending record. The legacy sealed-photo HTTP payload lacks a supported discriminator, so this guard check uses valid typed DTOs rather than counting malformed JSON as proof. |
| Devices/deployed staging | UNVERIFIED: local HTTP, host and compiler fixtures passed; no deployed account/session or physical device access was supplied. Exact reproduction follows below. |

## Commands and results (2026-11-08)

Before server changes, existing CommerceService bargaining and MerchantCatalogHttp tests were exercised and passed. After implementation, from `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api`:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test --tests '*Bargaining*Test' --tests '*CommerceServiceTest' --tests '*MerchantCatalogHttpTest' --tests '*CustomerCartCheckoutHttpTest' --tests '*CustomerCheckoutRestartTest' --tests '*PendingPaymentCheckoutServiceTest' --tests '*OrderFulfillmentPersistenceServiceTest' --tests '*ContractBusinessParameterReadinessTest'
```

PASS: **62 tests**, zero failures/errors/skips. Includes four new bargaining scenarios, existing catalog/eligibility and cart/home/relay/consolidation/mode flows, checkout restart/persistence and parameter defaults. Log `/tmp/sequo-task18-regressions.log`.

```sh
SEQUO_BARGAINING_JOURNEY_TEST_URL=jdbc:postgresql://127.0.0.1:55434/sequo_task14_bargaining JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test --tests '*BargainingCheckoutHttpTest'
```

PASS: **4 tests** with PostgreSQL 15, Flyway V1–V46, JPA schema validation, real foreign keys/row locks/unique retry index. After the test API stopped, SQL retained seven threads for six customers/four merchants, the exhausted scope, 18 retry receipts and the 3600 negotiated pending checkout with unit 1600/reference plus the 4400 catalog checkout. Expiry status is derived on reads, so the historical raw accepted row is not rewritten. The guard only accepts disposable loopback `sequo_task14_*` databases/user `sequo_fixture`. Task-owned port 55434 was explicitly used; that cluster was stopped afterward. Evidence: `/tmp/sequo-task18-postgres.log`, `/tmp/sequo-task18-postgres-results.xml`, `/tmp/sequo-task18-postgres-evidence.txt`.

From `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor`:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --offline :shared:testAndroidHostTest --tests '*VendorNegotiationClientTest' :shared:compileKotlinIosSimulatorArm64
```

PASS: **6 tests**, Android main and iOS simulator compilation. Fixtures cover scoped authorization, transcript/counter mapping, body/key preservation across 401 refresh, mutation-success/readback-failure handling, same-key retry, denied/cross-merchant reads and unknown legacy status/name handling. Logs `/tmp/sequo-task18-vendor-compile.log`, `/tmp/sequo-task18-vendor-test.log`.

From `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo`:

```sh
./gradlew --offline :shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64
./gradlew --offline :shared:testAndroidHostTest --tests '*BargainingClientTest'
```

Main compilation PASS. Normal filtered host command is **BLOCKED** by the user's existing malformed `calculateDeliver yPricing` edit at `shared/src/commonTest/kotlin/dev/orestegabo/sequo/domain/DeliveryPricingTest.kt:59`; it was preserved and excluded from commits. To execute only the new network regressions without changing that file, a temporary `/tmp/sequo-task18-customer-tests.init.gradle` contains:

```groovy
gradle.projectsEvaluated {
    def testCompile = rootProject.findProject(':shared').tasks.getByName('compileAndroidHostTest')
    testCompile.exclude('**/DeliveryPricingTest.kt')
}
```

```sh
./gradlew --offline --no-configuration-cache -I /tmp/sequo-task18-customer-tests.init.gradle :shared:testAndroidHostTest --tests '*BargainingClientTest'
```

PASS: **2 focused network tests** with the temporary compile exclusion. They cover real thread/counter readback, expected-offer/retry bodies and errors, accepted reference, catalog/negotiated line separation, quantity reference preservation and server quote totals. This does not claim that the normal/full customer host suite passed. Log `/tmp/sequo-task18-customer-tests-isolated.log`. All changed repos pass `git diff --check`.

## Migration and remaining decisions

Apply **V46__bargaining_replay_and_cart_agreements.sql** before the new API/client flow. It extends the status constraint, adds nullable retry fingerprints/keys and cart agreement references, and adds the retry/scope indexes. Existing offers with null keys, duplicate legacy threads, plain cart lines, order snapshots and fee/payment history are preserved. Existing accepted rows without a finite expiry/acceptance time are not silently granted a new 24-hour window; obtain a new valid agreement or an approved legacy policy.

Historical-minimum visibility remains excluded until SEQUO approves clause 55, including scope/privacy/data provenance. No attempt-reset/cooldown or variant-specific pricing policy was invented; any later reset policy must not expose a customer-created bypass. Existing Task 16 settings note remains uncommitted and unchanged.

## Exact unverified staging/device exercise

1. Apply V46 in authorized isolated staging. Point existing Sequo/Vendor debug NetworkConfig at the same API; provision one customer, a merchant-owner membership and a separate other customer/merchant. Verify each `/api/auth/me` session. Use only authorized fixture identities/test payment configuration.
2. Publish an active priced eligible product with an enabled bargaining switch. Customer opens product detail and sends a price below the list price. In the separate Vendor session, open Account → negotiations and Refresh, inspect the same product/thread/offer, counter and Refresh. Customer refreshes and accepts the current counter. Compare server attempt counts, history, accepted price and actual expiry on both apps.
3. On another product/thread, alternate customer offer → seller counter → customer rejection three times. Fourth/new-thread/reload/changed-key attempts must fail. Try guessed IDs, forged merchantId queries and the other customer's cart reference. Try a stale expectedOfferId and simultaneous same-key submissions; no duplicate attempt/agreement should appear.
4. Add the accepted product to the basket with its thread reference, including a valid option group if applicable. Change quantity and reload: agreement must persist, base discount applies, supplements remain server-priced. Keep a catalog-price line too and verify it has a distinct ID/quantity. Review the server quote and submit through `/api/orders/checkouts`, then refresh its pending state. Normal approved test payment evidence may proceed through the existing payment workflow; no client success flag is supported.
5. In a disposable fixture only, set the agreement expiry to a past timestamp (or wait the configured validity). Refresh: existing referenced line is unavailable; a new stale checkout returns 409. An existing pending checkout still retries to its same frozen identity/total. Remove the expired line, re-add without the reference and explicitly review the catalog quote before another checkout. Never backdate production agreements to run this check.
6. Disable negotiation or deactivate the product through its authorized merchant editor and reload. New monetary actions must fail. Sign out/restore both apps, simulate offline/readback failure, then reconnect and refresh. Physical device rendering/session restoration and deployed staging/provider execution remain unverified locally.

## Safe dependency for the next prompt

Real customer and Vendor sessions now share authenticated persisted negotiation, attempt budgets across threads, stale/replay-safe decisions and a 24-hour default accepted base price. Basket/quote/new checkout validate party/product/expiry and persist the true agreement reference; unverified payments stay pending. Simple refresh and readable local errors are available. Historical-minimum display, attempt resets, variant-specific locks, new realtime notifications and provider execution require their own approved work.

## Changed files

The following list excludes the unrelated customer test typo, Vendor Xcode project edit, audit/output/tmp files and the explicitly uncommitted Task 16 note.

sequo-api:

- [docs/POSTMAN_ENDPOINTS.md](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/docs/POSTMAN_ENDPOINTS.md)
- [docs/TASK18_NEGOTIATION.md](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/docs/TASK18_NEGOTIATION.md)
- [src/main/kotlin/dev/orestegabo/sequo_api/api/ApiErrors.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/api/ApiErrors.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/commerce/CartDeliveryQuote.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/commerce/CartDeliveryQuote.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/commerce/CommerceApi.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/commerce/CommerceApi.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/commerce/PersistedBargaining.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/commerce/PersistedBargaining.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/order/CustomerCartCheckout.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/order/CustomerCartCheckout.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/order/OrderController.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/order/OrderController.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/order/OrderFulfillmentPersistence.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/order/OrderFulfillmentPersistence.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/order/OrderProcessing.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/order/OrderProcessing.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/order/PendingPaymentCheckout.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/order/PendingPaymentCheckout.kt)
- [src/main/resources/application.properties](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/resources/application.properties)
- [src/main/resources/db/migration/V46__bargaining_replay_and_cart_agreements.sql](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/resources/db/migration/V46__bargaining_replay_and_cart_agreements.sql)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/commerce/BargainingCheckoutHttpTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/commerce/BargainingCheckoutHttpTest.kt)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/commerce/CommerceServiceTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/commerce/CommerceServiceTest.kt)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/commerce/MerchantCatalogHttpTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/commerce/MerchantCatalogHttpTest.kt)

Sequo:

- [BARGAINING_FLOW.md](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/BARGAINING_FLOW.md)
- [shared/src/androidHostTest/kotlin/dev/orestegabo/sequo/BargainingClientTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/androidHostTest/kotlin/dev/orestegabo/sequo/BargainingClientTest.kt)
- [shared/src/commonMain/composeResources/values-fr/strings.xml](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/commonMain/composeResources/values-fr/strings.xml)
- [shared/src/commonMain/composeResources/values/strings.xml](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/commonMain/composeResources/values/strings.xml)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequo/core/bargaining/BargainingRepository.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/core/bargaining/BargainingRepository.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequo/core/catalog/CartApiClient.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/core/catalog/CartApiClient.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequo/feature/settings/AppText.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/feature/settings/AppText.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/app/SequoShell.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/app/SequoShell.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/basket/BasketContent.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/basket/BasketContent.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/catalog/ProductBargainingPanel.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/catalog/ProductBargainingPanel.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/catalog/ProductDetailContent.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/catalog/ProductDetailContent.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/catalog/ProductOptionsPanel.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/catalog/ProductOptionsPanel.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/home/HomeContent.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/home/HomeContent.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/markets/MarketsContent.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/markets/MarketsContent.kt)

SequoVendor:

- [shared/src/androidHostTest/kotlin/dev/orestegabo/sequovendor/VendorNegotiationClientTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/src/androidHostTest/kotlin/dev/orestegabo/sequovendor/VendorNegotiationClientTest.kt)
- [shared/src/commonMain/composeResources/values-fr/strings.xml](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/src/commonMain/composeResources/values-fr/strings.xml)
- [shared/src/commonMain/composeResources/values/strings.xml](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/src/commonMain/composeResources/values/strings.xml)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/negotiation/NetworkVendorNegotiationRepository.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/negotiation/NetworkVendorNegotiationRepository.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/negotiation/VendorNegotiationApiClient.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/negotiation/VendorNegotiationApiClient.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/negotiation/VendorNegotiationRepository.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/negotiation/VendorNegotiationRepository.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/feature/settings/AppText.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/feature/settings/AppText.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/model/VendorModels.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/model/VendorModels.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/ui/account/AccountContent.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/ui/account/AccountContent.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/ui/app/SequoVendorShell.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/ui/app/SequoVendorShell.kt)
