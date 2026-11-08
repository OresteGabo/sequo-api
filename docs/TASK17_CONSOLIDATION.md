# Task 17 — Consolidation and cooperative approval

## Contract and runtime evidence

The signed PDF `/Users/muhirwagabooreste/Downloads/contrat_mvp_sequo_signed-2.pdf`, page 3, defines the scope:

| Reference | Scope | Implemented evidence |
| --- | --- | --- |
| 29 | Group multiple products before customer delivery. | Persisted order → two merchant sub-orders → collection missions → individual SEQUO receipts → one sealed package and final mission. |
| 37 | Group products from nearby merchants or cooperatives. | Operator-assisted grouping uses the actual order's merchant/package data; an optional cooperative must already be approved and contain every merchant. No geographical threshold was invented. |
| 38 | SEQUO validates cooperatives or merchant grouping. | Staff authorization, recorded grouping approver, cooperative decision reason/reviewer/time/history, admin review and operations UI. |

Audit rows 29/37/38 helped locate existing manifests, transitions and the standalone CooperativeMarket domain. The live cooperative HTTP routes actually use PlatformCommerceApi persistence. Those existing routes previously lacked a staff-role check for review. Existing consolidation also treated courier pickup as warehouse receipt, lacked actor history/serialization, and allowed arbitrary caller-supplied manifest contents. These concrete runtime gaps were fixed.

## Result and boundaries

- Selected operator surface: existing authenticated **sequo-admin → Deliveries**, with a small grouping/cooperative panel. Staff review an existing cooperative or validate grouping without a cooperative, enter the paid order ID, confirm each physical receipt, seal one package, dispatch, refresh and inspect history. Existing cooperative create/add-member endpoints prepare proposals; no second administration system was added.
- Grouping uses persisted authenticated ownership, paid active order state and all merchants' PACKED_READY package counts. Customer/package fields in older create payloads are accepted but ignored. A changed cooperative on retry is rejected. A new proposed manifest ID for an already-grouped order returns its original manifest. Reusing an ID across different orders cannot overwrite a manifest.
- Readiness dispatch waits for a manifest on new multi-merchant home orders. Group approval and readiness dispatch lock the same order, so the scheduler cannot send a new grouped order directly to the customer while approval is pending. Existing already-dispatched direct legs and relay/pickup routes retain their supported flow; in-flight missions are not silently rerouted.
- Vendor keeps its existing scoped queue/detail and accept → preparation → mark-packed actions. Each merchant sees its own lines, quantities and package counts. Legacy manifest-ready replay now returns only that merchant's package acknowledgement, without customer ID, other merchants' packages or internal history.
- Rider accepts and proves collection through existing actions. Pickup history persists in a new transaction after the mission commits; pickup alone leaves the package awaiting SEQUO receipt. Only authorized operations staff can confirm physical receipt after the assigned pickup. Each receipt stores its actor/reference and finishes that collection leg as HANDED_TO_SEQUO. Rider shows collection → SEQUO receipt and final SEQUO pickup → customer handoff separately, with no customer contact on warehouse collection legs.
- Final dispatch requires every receipt plus a sealed package. An order lock, manifest lock and existing deterministic final delivery code serialize repeat/concurrent actions. One final mission uses the paid checkout's customer fee, mode/ETA and subscriber metadata. Intake completion cannot complete the customer order; final customer handoff uses the ordinary assigned Rider/PIN flow. Merchant payout/return-window completion still waits for customer delivery.
- History records grouping approval, courier pickup actors, each receipt, sealing and final dispatch. Server time is authoritative. New responses include the just-recorded history; refreshed reads retain it. Customer reads omit internal actor history. Cooperative writes/review are ADMIN/SUPER_ADMIN; consolidation operations retain existing SUPPORT_AGENT/ADMIN/SUPER_ADMIN authorization. Customer, merchant and Rider tokens cannot approve grouping or confirm warehouse custody.
- Cooperative review accepts explicit APPROVED/REJECTED/SUSPENDED outcomes and a reason. Replaying the same decision does not add duplicate history. Adding a member resets current approval to PENDING_REVIEW, preserves old review history, and requires a new review before a new cooperative grouping. Existing grouping approvals remain their own recorded operator decision.
- Admin errors do not mark local data complete. Saved-action/readback failures explicitly request refresh. Mutation JSON survives auth refresh; expired/invalid sessions clear the mounted operations UI. Existing English admin copy and Rider English/French localization are reused.

## Acceptance results

| Check | Result |
| --- | --- |
| Two-merchant order | PASS locally: real persisted CUSTOMER, two MERCHANT_OWNER memberships, ADMIN and two COURIER accounts are verified through `/api/auth/me`. Normal signed fixture payment webhook creates the paid order. Merchant quantities are 2 and 3; prepared package counts are 1 and 2. |
| Both merchant contributions and one final dispatch | PASS: two distinct collection missions and two physical receipt entries become one sealed final package and one customer mission. Final PIN handoff marks the same order DELIVERED. |
| Reject dispatch before prerequisites | PASS: preparation/approval pending, missing pickup, missing receipts, blanket collection transition and missing seal all fail. Customer/Rider/merchant approval or receipt attempts are denied. |
| Merchant isolation | PASS: other-merchant detail/accept and manifest-ready attempts are denied. Own ready replay returns one scoped package. Forged customer/package fields do not alter the manifest. |
| Repeated/concurrent actions | PASS: pickup/receipt repeats retain state; review repeat retains one decision; minimal UI create retry returns the canonical manifest; simultaneous final dispatch calls return the same mission with original/replay flags. `{}` dispatch retry is accepted. |
| Actor history and refresh | PASS: seven grouping/pickup/receipt/seal/dispatch events persist with actual actor IDs. Browser reload shows stored history; expired-session refresh removes it. |
| PostgreSQL persistence | PASS with Flyway V1–V45, JPA schema validation, real foreign keys and row locks. After API shutdown, SQL readback shows two HANDED_TO_SEQUO collection missions, one DELIVERED_TO_CUSTOMER mission, one DELIVERED GroupedSequo order and seven manifest history events. |
| Deployed staging/physical devices | UNVERIFIED: no authorized deployed staging session or physical device was supplied. Local HTTP/browser/host/compiler fixtures are not a deployed provider exercise. Exact steps follow below. |

## Commands and results (2026-11-08)

From `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api`:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test --tests '*CustomerCartCheckoutHttpTest' --tests '*Consolidation*Test' --tests '*Cooperative*Test' --tests '*DeliveryReadinessDispatchServiceTest' --tests '*DeliveryMission*Test' --tests '*OrderProcessingTest' --tests '*AdminWeb*Test'
```

PASS: **73 tests**, zero failures/errors/skips. Includes the new paid two-merchant HTTP journey, existing checkout/home/relay/mode paths, legacy manifest tests, manifest-ID collision regression and receipt-prerequisite regression. A stale test fixture enum value was corrected to the existing Standard fulfillment priority; production records were not rewritten. Log: `/tmp/sequo-task17-regressions.log`.

```sh
SEQUO_RELAY_JOURNEY_TEST_URL=jdbc:postgresql://127.0.0.1:55434/sequo_task14_consolidation JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test --tests '*CustomerCartCheckoutHttpTest.approvedTwoMerchantGroupingRequiresSequoReceiptsAndCreatesOneFinalDispatch'
```

PASS: **1 complete PostgreSQL HTTP journey**, migration V45/schema validation and concurrency included. The inherited test guard accepts only disposable loopback `sequo_task14_*` databases under `sequo_fixture`. The task-owned PostgreSQL 15 cluster was explicitly started on port 55434 and stopped afterward; other local services were untouched. Logs/evidence: `/tmp/sequo-task17-postgres.log`, `/tmp/sequo-task17-postgres-results.xml`, `/tmp/sequo-task17-postgres-evidence.txt`.

From `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider`:

```sh
./gradlew --offline :shared:testAndroidHostTest :shared:compileKotlinIosSimulatorArm64
```

PASS: **8 host tests**, Android main compilation and iOS simulator compilation. Focused client regression distinguishes warehouse intake, receipt completion, final SEQUO pickup, one final package and customer PIN proof. Log: `/tmp/sequo-task17-rider.log`.

From `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor`:

```sh
./gradlew --offline :shared:testAndroidHostTest --tests '*VendorFulfillmentClientTest'
```

PASS: **2 existing fulfillment client tests** covering bearer/merchant scope, own quantities/options/preparation target, package-count mutation and denied errors. No Vendor source changes were needed. Log: `/tmp/sequo-task17-vendor-fulfillment.log`.

From `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin`:

```sh
npm run type-check
VITE_SEQUO_API_BASE_URL='' npm run build-only
node --experimental-strip-types --test tests/*.test.mjs
NODE_PATH=/opt/homebrew/lib/node_modules/@google/gemini-cli/node_modules node scripts/consolidation-browser-check.cjs
```

PASS: type check, production build, **3 Node tests**, browser regression. Browser covers auth-refresh JSON preservation, failed grouping/stable retry ID, server-returned canonical ID, both receipts, early-dispatch guard, saved-action/readback failure, seal/final dispatch/repeat, reload history and session-expiry clearing. It serves the built app against loopback HTTP fixtures, blocks external requests and uses installed puppeteer-core/Chrome (CHROME_PATH override supported). Screenshot `/tmp/sequo-task17-admin.png` was visually inspected. All changed repositories pass `git diff --check`.

## Migration and operator decisions

1. Apply **V45__record_consolidation_approval_and_receipt.sql** before deploying the new API/Rider. It adds nullable approval/history fields and extends the mission status constraint; it does not backfill approval, rewrite old custody/payment/fee history, or create fake actor events. Upgrade Rider before using new warehouse receipt states so old enum decoders do not encounter an unknown status.
2. Review active legacy manifests whose approver/history is unavailable. They remain readable and retain their known state; the UI labels missing approval evidence. Existing live direct missions continue as direct deliveries. This prompt does not automatically convert already-dispatched orders into consolidated orders; those require an explicit operational recovery decision.
3. Before live grouping, SEQUO must choose the actual consolidation receiving location and the merchants it can physically serve. This MVP uses an operator-entered receipt reference and logical SEQUO location, not invented coordinates or a proximity radius. New multi-merchant home orders now require that operational approval before collection dispatch.
4. No new consolidation tariff, collection-leg payout, premium or ETA window was invented. Existing optional final courier-fee estimate input remains; the admin minimal dispatch action retains its existing zero default estimate. Confirm approved courier compensation/configuration before a live independent payout exercise. Customer totals are not recharged or changed by grouping. Existing Task 16 production zone/ETA decisions remain in the easy-to-find **uncommitted** root note; this task does not resolve or commit that note.
5. Cancellation/failed physical collection still uses existing problem/support operations; this task does not automatically move parcels, refund payments or substitute a receiving site.

## Exact unverified staging/device exercise

1. Apply V45 to an authorized isolated staging environment. Use existing debug base-URL configuration for Vendor/Rider and VITE_SEQUO_API_BASE_URL for admin. Provision authorized customer, two merchants with separate owner memberships, admin and linked courier accounts. Use authorized test payment evidence; never enable a payment simulator or fixture credentials in production.
2. Through the customer basket/checkout create one order containing each merchant's real product. Confirm paid acceptance via the existing approved test evidence. In each Vendor session, inspect only its own quantities, accept, prepare and mark packed with actual package count. Try the other merchant's ID/body scope: expect denial.
3. If using a cooperative, POST `/api/admin/cooperatives` with code/name/city/ownerMerchantId, add the second merchant through `/{id}/members`, then review it in admin Deliveries with a reason. Try customer/merchant/Rider review tokens: expect 403. Reject then approve, refresh, inspect reviewer/history. Add a member later and verify it returns to PENDING_REVIEW.
4. In admin Deliveries validate the paid order grouping. A cooperative is optional; an explicit staff-approved grouping without one is supported. Run `POST /api/delivery/missions/dispatch-ready?limit=200`; before approval it must skip that order, afterward it must create one SEQUO_CONSOLIDATION collection mission per merchant. Record the manifest/order/mission IDs. Early `POST /api/consolidations/{id}/dispatch-final-package` must return 400.
5. Assign each collection mission using the existing assign/assign-policy route, offer it, accept in the assigned Rider and submit actual pickup proof. Confirm the manifest is still awaiting that receipt and the customer destination/contact is hidden on collection legs. In admin enter the physical receipt reference and confirm each merchant package only after arrival at the approved receiving site. Duplicate receipt must be harmless; a receipt before pickup must fail.
6. Confirm all package receipts, seal one labelled final package, then dispatch. Send two simultaneous final-dispatch requests: both must read back the same final mission, with no second mission. Refresh/reload the admin and compare actor IDs/times. Assign/offer the final mission; Rider must show SEQUO pickup, one package and the authorized customer destination. Complete pickup and customer PIN handoff through existing actions; verify the customer order becomes DELIVERED only now.
7. Restart staging API, refresh each role's screens, confirm history and final mission remain. Expire the admin session and refresh: history must disappear until sign-in. Exercise physical handoffs, offline/reconnect and app session restoration on devices. These deployed/device steps remain unverified locally.

## Safe dependency for the next prompt

Operator-assisted grouping now connects real paid merchant preparation to separate collection receipts and one replay-safe final dispatch, with scoped merchant data, recorded SEQUO approval/actors, inherited paid delivery metadata, and customer completion only after final handoff. Existing cooperative/manifests/sub-orders/routes are reused. Real receiving-site deployment and any additional courier tariff/payout policy still need SEQUO's operational decision.

## Changed files

Under `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api`:

- `src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/ConsolidationController.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/ConsolidationMissionEventListener.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/ConsolidationPersistence.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/DeliveryFulfillmentWorkflow.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/DeliveryMissionPersistence.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/DeliveryReadinessDispatch.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/SequoConsolidation.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/order/OrderFulfillmentPersistence.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/platform/PlatformCommerceApi.kt`
- `src/main/resources/db/migration/V45__record_consolidation_approval_and_receipt.sql`
- `src/test/kotlin/dev/orestegabo/sequo_api/domain/delivery/ConsolidationMissionEventListenerTest.kt`
- `src/test/kotlin/dev/orestegabo/sequo_api/domain/delivery/ConsolidationPersistenceServiceTest.kt`
- `src/test/kotlin/dev/orestegabo/sequo_api/domain/order/CustomerCartCheckoutHttpTest.kt`
- `docs/POSTMAN_ENDPOINTS.md`
- `docs/TASK17_CONSOLIDATION.md`

Under `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin`:

- `src/App.vue`
- `src/ConsolidationPanel.vue`
- `scripts/consolidation-browser-check.cjs`

Under `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider`:

- `shared/src/commonMain/kotlin/dev/orestegabo/sequorider/core/missions/RiderMissionApiClient.kt`
- `shared/src/commonMain/kotlin/dev/orestegabo/sequorider/feature/settings/AppText.kt`
- `shared/src/commonMain/kotlin/dev/orestegabo/sequorider/model/RiderModels.kt`
- `shared/src/commonMain/kotlin/dev/orestegabo/sequorider/ui/history/HistoryScreen.kt`
- `shared/src/commonMain/kotlin/dev/orestegabo/sequorider/ui/missions/MissionsScreen.kt`
- `shared/src/commonMain/kotlin/dev/orestegabo/sequorider/ui/route/ActiveRouteScreen.kt`
- `shared/src/androidHostTest/kotlin/dev/orestegabo/sequorider/RiderMissionClientTest.kt`

SequoVendor required no changes. Unrelated customer/Vendor edits, audit/output/tmp files and the explicitly uncommitted Task 16 decision note were preserved and excluded from commits.
