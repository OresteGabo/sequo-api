# Task 15 — Relay pickup, history and overdue operations

Signed PDF evidence: pages 5 and 12–13, clauses 58, 60, 61, 108, 127–130 and 139. The source requires code/QR plus identity confirmation, custody history, possible fees strictly after eight days, and operator review/return at thirty days. The audit's 14/28-day findings were stale: Task 01 already corrected the defaults. This task retains those defaults and existing fee snapshots/history.

## Result

- New deposits issue a server-managed six-digit code and opaque QR nonce. The existing credential table stores hashes and a nullable version marker, not raw values. HMAC with the existing protected server secret reconstructs only managed credentials for the owning customer. Existing manual/hash-only credentials remain usable; an explicit renewal supersedes them without deleting history. Secret rotation requires renewal when a stored hash no longer matches the reconstructed credential.
- `GET /api/orders/{orderId}/relay-pickups` is owner-only and no-store. Another customer, operator or administrator cannot retrieve that customer's credential through it. Customer Orders displays the actual numeric code and locally rendered QR matrix, expiry, deposit and pickup timestamps, and the identity-document instruction. Codes disappear at expiry, on refresh failure, when changing account and when custody is closed. `POST /api/orders/{orderId}/relay-pickups/{parcelId}/credential` explicitly creates/renews a credential; valid managed credentials are reused. Operator issuance returns metadata only through `POST /api/relay/parcels/{id}/pickup-credential`.
- The selected API-served `/relay-operator.html` module accepts numeric or decoded QR scanner input and a required identity confirmation. No ID image/number is requested or stored. The server enforces identity confirmation even for older optional-identity records. Codes, QR values and tokens are excluded from generic operator/history responses and logs.
- Public parcel release now locks the parent order, linked mission and parcel in consistent order, consumes the credential once, releases the occupied slot, advances the linked mission to RELEASED_BY_RELAY and invokes the existing order lifecycle in one transaction. Same-key concurrent/repeat submissions succeed without duplicate custody or completion events. New attempts after consumption fail. Parent completion waits for other known missions/sub-orders, while supported unlinked legacy missions retain their existing behavior. Numeric and QR expiration and failed-attempt checks are retained.
- `GET /api/relay/parcels/overdue?relayPointId=...` resolves operator scope and reads computed deadlines from persisted deposit/policy values. `POST /api/relay/parcels/deadlines/evaluate` explicitly records current delay/review transitions and history; the existing scheduler uses the same locked implementation. Fees become eligible strictly after the stored eight-day boundary; review is due at thirty days. Missing legacy fee-policy snapshots stay flagged unknown and do not become retroactive charges.
- Scoped operators can record a hold/SEQUO coordination decision via `POST /api/relay/parcels/{id}/review`. Only existing authorized SEQUO staff roles can record physical seller return through the existing `return-to-seller` route; the web action requires a receipt note and explicit confirmation. It records history, closes the linked delivery mission and frees its system-occupied slot. It does not mark the order delivered, create a refund, charge fees or automate disposition.

## Verification

Commands from sequo-api:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test --tests '*CustomerCartCheckoutHttpTest' --tests '*CustomerCheckoutRestartTest' --tests '*Relay*Test' --tests '*HubMobile*Test' --tests '*DeliveryMission*Test' --tests '*OrderDeliveryLifecycle*Test'
NODE_PATH=/opt/homebrew/lib/node_modules/@google/gemini-cli/node_modules node scripts/relay-operator-browser-check.cjs
node --check src/main/resources/static/relay-operator.js
```

The existing paid local fixture now additionally verifies owned credential read/reload, denied cross-account reads, real ZXing QR decoding, expired credentials, explicit renewal, invalidated old QR, missing identity, concurrent valid release, used-code rejection, final customer order/mission state, explicit overdue review, denied operator seller return, authorized/repeated seller return and absence of automatic fee assessments. `RelayDeadlinesTest` checks just before, at and just after eight and thirty days, resolved records and unknown legacy fee policy. Existing relay/history/notification/lifecycle regressions run with them.

Both new real-HTTP journeys passed separately on a migrated disposable PostgreSQL 15 database, including V43, actual foreign keys and row locks:

```sh
SEQUO_RELAY_JOURNEY_TEST_URL=jdbc:postgresql://127.0.0.1:55434/sequo_task14_pickup JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test --tests '*CustomerCartCheckoutHttpTest.managedRelayPickupIsOwnedReloadableExpiredAndReleasedAtomically' --tests '*CustomerCartCheckoutHttpTest.overdueReviewAndSellerReturnAreExplicitScopedAndDoNotChargeAutomatically'
```

The inherited fixture guard only permits disposable `sequo_task14_*` loopback databases and fixture user `sequo_fixture`. No staging/production connection or provider execution was used. Initialize/start the temporary PostgreSQL cluster with explicit loopback host/port/socket as described in Task 14; a default PostgreSQL port may belong to another local service and must not be changed or stopped.

The Chrome fixture check passes mobile-width sign-in, overdue evaluation/review, managed issuance, release, refresh restore, rendered review/withdrawal history after reload, offline errors and expired-session clearing. It uses mock loopback HTTP, while server tests use actual HTTP/database transitions. ZXing 3.3.3 is an explicit backend dependency, available in the local Gradle cache; the client renders returned QR modules without an external image service.

From Sequo, `./gradlew --offline :shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64` passed. The pre-existing split function name in `DeliveryPricingTest.kt:59` still blocks the customer host suite; it was preserved and excluded from commits. Physical-device QR scanning, deployed staging login/refresh and live provider checks remain unverified.

## Deployment and operator decisions

Apply V43 before this API. It adds only a nullable managed-credential version and permits DELAY/REVIEW custody event types; no old credential/hash is removed or retrospectively exposed. Keep the existing protected notification-token encryption secret stable and backed up: managed credential derivation uses a distinct HMAC namespace under that protected secret. Production startup already rejects its development default.

SEQUO still must approve any fee amount and treatment of old parcels lacking a fee-policy snapshot. This screen never assesses fees or invents a tariff. Seller return requires actual physical receipt and authorized staff confirmation; refunds and alternative disposition remain explicit subsequent workflows. Broken legacy parcel/order/mission linkage fails closed and needs an approved repair, not a guessed owner or destination.

## Exact unverified device/staging exercise

1. Deploy V43 and this API to an isolated staging environment. Point a debug Sequo build at that API. Use the Task 14 approved point/operator/logical-slot configuration and a real paid staging order/deposit.
2. Open that customer's Orders and select pickup code/QR. Refresh and confirm it is the same active code. In a second customer session, try the first customer's pickup endpoint and confirm 404. Operator/admin sessions must not retrieve the customer's raw credential.
3. In `/relay-operator.html`, sign in as the correct operator, enter a wrong code, omit identity confirmation, then scan the actual QR from the customer device (decoded scanner value goes into QR input). Confirm matching identity and submit. Repeat the identical request/key and refresh both apps; expect one withdrawal, released delivery and completed order only when all fulfillment is complete.
4. With a separate fixture, expire its credential in the authorized staging fixture setup. Confirm the customer view hides it, the operator receives expiry rejection, and explicit renewal produces a new credential. The old QR must fail; a valid numeric code must succeed. Re-submit a consumed credential with a new key and expect rejection.
5. Use synthetic fixture deposit timestamps at eight days minus/exact/plus a small interval and thirty days minus/exact/plus. Refresh the overdue filter and explicitly evaluate deadlines. Confirm no eight-day fee eligibility at equality, review at thirty-day equality, and no fee record or automatic disposition. Record a hold note, reload the page and see its history. Confirm only SEQUO staff can record a physical seller return after review is due.
6. Restart the staging API and apps, refresh and inspect the same history. Disconnect during release, reconnect and retry the same key. Physical scanner/device and deployed restart behavior are unverified until this exercise is run with authorized access.

## Next prompt can depend on

Owner-only managed credential display, actual QR data, no-store reads, explicit renewal, atomic parcel/mission/order pickup, stored withdrawal/deadline/decision history, scoped overdue reads and operator-controlled seller return. No payment/refund execution or tariff approval is implied.

Final local regression result: 83 tests passed and the opt-in migration test was skipped by the standard command. Both explicit PostgreSQL pickup/overdue tests passed separately.

## Changed files

sequo-api:

- `build.gradle.kts`
- `docs/POSTMAN_ENDPOINTS.md`
- `docs/TASK15_RELAY_PICKUP.md`
- `scripts/relay-operator-browser-check.cjs`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/order/OrderFulfillmentPersistence.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/relay/RelayCustomerPickup.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/relay/RelayFulfillment.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/relay/RelayParcelController.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/relay/RelayParcelPersistence.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/relay/RelayParcelService.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/relay/RelayPickupOperations.kt`
- `src/main/resources/db/migration/V43__mark_customer_relay_credentials.sql`
- `src/main/resources/static/relay-operator.html`
- `src/main/resources/static/relay-operator.js`
- `src/test/kotlin/dev/orestegabo/sequo_api/domain/order/CustomerCartCheckoutHttpTest.kt`
- `src/test/kotlin/dev/orestegabo/sequo_api/domain/relay/RelayDeadlinesTest.kt`

Sequo:

- `shared/src/commonMain/composeResources/values-fr/strings.xml`
- `shared/src/commonMain/composeResources/values/strings.xml`
- `shared/src/commonMain/kotlin/dev/orestegabo/sequo/core/catalog/CartApiClient.kt`
- `shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/app/SequoShell.kt`
- `shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/orders/OrdersContent.kt`
