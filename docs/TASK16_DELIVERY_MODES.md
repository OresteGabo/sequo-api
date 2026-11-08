# Task 16 — Delivery modes, workforce and dispatch

## Scope and evidence

Read the signed PDF pages 2–4: references 3/4 describe classic, express, deferred delivery and salaried/independent couriers; clauses 31–35 distinguish modes, SEQUO subscriber priority, independent fallback and server distance pricing. Clause 41 leaves subscription/reduction/SEQUO funding settings configurable or for later agreement. Audit rows 31–35/41 located existing assignment and pricing policies. Existing runtime checkout selected a single route; `DeliveryAssignmentPolicy` existed but was not wired to persisted fleet assignment. No approved zone/ETA values were present.

The user requested configurable implementation and local verification without blocking on business terminology, plus a separate easy-to-find uncommitted decision note. `TASK16-SETTINGS-TO-DECIDE.md` at the repository root is intentionally not committed. Production mode promises are not inferred from test numbers.

## Implemented behavior

- Existing cart quote now returns `availableDeliveryModes` and selected `deliveryOption`. EXPRESS maps to EXPRESS missions, CLASSIC to STANDARD, DEFERRED to PROGRAMMED. Each explicit choice includes its ETA range in minutes, zone ID and the existing server-computed delivery fee. The same selected object survives pending payment, webhook reconciliation, dispatch, Rider/customer tracking and authenticated admin reads.
- `deliveryMode` is optional on `GET /api/cart/quote` and cart checkout. Explicit choices require an enabled service, configured positive ETA window, configured zone and positive coverage radius around the existing trusted dispatch origin. The quote uses owned address/relay coordinates; client distance/totals/priority flags do not override pricing or entitlement. Disabled, changed-ETA and out-of-zone explicit choices are rejected or require quote review before creating a checkout.
- When a configured express choice exists, it is the default. With no new configuration, the existing unspecified-mode checkout remains supported and reports no configured ETA; the new explicit choices are unavailable. This preserves existing clients while preventing invented new service promises.
- Current fees remain the existing 400 CFA through 5 km plus 100 CFA per extra rounded-up kilometre. No mode surcharge, subscription discount, rider tariff or SEQUO funding amount was invented. Mode rows currently use that common distance price; any later premium/discount needs an approved pricing change.
- Active current subscriptions under active plans are captured server-side for priority. The client cannot assert priority. This task does not execute subscription payment collection or add discounts; subscription lifecycle/payment rules remain their existing separate workflow. The local priority fixture explicitly provisions an active current subscription.
- Staff use `POST /api/delivery/missions/{id}/assign-policy`, also exposed as “Assign available courier” in admin Deliveries. It reuses `DeliveryAssignmentPolicy`, reads persisted active COURIER accounts/workforce/vehicle/availability, excludes paused or busy couriers and locks candidate rows in stable order. Conservative policy capacity is one outstanding job; a deposited relay parcel no longer occupies courier work capacity. Unknown workforce/vehicle or missing user linkage is excluded from automatic selection; explicit supported legacy staff assignment remains available.
- Subscriber jobs prefer available salaried SEQUO capacity. Existing policy handles standard/programmed/express channels and independent fallback. No-capacity returns 409 and leaves the mission unassigned. Repeat calls retain an existing assignment. Only ADMIN/SUPER_ADMIN can invoke policy assignment; customer/Rider tokens are denied. Dispatch remains staff-triggered, not silently automated by checkout.
- Workforce/reason and original freelancer fee estimate are persisted. Salaried assignment does not create a freelance payable; independent assignment uses the existing stored estimate. Manual reassignment refreshes workforce metadata and restores the original independent estimate instead of retaining a salaried zero or stale label. No financial history is rewritten by reads/migration.
- Customer mode labels use existing English/French localization. Rider displays the persisted mode, ETA range and actual assigned workforce. Admin displays the same ETA/price/zone/priority fields and readable capacity/session errors, with loading guards and refresh. Authentication failure clears the admin data.

## Effective configuration

New explicit modes default disabled, ETA/zone/radius unset. Configure approved values through the existing application environment:

```text
DELIVERY_ZONE_ID
DELIVERY_MAX_DISTANCE_KM
DELIVERY_EXPRESS_ENABLED
DELIVERY_EXPRESS_ETA_MIN_MINUTES
DELIVERY_EXPRESS_ETA_MAX_MINUTES
DELIVERY_CLASSIC_ENABLED
DELIVERY_CLASSIC_ETA_MIN_MINUTES
DELIVERY_CLASSIC_ETA_MAX_MINUTES
DELIVERY_DEFERRED_ENABLED
DELIVERY_DEFERRED_ETA_MIN_MINUTES
DELIVERY_DEFERRED_ETA_MAX_MINUTES
```

Also configure the existing `sequo.cart.straight-line-dispatch-origin` / its existing environment setting. ETA windows are configured service estimates captured at checkout, not live GPS predictions or a booked delivery appointment. No nonexistent road-distance/provider integration is claimed. Approval must clarify whether windows include preparation before production enablement.

V44 adds nullable delivery-option/assignment/fee-estimate fields plus a false-default subscriber flag to missions. Existing records are not inferred into new modes or silently reassigned.

## Acceptance evidence

| Check | Local result |
|---|---|
| Express/classic/deferred quote and fulfillment | Three paid fixture orders go through normal signed webhook acceptance, merchant preparation, dispatch, assignment, Rider acceptance/pickup/handoff and customer DELIVERED readback. Mode, ETA and price match Rider/admin/customer snapshots. |
| Subscriber priority | With salaried and independent accounts both available, an active current subscriber's express order selects the salaried SEQUO courier. |
| Fallback | A paused SEQUO courier is excluded; a subscribed deferred order falls back to an independent courier and completes normally. |
| Stale/unavailable choice | Disabling classic or changing its ETA after quote yields checkout 409 with no pending checkout; an out-of-zone explicit choice fails. Client-supplied priority/distance/total do not override the server. |
| Representative distances | Synthetic local origin 0,0, radius 10 km: longitude 0.01 gives roughly 1.1 km; 0.0449 is just below 5 km/400 CFA; 0.0451 just above/500 CFA; 0.2 is outside configured coverage. This is LOCAL_FIXTURE_ONLY, not a real service zone. |
| Capacity and retry | Two concurrent mission assignments compete for one eligible courier: one success, one 409, one persisted reservation. Repeat assignment retains the chosen courier. |
| Workforce consistency | Staff salary-to-independent reassignment changes the stored type and restores the original 400 CFA fixture independent estimate; no new tariff is introduced. |
| Screens | Customer/Rider Android+iOS compilation, Rider DTO/ETA/workforce loopback regression, admin rendering regression, type check and production build pass. Built admin browser fixture verifies assignment action, failed capacity and expired-session clearing. Physical device/staging remains unverified. |

## Commands and results (2026-11-08)

From sequo-api:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test --tests '*CustomerCartCheckoutHttpTest' --tests '*CustomerCheckoutRestartTest' --tests '*DeliveryAssignment*Test' --tests '*DeliveryMission*Test' --tests '*DeliveryReadinessDispatchServiceTest' --tests '*OrderProcessingTest' --tests '*AdminWeb*Test' --tests '*Relay*Test'
```

90 targeted API tests passed; the opt-in migration test is skipped by this normal H2 command. The HTTP fixture resets the existing in-memory test request budget before each independent test, preserving the production 300/minute limit.

Four new tests also passed on PostgreSQL 15 with all Flyway migrations through V44 and JPA schema validation, real foreign keys and row locks:

```sh
SEQUO_RELAY_JOURNEY_TEST_URL=jdbc:postgresql://127.0.0.1:55434/sequo_task14_modes JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test --tests '*CustomerCartCheckoutHttpTest.configuredModesPersistThroughFulfillmentWithSubscriberPriorityAndIndependentFallback' --tests '*CustomerCartCheckoutHttpTest.staleOrOutOfZoneDeliveryChoicesCannotBypassAuthoritativeQuote' --tests '*CustomerCartCheckoutHttpTest.policyAssignmentCannotReserveOneCourierForTwoConcurrentMissions' --tests '*CustomerCartCheckoutHttpTest.manualReassignmentRefreshesWorkforceAndPreservesIndependentFeeEstimate'
```

The inherited guard restricts this to explicitly named disposable `sequo_task14_*` loopback fixture databases/user. See Task 14/15 for initialization; specify the temporary cluster's loopback port/socket explicitly. No existing local production service was changed or stopped. The task-owned cluster is stopped after verification.

From Sequo:

```sh
./gradlew --offline :shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64
```

Passed. The customer host suite remains blocked by the pre-existing `calculateDeliver yPricing` edit at `DeliveryPricingTest.kt:59`; it is preserved, not staged, and not counted as passing.

From SequoRider:

```sh
./gradlew --offline :shared:testAndroidHostTest :shared:compileKotlinIosSimulatorArm64
```

Seven host tests and iOS simulator compilation passed; the host task compiles Android main sources as well. The new HTTP client regression reads actual DEFERRED mode/ETA and Freelancer workforce fields.

From sequo-admin:

```sh
node --experimental-strip-types --test tests/deliveryReads.test.mjs
npm run type-check
npm run build-only
VITE_SEQUO_API_BASE_URL='' npm run build-only
NODE_PATH=/opt/homebrew/lib/node_modules/@google/gemini-cli/node_modules node scripts/delivery-mode-browser-check.cjs
```

Three Node tests, type check and both production/same-origin fixture builds passed. The browser check requires installed `puppeteer-core` through NODE_PATH and Chrome (`CHROME_PATH` override supported). It loads the built app against loopback mock auth/API; external requests are aborted. It is not a deployed staging test.

## Exact unverified staging/device exercise

1. Apply V44 after V43. Keep new explicit modes disabled until SEQUO approves its real origin/zone/radius and arrival estimates. Use the separate uncommitted glossary/decision note for that discussion. For isolated fixtures only, use LOCAL_FIXTURE_ONLY, origin 0,0, radius 10 km, and windows EXPRESS 30–60 minutes, CLASSIC 120–240, DEFERRED 1440–2880. These numbers are deliberately not production defaults.
2. Configure debug Sequo and SequoRider through their existing NetworkConfig base URLs and admin through VITE_SEQUO_API_BASE_URL, all pointing at the same isolated API. Provision real authorized customer, merchant, staff and two courier accounts; link courier user IDs, ACTIVE status, MOTO vehicle, SEQUO_SALARIED or FREELANCER workforce. Confirm pause/availability states. Provision an authorized active current subscriber fixture under an active plan.
3. For each enabled choice, add real products, select an owned destination and the mode in customer checkout, review ETA/price and submit. Use normal approved test payment evidence, prepare/pack with the merchant and dispatch through the existing staff route. In admin Deliveries enter the mission ID and select Assign available courier, then offer the mission through the existing operational action. The assigned Rider accepts, picks up with proof and completes the actual handoff. Refresh customer/Rider/admin and compare the same order's mode/ETA/price/workforce.
4. With both workforce types available, verify subscriber SEQUO priority. Pause the salaried account, repeat and verify independent fallback. Try customer/Rider tokens against assign-policy: expect 403. Create two ready missions with one eligible courier and submit policy assignment concurrently: one must remain unassigned with 409. Review stored freelancer estimates before any actual payout/provider exercise.
5. Review a quote, disable that mode or change its ETA in approved staging configuration and restart the API, then submit the stale quote: expect 409, no silently substituted choice. Repeat outside the configured radius. Confirm existing unspecified-mode records remain readable and the legacy checkout remains available without invented ETA values.
6. Exercise physical apps, offline/reconnect and deployed restart/provider behavior. Those checks remain unverified because no deployed staging/device access was provided. No production ETA, premium or subscription discount approval is implied.

## Safe dependency for the next prompt

Explicit configured delivery choices with server-owned price/coverage, persisted ETA/mode snapshots, current-subscription priority flag, an authenticated policy assignment action, persisted workforce/fee-estimate metadata, independent fallback and serialized single-job capacity. Existing order, payment, merchant, relay and legacy-read contracts remain supported. Subscription payment collection, live road/GPS ETA and any new tariff require their own approved workflow.

## Changed files

sequo-api:

- `docs/POSTMAN_ENDPOINTS.md`
- `docs/TASK16_DELIVERY_MODES.md`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/admin/AdminWebSurface.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/commerce/CartDeliveryQuote.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/DeliveryMissionPersistence.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/DeliveryOptions.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/DeliveryReadinessDispatch.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/PersistedDeliveryAssignment.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/order/CustomerCartCheckout.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/order/OrderController.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/order/OrderProcessing.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/order/PendingPaymentCheckout.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/settlement/SettlementPersistence.kt`
- `src/main/resources/application.properties`
- `src/main/resources/db/migration/V44__persist_delivery_options_and_assignments.sql`
- `src/test/kotlin/dev/orestegabo/sequo_api/domain/order/CustomerCartCheckoutHttpTest.kt`

Sequo:

- `shared/src/commonMain/composeResources/values-fr/strings.xml`
- `shared/src/commonMain/composeResources/values/strings.xml`
- `shared/src/commonMain/kotlin/dev/orestegabo/sequo/core/catalog/CartApiClient.kt`
- `shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/app/SequoShell.kt`
- `shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/basket/BasketContent.kt`
- `shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/orders/OrdersContent.kt`

SequoRider:

- `shared/src/androidHostTest/kotlin/dev/orestegabo/sequorider/RiderMissionClientTest.kt`
- `shared/src/commonMain/kotlin/dev/orestegabo/sequorider/core/missions/RiderMissionApiClient.kt`
- `shared/src/commonMain/kotlin/dev/orestegabo/sequorider/feature/settings/AppText.kt`
- `shared/src/commonMain/kotlin/dev/orestegabo/sequorider/model/RiderModels.kt`
- `shared/src/commonMain/kotlin/dev/orestegabo/sequorider/ui/missions/MissionsScreen.kt`
- `shared/src/commonMain/kotlin/dev/orestegabo/sequorider/ui/route/ActiveRouteScreen.kt`

sequo-admin:

- `scripts/delivery-mode-browser-check.cjs`
- `src/App.vue`
- `src/adminReads.ts`
- `tests/deliveryReads.test.mjs`

## Implementation commits

- sequo-api `c09ce45`: configured mode contract, snapshots, workforce policy assignment and server regressions.
- Sequo `eb01d38`: customer mode choice/ETA/price and order readback.
- SequoRider `5cbb798`: actual mode/ETA/workforce display and client regression.
- sequo-admin `4fc84cd`: consistent delivery reads, authorized policy assignment and browser regression.

The technical report is committed separately. The root business-settings note remains uncommitted at the user's request.
