# Task 14 — Customer relay choice and rider deposit

## Scope and contract evidence

Read signed `contrat_mvp_sequo_signed-2.pdf` pages 5 and 12: clause 56 permits a relay alternative after home absence; 57 allows exclusion of food, perishables and sensitive products; 112 offers home/available relay choice; 122–123 cover courier pickup and the planned destination; 126 covers relay deposit. Audit rows 56, 57, 112, 122, 123 and 126 located the existing cart, dispatch and rider paths. The signed PDF defines the scope; the audit was not treated as current behavior.

The existing PointDeRelai route and mission destination enum are reused. No new tariff or automatic reroute was introduced. The existing distance/fee engine uses the configured dispatch origin and server-stored relay coordinates. Customer contact still comes from an owned saved address; the assigned Rider receives the relay destination, rather than the home recipient/contact details for a relay mission.

## Implemented contract

- `GET /api/cart/relay-points` evaluates the authenticated customer's actual persisted basket and current product data. It returns public destination fields only. Points require ACTIVE status, an explicit operator assignment, valid stored coordinates, an AVAILABLE persisted slot, allowed Hub/intake/pickup control states and configured opening hours. Reroute additionally checks the PLAN_B_DROP_OFF control.
- `GET /api/cart/quote?addressId=<owned-address>&relayPointId=<point>` returns `relayPointId` and `fulfillmentMode=RELAY`. Omit relayPointId for home. Distance comes from stored coordinates; relay destination and the cart are included in the reviewed quote digest. Unavailable/ineligible selections fail rather than reverting to home.
- `POST /api/orders/checkouts` accepts the existing fields plus optional `relayPointId`. It rechecks eligibility, availability and the quote revision before payment initiation. Destination and relay classification are captured in the existing pending-payment payload. Payment reconciliation preserves that snapshot; dispatch copies it to the same order's mission. An already recorded retry retains its original destination/provider, including when the basket was cleared.
- Food, perishable and sensitive classifications share one relay policy for quote, checkout, reroute and deposit. Existing `grocery` inventory is conservatively classified as food; `pharmacy` as sensitive. This prevents seeded fresh food/medications from falling through as general sealed goods. These broad catalog categories can exclude some otherwise standard products; SEQUO must review its catalog classification before enabling relay offers. Home checkout photo rules remain unchanged: relay classification is separate from the existing order/photo category. New paid snapshots preserve classification; older orders fall back to current catalog classification and then their supported stored category.
- Existing Rider and generic courier `/relay-deposit` actions accept `relayPointId`, `lockerCode`, `proofMetadata`, `idempotencyKey`. They require the assigned courier, a paid active eligible order, prior pickup, the persisted destination and an available slot at that exact point. The mission and slot are locked. One transaction records the mission transition, custody parcel and OCCUPIED slot. Same-key/same-slot retry returns current state; changed relay/slot/key conflicts fail. The custody deposit identifier is a deterministic 64-character digest, preserving the existing PostgreSQL column and old history.
- Direct `/api/relay/parcels` creation with `deliveryMissionId` is rejected: linked mission custody must use the transactional action. Unlinked supported legacy/admin/operator parcel reads and creation remain available with their existing authorization and category checks. The older general order processing endpoint directs new relay checkouts to the authoritative basket route.
- A successful numeric/QR parcel pickup releases a slot marked SYSTEM_OCCUPIED by this flow. Unknown legacy/manual occupancy is not automatically changed.
- `POST /api/delivery/missions/{id}/relay-reroute` requires authenticated SUPPORT_AGENT/ADMIN/SUPER_ADMIN, a reported home handoff failure after pickup, a paid eligible order, a currently available point, explicit customer agreement, a reason and stable key. It records actor, reason, time and destination on the mission. Customer/Rider tokens cannot approve it. No extra charge is created. The original checkout remains historical; customer tracking and Rider reads show the current mission destination.

Example staff-approved reroute body (fixture IDs only):

```json
{"relayPointId":"approved-fixture-relay","customerApproved":true,"reason":"Customer unavailable at home; relay agreed","idempotencyKey":"stable-approval-key"}
```

The Rider reports the failure through the existing problem action. Staff approval currently uses the authenticated operational API; no new staff screen or Rider self-approval was added. Refresh Rider after approval.

## Acceptance results

| Check | Result and evidence |
|---|---|
| Eligible basket | Authenticated real HTTP fixture lists a persisted operated point, quotes its stored coordinates and submits real product lines. Normal signed webhook reconciliation creates a paid order with PointDeRelai route; merchant preparation and dispatch preserve the point. |
| Ineligible basket | Food/perishable/sensitive and existing grocery/pharmacy categories yield no offered points and reject relay quotes. Paid home orders of each class reject approved reroute. Standard sealed home products keep their existing generic-image behavior. |
| Unavailable relay | Missing coordinates, inactive point and maintenance/full slot remove the point from offers. A point made unavailable after quote causes checkout 409 with no pending checkout. Availability is rechecked at deposit. |
| Wrong deposit | Wrong relay, guessed slot, old proof-only payload and changed-slot retry are rejected. Two simultaneous valid retries consume one persisted slot/create one parcel. |
| Authorized reroute | Rider/customer approval attempts, missing agreement, pre-failure and unavailable-point approval fail. Valid staff approval and retry succeed; Rider sees the changed destination and deposits at it. |
| Same order across apps | Customer order tracking, Rider mission, operations detail and scoped relay parcel share the same order/mission/point and persisted deposit timestamp. Server readbacks run against H2 and migrated PostgreSQL. Rider client loopback tests verify typed destination/slot JSON. Physical app interaction remains unverified. |

## Verification commands and results (2026-11-08)

From sequo-api, Java 21:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test --tests '*CustomerCartCheckoutHttpTest' --tests '*CustomerCheckoutRestartTest' --tests '*Relay*Test' --tests '*HubMobile*Test' --tests '*DeliveryMissionControllerTest' --tests '*DeliveryReadinessDispatchServiceTest' --tests '*OrderProcessingTest' --tests '*DatabaseMigrationValidationTest'
```

90 tests passed. The opt-in PostgreSQL migration test is skipped by this normal H2 command. It was separately enabled and passed against a newly initialized disposable PostgreSQL 15 database, applying all Flyway migrations through V42 and validating JPA against the migrated schema:

```sh
SEQUO_RELAY_MIGRATION_TEST_URL=jdbc:postgresql://127.0.0.1:55434/sequo_task14_fixture JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test --tests '*RelayDestinationMigrationTest'
```

Two new paid real-HTTP journeys also passed on migrated PostgreSQL, exercising foreign keys, persisted slots and row locks:

```sh
SEQUO_RELAY_JOURNEY_TEST_URL=jdbc:postgresql://127.0.0.1:55434/sequo_task14_journey JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test --tests '*CustomerCartCheckoutHttpTest.relaySelectionPersistsThroughPaidDispatchAndAtomicDeposit' --tests '*CustomerCartCheckoutHttpTest.failedHomeHandoffRequiresStaffCustomerApprovalAndSharedExclusionsForReroute'
```

Both opt-in URLs are restricted in test code to disposable `sequo_task14_*` databases on 127.0.0.1 with fixture-only user `sequo_fixture`. A local PostgreSQL cluster was initialized under `/tmp/sequo-task14-pg.*`, bound to loopback port 55434, and stopped after verification. For reproduction, initialize an empty local cluster with `initdb -U sequo_fixture -A trust --no-locale`, start it on the explicit loopback port, create the two named fixture databases and run the commands above. Never point these exercises at staging/production data. Initial PostgreSQL testing caught the existing deposit-code length constraint; the final hash implementation passed both journeys. After restarting this disposable PostgreSQL instance, SQL readback confirmed two deposited missions still had their destination snapshot and deposit timestamp.

From Sequo:

```sh
./gradlew --offline :shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64
./gradlew --offline :shared:testAndroidHostTest
```

Android/iOS simulator compilation passed. The customer host suite fails during compilation at the pre-existing `DeliveryPricingTest.kt:59` edit (`calculateDeliver yPricing`); that unrelated working-tree change was preserved and excluded from commits. Customer host/UI tests are therefore not claimed as passing.

From SequoRider:

```sh
./gradlew --offline :shared:testAndroidHostTest :shared:compileKotlinIosSimulatorArm64
```

Six host tests and iOS simulator compilation passed; this also compiles Android main sources. The added loopback HTTP test checks current relay name/location, removal of home-contact fallback and explicit point/slot/proof/key serialization.

## Deployment and unresolved operator decisions

1. Apply V42 before deploying this API. It adds nullable coordinates to points and nullable destination/approval fields to missions. It does not infer destinations, assignments or charges for existing records.
2. SEQUO must approve actual point coordinates, operator assignment/status, catalog classification, opening/control settings and logical staging slots. A logical slot still needs real rows in `relay_locker_grids` and `relay_lockers`, with `relay_point_id` pointing to the approved point, a grid belonging to that point, `locker_code` such as `SLOT-1`, status AVAILABLE and version 0. Existing Task 13 operator assignment enforcement remains in force.
3. Deploy the updated customer and Rider clients together with the API before enabling relay deposits. Old proof-only deposit clients now fail closed. Existing pending relay missions without a chosen point/snapshot need an approved destination repair; no automatic backfill or guessed relay is supplied. Historical parcels/missions remain readable. Existing stored-category fallback for legacy orders needs catalog review if their original classification is ambiguous.
4. Availability is checked at selection, checkout and deposit. Checkout does not reserve a physical slot while payment/preparation is pending. A point that fills later requires operator resolution; the system never silently switches it. Rerouting is staff/customer approved after a failed home handoff, with the original agreed price retained. Confirm SEQUO's operational consent procedure before using that staff action.
5. Parcel pickup-to-parent order/delivery completion and customer credential notifications remain the subsequent relay pickup journey integration described in Task 13. This task connects destination and deposit, while retaining that existing boundary.

## Exact unverified staging/device reproduction

- Point debug Sequo and SequoRider builds at the same authorized isolated API through their existing `core/network/NetworkConfig.kt` settings. Apply V42 and configure the existing dispatch origin. Provision customer, merchant, Rider, staff and two operator identities in authorized staging. Use its normal test payment evidence; never client-success flags.
- Seed two approved points with stored coordinates, explicit operators, ACTIVE controls and persisted logical grids/slots. In Sequo add a standard sealed product, choose a point and owned contact address, review the server quote and submit. Confirm payment via the existing isolated webhook/provider fixture; merchant accepts, prepares and marks packed, then staff dispatches/offers the mission. Refresh Rider and accept/pick up with real custody evidence.
- Confirm customer and Rider show the same order/point. Try another relay ID and nonexistent slot through the deposit API: expect 400 and no custody change. Enter the correct `lockerCode` in Rider and deposit. Repeat the same key; verify one parcel and occupied slot in `/relay-operator.html`, customer tracking and operational detail.
- Repeat selection with food/perishable/sensitive products, mark a point inactive or a slot unavailable after quote, and confirm empty choices/409 review behavior. Closing a chosen point must require explicit destination review rather than silently switching to home.
- For a separate eligible paid home order, Rider picks up then reports customer unavailable. Staff records real customer agreement and calls the approval route above. Rider refreshes to the new point and deposits. Try the same approval using customer/Rider tokens and with an excluded order; expect denial. Verify no additional charge.
- Restart the staging API and apps and verify the same destination/custody state. Exercise offline/stale refresh and scanner/device behavior. These physical-device, deployed staging and live provider checks remain unverified; no staging access was provided.

## Safe dependency for the next prompt

The next task can depend on authoritative relay offers/quotes, persisted checkout and mission destinations, shared captured eligibility, staff-approved rerouting, atomic assigned-rider deposit into a persisted slot and scoped parcel custody. It can use parcel `deliveryMissionId`/`orderId` to integrate customer pickup, credential delivery and final order/delivery completion without recreating checkout or deposit.

## Files changed

sequo-api:

- `docs/POSTMAN_ENDPOINTS.md`
- `docs/TASK14_RELAY_FULFILLMENT.md`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/commerce/CartDeliveryQuote.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/DeliveryMissionController.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/DeliveryMissionPersistence.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/DeliveryReadinessDispatch.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/hub/HubMobileRepositories.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/order/CustomerCartCheckout.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/order/OrderController.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/order/OrderProcessing.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/order/PendingPaymentCheckout.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/party/PartyMasterRecords.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/relay/RelayAvailability.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/relay/RelayFulfillment.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/relay/RelayParcelController.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/relay/RelayParcelPersistence.kt`
- `src/main/resources/db/migration/V42__persist_relay_destinations.sql`
- `src/test/kotlin/dev/orestegabo/sequo_api/database/RelayDestinationMigrationTest.kt`
- `src/test/kotlin/dev/orestegabo/sequo_api/domain/order/CustomerCartCheckoutHttpTest.kt`
- `src/test/kotlin/dev/orestegabo/sequo_api/domain/relay/RelayOperatorHttpTest.kt`

Sequo:

- `shared/src/commonMain/composeResources/values-fr/strings.xml`
- `shared/src/commonMain/composeResources/values/strings.xml`
- `shared/src/commonMain/kotlin/dev/orestegabo/sequo/core/catalog/CartApiClient.kt`
- `shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/app/SequoShell.kt`
- `shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/basket/BasketContent.kt`
- `shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/orders/OrdersContent.kt`

SequoRider:

- `shared/src/androidHostTest/kotlin/dev/orestegabo/sequorider/RiderMissionClientTest.kt`
- `shared/src/commonMain/kotlin/dev/orestegabo/sequorider/core/missions/DemoMissionRepository.kt`
- `shared/src/commonMain/kotlin/dev/orestegabo/sequorider/core/missions/RiderMissionApiClient.kt`
- `shared/src/commonMain/kotlin/dev/orestegabo/sequorider/model/RiderModels.kt`
- `shared/src/commonMain/kotlin/dev/orestegabo/sequorider/ui/app/SequoRiderShell.kt`
- `shared/src/commonMain/kotlin/dev/orestegabo/sequorider/ui/route/ActiveRouteScreen.kt`

## Implementation commits

- sequo-api `653e38a`: authoritative relay checkout, persisted destinations, shared eligibility, staff reroute, atomic custody and server regressions.
- Sequo `8fc20b5`: customer relay chooser, explicit unavailable-choice review and persisted destination/deposit tracking.
- SequoRider `12c4a4a`: typed destination/slot deposit, route readback and client regression.

This evidence guide is committed separately from implementation.
