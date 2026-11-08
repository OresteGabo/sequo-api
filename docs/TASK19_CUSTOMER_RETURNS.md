# Task 19 — Customer return request and status

## Scope and contract evidence

Read the signed `/Users/muhirwagabooreste/Downloads/contrat_mvp_sequo_signed-2.pdf` pages 5 and 12–13. Audit rows 62/63/115/138/140 located ReturnController, ReturnProcessingService and persisted handoff data. The PDF defines scope; older repository notes incorrectly promised mandatory relay intake and automatic refunds.

| Reference | Contract point | Implementation evidence |
| --- | --- | --- |
| 62 | Request within 72 hours following delivery. | Server clock plus persisted paid handoff time, inclusive deadline, owned delivered order; request payload cannot backdate or reset it. Exact −1 ns / at / +1 ns boundary regression. |
| 63 | Relay drop-off when eligible; customer-managed Click and collect example. | Return request and relay eligibility are separate. Only actual available eligible relays are shown. Unsupported transport stays Requested with explicit pending instructions. Click treatment remains pending SEQUO agreement. |
| 115 | Customer requests an eligible return in time. | Actual customer Orders screen → server eligibility → minimal request → canonical persisted ID, status/instructions and refresh. No local fake ID/PIN or local success state. |
| 138 | Return-request delay modifiable/prepared. | RETURN_WINDOW_HOURS defaults 72; request eligibility and new delivery/pickup deadline producers use it. Existing handoff retries preserve original timestamps. |
| 140 | Categories excluded from relay/returns configurable/prepared. | Explicit approved RETURN_EXCLUDED_CATEGORIES, default empty. Existing relay policy separately excludes food, perishables and sensitive goods using the order's server category data. No blanket food-return ban was inferred from relay exclusion. |

## Runtime changes

- Existing 72-hour domain comparison was already inclusive. The remaining live gaps were client-controlled requestedAt/policy flags, early ID replay before ownership, unsafe assigned-ID merge, duplicate whole-order claims, missing customer order reads/UI, unrecoverable old PINs, and replay paths that could reset handoff/deadline or reopen a returned order. These were corrected.
- POST `/api/returns` now accepts the customer's minimal returnId/orderId/reason. Authenticated identity, current request time, item refund ceiling, eligibility and PIN come from the server. Customer amount, PIN, timestamps, productReturnable, merchantAllowsReturn and adminOverride fields cannot change those decisions. A forged customerId is denied. Existing staff exception/manual-PIN capabilities remain role-gated, using actual server request time.
- The customer action uses the real paid delivered order and its persisted line/checkout categories. Sealed goods retain both their product-kind category and frozen food/perishable/sensitive classification; changing the current catalog category cannot bypass an approved exclusion. Whole-order requests are the existing MVP model; item-level partial selection was not invented. No request/relay deposit starts a payment or promises a refund amount.
- Identity/order binding is checked before replay. An order row lock serializes duplicate and concurrent requests. The original ID is returned even for a new proposed ID on the same order; same-ID changed reason (or a staff amount intent) is rejected. Insert-only persistence prevents another order/customer racing on the same ID from overwriting it; the loser gets a controlled conflict. Existing duplicate legacy rows remain readable and are not deleted.
- GET `/api/returns/orders/{orderId}/eligibility`, owned order return list and existing owned list/detail expose actual deadline, canonical reference, current status and instructions. Customer views omit PIN hashes, operator identity, inspection notes and responsibility. Private responses are no-store. Relay reads require an assigned active site; intake rejects forged site IDs and uses server time. Atomic record locks preserve one drop-off and its original timestamp on repeat.
- New return PINs use the existing protected server secret and versioned HMAC derivation; only a hash and version are stored. The authorized owner can retrieve the same real PIN after restart while relay drop-off is active. After deposit it is hidden. Legacy hashes cannot be reconstructed or invented: those records retain their ID/hash/status and show instructions to contact SEQUO for credential confirmation.
- Relay destinations come from configured persisted points, configured operator and active site, valid coordinates, opening hours, capacity and CUSTOMER_RETURNS/LOCKER_INTAKE controls. The immutable checkout category snapshot is preferred via the existing relay eligibility service, with its supported legacy fallback. No relay address was invented.
- No active eligible relay or a relay-excluded product means Requested / SEQUO_INSTRUCTIONS_PENDING, without a relay PIN or an instruction to leave the parcel. A return request can still be recorded for review when relay handling is unsuitable. Product return exclusions require explicit approved configuration; the local ineligible-food fixture is not a production policy.
- Click and collect defaults to PENDING. Its request can be recorded within the deadline as Requested / CUSTOMER_MANAGED_PENDING; no extra pickup-product exclusion, relay destination or automatic rider movement is applied. A concise clarification was requested during this task. No answer had arrived when this report was written, so activation remains blocked on SEQUO's treatment decision. Prepared modes SELLER_MANAGED and RELAY must only be configured after that agreement.
- Pickup now locks the order and checks ownership before confirmation replay. New keys after delivery/return/refund return the original confirmation, without resetting deliveredAt or the deadline. First pickup/delivery requires paid active fulfillment. Mission replay preserves later return/refund/cancellation state and does not repost financial writes. Historical bad timestamps are not silently repaired.
- Customer app uses the actual Orders rows, small expandable request/status section, stable saved retry ID and frozen uncertain-submit reason, followed by server readback. It shows server PINs/point names, EN/FR state/instruction copy and readable error/retry states. Ineligible orders do not display drop-off directions. The old unused fake request/PIN panel now points to actual Orders; unrelated private legacy helpers were preserved.

## Acceptance results

| Check | Local result |
| --- | --- |
| Just inside / at / outside 72 hours | PASS: −1 ns and exact deadline accepted; +1 ns rejected with return_window_expired. Client requestedAt and adminOverride do not bypass this. Deadline uses the stored handoff and server Clock. |
| Ineligible product | PASS: an explicitly configured LOCAL_FIXTURE_ONLY Food exclusion rejects the request despite forged true policy flags. Separately, a sensitive relay-ineligible order records Requested for manual instructions with no relay/PIN. Default domain food request is recorded for review, not universally banned. |
| Repeated submission | PASS: same-ID concurrent retry and different-ID same-order retry return one canonical record. Changed original reason is rejected. Concurrent cross-customer ID collision gives one success and one controlled 400/409, preserving both order ownership and the winner's PIN/data. |
| Another customer's order | PASS: eligibility, order-list, detail, forged customer ID and existing-ID collision calls are denied; customerId list query cannot reveal another customer's returns. No foreign PIN is returned. |
| Persisted return ID after restart | PASS: actual HTTP POST → complete application-context shutdown → new application context/server port using the same H2 file → fresh JWT GET/retry. ID, status and real PIN match, with one stored request. Legacy version-null read retains hash and reports credential instructions without inventing a PIN. |
| Relay scope and repeat | PASS: two operators cannot read unassigned returns or forge another site. Wrong PIN fails; correct authorized intake succeeds once; later repeat keeps the first timestamp. Deposited return is readable only at its site; customer PIN disappears. |
| Click and collect | PASS for safe pending behavior: a pickup food request under the local exclusion fixture is recorded pending agreement, without a new pickup-specific category ban, PIN or relay directions. Final transport treatment remains a business blocker. |
| Device/deployed staging | UNVERIFIED: no authorized deployed account session or physical device was supplied. Local network/compiler fixtures are not a physical handoff/provider exercise. Exact steps follow. |

## Commands and results (2026-11-08)

Existing ReturnProcessingServiceTest, ReturnPersistenceServiceTest and ReturnWorkflowNotificationTest passed before changes. Final run from `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api`:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test --tests '*Return*Test' --tests '*HandoffReplayIntegrityTest' --tests '*CustomerCartCheckoutHttpTest' --tests '*OrderFulfillmentPersistenceServiceTest' --tests '*OrderDeliveryLifecycleNotificationTest' --tests '*ContractBusinessParameterReadinessTest'
```

PASS: **54 tests**, zero failures/errors/skips. Includes six new HTTP scenarios, actual application restart, six handoff integrity regressions, existing return/notification workflows, configured-policy tests and prior checkout/home/relay/consolidation/mode paths. Old tests that treated the PDF as a blanket food-return ban now exercise an explicit configured exclusion. Log `/tmp/sequo-task19-regressions.log`; restart XML `/tmp/sequo-task19-restart-results.xml`.

```sh
SEQUO_RETURN_JOURNEY_TEST_URL=jdbc:postgresql://127.0.0.1:55434/sequo_task14_returns JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test --tests '*CustomerReturnHttpTest'
```

PASS: **6 HTTP tests** with PostgreSQL 15, Flyway V1–V47, JPA schema validation, real foreign keys/row locks and primary-key collisions. After API shutdown, SQL retained Requested/AwaitingRelayDropoff/DroppedAtRelay records with credential version 1 and **zero orders with duplicate returns**. The test guard only accepts disposable loopback `sequo_task14_*` databases under `sequo_fixture`; no production data was used. Only the task-owned port 55434 cluster was started/stopped. Evidence `/tmp/sequo-task19-postgres.log`, `/tmp/sequo-task19-postgres-results.xml`, `/tmp/sequo-task19-postgres-evidence.txt`.

From `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo`:

```sh
./gradlew --offline :shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64
./gradlew --offline :shared:testAndroidHostTest --tests '*ReturnClientTest'
```

Main Android/iOS compilation PASS. The normal focused host command remains **BLOCKED** at the pre-existing malformed `calculateDeliver yPricing` edit in `shared/src/commonTest/kotlin/dev/orestegabo/sequo/domain/DeliveryPricingTest.kt:59`; it was preserved, not staged. To execute the new tests without modifying it, `/tmp/sequo-task18-customer-tests.init.gradle` contains:

```groovy
gradle.projectsEvaluated {
    def testCompile = rootProject.findProject(':shared').tasks.getByName('compileAndroidHostTest')
    testCompile.exclude('**/DeliveryPricingTest.kt')
}
```

```sh
./gradlew --offline --no-configuration-cache -I /tmp/sequo-task18-customer-tests.init.gradle :shared:testAndroidHostTest --tests '*ReturnClientTest' :shared:compileKotlinIosSimulatorArm64
```

PASS on final sources: **2 network fixture tests**, Android main compilation through the host task, iOS simulator compilation. Fixtures cover server-owned eligibility, expiry/pending policy, denied reads, exact minimal request fields, stable retry body, canonical ID, real PIN/status readback. This does not claim a passing normal/full host suite. Logs `/tmp/sequo-task19-customer-final.log`, `/tmp/sequo-task19-customer-tests.log`. Changed repositories pass `git diff --check`.

## Migration and operator decisions

1. Apply **V47__return_customer_credentials.sql** before deploying the new API/client flow. It adds a nullable version marker without rewriting old PIN hashes, claims, delivery timestamps, fees or refund history. Keep the protected credential secret stable; unknown/rotated/legacy credentials fail closed and require an approved operator recovery process.
2. **Click-and-collect routing is pending agreement.** The PDF says the customer manages it but does not specify the final intake location/treatment. Keep RETURN_CLICK_AND_COLLECT_MODE=PENDING until SEQUO decides. Request recording/deadline protection remains available. Do not interpret pending requests as approved relay/seller transport or a refund instruction.
3. RETURN_WINDOW_HOURS defaults 72; RETURN_EXCLUDED_CATEGORIES defaults empty. Any new exclusion or active-order window change requires an approved policy. Review old timestamp inconsistencies against handoff proofs, duplicate/rejected legacy claims and any financial hold implications before changing active data. This task does not automatically repair or delete them.
4. The request model remains whole-order. Mixed/partial-item refunds, credential renewal/attempt budgets, full return custody/inspection and provider execution are separate work. Existing authorized receipt/refund endpoints remain, with no new customer refund execution permission.
5. No device/staging access was available. The explicitly uncommitted Task 16 note and unrelated customer/Vendor edits remain untouched.

## Exact unverified staging/device exercise

1. Apply V47 to approved isolated staging. Configure Sequo's existing debug NetworkConfig to that API; use authorized customer, second customer, relay operator and staff sessions. Confirm the delivered order comes from the normal verified payment → preparation → assigned courier → customer handoff flow. Keep production secrets/identities out of fixtures.
2. In customer Orders, open Return options on that delivered order. Refresh server eligibility/deadline. Enter a reason and submit. Record the returned ID/status; refresh and relaunch the app. Confirm the same server ID, not a generated success screen. Retry the same body after disconnecting the response, then retry a new proposed ID: there must still be one order claim.
3. With approved active relay configuration (operator, coordinates, opening hours, available logical slot, CUSTOMER_RETURNS and LOCKER_INTAKE enabled), verify the actual displayed locations and owner-only server PIN. For unavailable/excluded relay handling, verify Requested/pending instructions without a PIN or invented address. Do not move parcels until the permitted route is confirmed.
4. In a disposable fixture only, set server handoff/clock to just inside, exactly at and just outside 72 hours. Expect accept/accept/reject. Send old requestedAt, true policy flags/adminOverride, arbitrary amount/PIN and a forged customerId: these must not change eligibility/ownership. Never rewrite production handoffs to perform this test. Test ineligible return categories only under an explicitly approved fixture policy, not an invented global food ban.
5. From the second customer, try the order eligibility/list/detail and a guessed existing return ID. Expect denial/no foreign PIN. With two separate relay operators, try unassigned read/forged site/wrong PIN, then authorized physical intake. Repeat intake: original deposit timestamp must remain. Customer refresh must show actual deposited status with no live PIN.
6. Stop/restart the staging API with the same database/protected secret, sign in again and reload the request. ID/status/credential must persist where still active. Repeat old pickup/delivery confirmation after RETURN_REQUESTED: it must not reopen delivery or extend the deadline.
7. Obtain SEQUO's Click-and-collect treatment before enabling its transport mode or product-specific additions. Test that agreed branch on devices. Physical packaging, device rendering/offline session restoration and deployed custody/provider checks remain unverified locally. A return request or deposit is not proof of SEQUO receipt, inspection approval or completed refund.

## Safe dependency for the next prompt

Owned customer requests/status/instructions now use persisted paid handoff time, a 72-hour default inclusive window, server category policy, canonical replay-safe whole-order records and restart-stable owner credentials. Relay access is scoped and handoff replay cannot reset the return clock. Task 20 can use the real return ID/status/PIN hash/version and explicit pending transport state for custody/inspection work. Click transport agreement, partial claims and provider execution are not implied.

## Changed files

Listed below; unrelated user edits, audit/output/tmp files and the intentionally uncommitted Task 16 note are excluded.

sequo-api:

- [SETTLEMENTS_AND_RETURNS.md](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/SETTLEMENTS_AND_RETURNS.md)
- [docs/POSTMAN_ENDPOINTS.md](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/docs/POSTMAN_ENDPOINTS.md)
- [docs/TASK19_CUSTOMER_RETURNS.md](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/docs/TASK19_CUSTOMER_RETURNS.md)
- [src/main/kotlin/dev/orestegabo/sequo_api/api/ApiErrors.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/api/ApiErrors.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/order/CustomerPickupConfirmation.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/order/CustomerPickupConfirmation.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/order/OrderFulfillmentPersistence.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/order/OrderFulfillmentPersistence.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/relay/RelayAvailability.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/relay/RelayAvailability.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/returns/CustomerReturnPolicy.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/returns/CustomerReturnPolicy.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/returns/ReturnController.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/returns/ReturnController.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/returns/ReturnPersistence.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/returns/ReturnPersistence.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/returns/ReturnProcessing.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/returns/ReturnProcessing.kt)
- [src/main/resources/application.properties](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/resources/application.properties)
- [src/main/resources/db/migration/V47__return_customer_credentials.sql](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/resources/db/migration/V47__return_customer_credentials.sql)
- [src/test/kotlin/dev/orestegabo/sequo_api/contracts/ContractBusinessParameterReadinessTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/contracts/ContractBusinessParameterReadinessTest.kt)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/order/HandoffReplayIntegrityTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/order/HandoffReplayIntegrityTest.kt)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/returns/CustomerReturnHttpTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/returns/CustomerReturnHttpTest.kt)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/returns/CustomerReturnRestartTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/returns/CustomerReturnRestartTest.kt)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/returns/ReturnPersistenceServiceTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/returns/ReturnPersistenceServiceTest.kt)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/returns/ReturnProcessingServiceTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/returns/ReturnProcessingServiceTest.kt)

Sequo:

- [RETURNS_POLICY.md](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/RETURNS_POLICY.md)
- [shared/src/androidHostTest/kotlin/dev/orestegabo/sequo/ReturnClientTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/androidHostTest/kotlin/dev/orestegabo/sequo/ReturnClientTest.kt)
- [shared/src/commonMain/composeResources/values-fr/strings.xml](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/commonMain/composeResources/values-fr/strings.xml)
- [shared/src/commonMain/composeResources/values/strings.xml](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/commonMain/composeResources/values/strings.xml)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequo/core/catalog/CartApiClient.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/core/catalog/CartApiClient.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequo/feature/settings/AppText.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/feature/settings/AppText.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/app/SequoShell.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/app/SequoShell.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/orders/CustomerReturnContent.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/orders/CustomerReturnContent.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/orders/OrdersContent.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/orders/OrdersContent.kt)
