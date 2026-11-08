# Task 22 — Commissions, rider pay and SEQUO shortfall

## Source and starting point

Read the signed source PDF at `/Users/muhirwagabooreste/Downloads/contrat_mvp_sequo_signed-2.pdf`: clauses 22 (page 3), 41 (page 4), 67–70 (page 5), 125 (page 12) and 133 (page 13). The corresponding audit rows were used to locate code; the PDF controls scope. Current diffs and normal commission, quote/order, delivery, settlement, Rider and admin entry points were inspected first. Baseline: 32 existing commission, settlement, payout-visibility and assignment tests passed before changes.

The existing merchant percentage policy already implements the proposed 15% default, merchant overrides and frozen paid sub-order amounts. The gap was live rider accrual: completed deliveries posted shortfall expense but no CourierPayable credit. Rider UI also reconstructed an invented current-period total from mission estimates, called a cumulative field the last payout, and could retain stale finance data after errors. Admin reads already had useful amounts but hid them in the adapter.

## Selected rule and acceptance evidence

The selected rule remains the existing merchant percentage policy: 1500 basis points by default, merchant override taking priority, currently accepted range 500–1500 basis points. Commission uses the existing integer HALF_UP rounding. A separate category or price-band schedule is unnecessary for this selected rule; none is invented. Changing a current override does not rewrite a paid sub-order or its eventual settlement.

| Check / contract | Verified outcome |
| --- | --- |
| 22, 67–70, 133: default and override | Local paid product subtotal4000 CFA: default15% gives commission600 and merchant net3400; override10% gives commission400 and merchant net3600. Quote product total, paid order lines, merchant sub-order, accrual and ledger agree. A later override change leaves the original paid snapshot unchanged. Unauthorized merchant edits are denied. |
| 70: visible/calculable breakdown | Product/options/service/delivery amounts remain authoritative server values. Commission is deducted from the merchant's product subtotal, not silently added as a customer surcharge. Current platform margin/service amounts are0; no new tariff is created. Admin shows recorded accrual net, commission, margin, source and holds, plus the exact frozen rate/subtotal lookup. Its current Merchant list now resolves the effective override instead of displaying the stale legacy rate field. |
| 41, 125: independent mission | A labelled local20% subscription fixture gives customer delivery320, original independent estimate/payable400, SEQUO shortfall80. After paid order, preparation, authorized pickup and PIN handoff, ledger posts CourierPayable Credit400 and SequoDeliveryShortfallExpense Debit80 once. Rider summary/history and admin ledger agree. No transfer is executed. |
| 41: salaried mission | Paid salaried mission has independent payable0 and shortfall0; no CourierPayable or per-order shortfall ledger entry is created. Its merchant10% commission snapshot still settles correctly. |
| Multi-leg order | Two independent completed legs each400 against one paid customer delivery fee400 produce total payable800 and total shortfall400. The customer contribution is allocated once, including concurrent duplicate posting. |
| Replay and actor scope | Concurrent/repeated handoff and webhook replay preserve one posting and each deterministic ledger identity. Customer/merchant/other rider cannot read another rider's finances or admin ledger. Missing sessions fail closed. Linked rider identity comes from authenticated user_id, never a guessed courier primary key. |
| Historical workforce and netting | Explicit freelance snapshot remains payable after the current profile changes to salaried. Ambiguous legacy credits require review. Corrections, holds/releases and unverified payout debits are netted; correction of a payout reserve does not increase earnings. Company expense is never rider earnings. |
| Legacy policy | A completed old leg with unknown settlement-policy version blocks fresh allocation on that old order. It produces REVIEW_REQUIRED and no new money rows, preventing reuse of an unknown earlier customer contribution. |

The subscription/rate/coordinate/identity values in automated journeys are LOCAL_FIXTURE_ONLY. The user's Task21 clarification confirms that production subscription/referral values are not approved; their production gates remain inactive. Default commission15% is the existing signed-PDF proposed rule. Real customer, rider or provider money was not used.

## Implementation and runtime entry points

`DeliveryMissionService` publishes a synchronous settlement-ready event only after an authorized persisted home completion, relay deposit/release, or SEQUO collection receipt. `DeliverySettlementPostingService` reads and locks the actual mission, verifies paid active order state and workforce, and accrues inside the same transaction. It never accepts an amount or workforce from the rider body. The old order-lifecycle-only shortfall write is removed so there is one financial posting path.

A `delivery_settlements` record is keyed by mission ID and freezes order, courier, workforce, allocated customer contribution, courier payable, shortfall, status and time. A customer row lock serializes independent legs without spending the paid order's delivery fee more than once. Independent payable uses the server-recorded mission fee. Salaried work does not use the independent fee calculation. Database checks enforce payable = allocated contribution + shortfall. Ledger entries retain source/amount/direction; existing financial rows are not rewritten.

The existing routes remain:

- Rider: `GET /api/rider/payouts/summary` and `/history`, COURIER role and linked authenticated owner only, no-store responses.
- Merchant: `GET /api/settlements/merchant-payouts?merchantId=...`, existing merchant membership or admin scope.
- Admin: `GET /api/settlements/ledger?sourceType=DeliveryMission|OrderItem&sourceId=...`; frozen merchant rate through `GET /api/merchant/sub-orders/{id}`.
- Commission configuration: existing admin-only GET/PUT/DELETE `/api/commissions/merchant-overrides/{merchantId}`. The prior effective-parameter inventory continues to expose the default and change procedures.

Rider summary adds authoritative `earnedTotalCfa`, `settledMissionCount`, `unverifiedTransferCfa`, `reviewRequiredCfa`, `paidCfa`, `processingCfa`, and `transferStatus`. `availableBalanceCfa` is remaining recorded liability, not cash in a wallet. Earned total is signed courier payable plus related corrections; holds and unverified transfer reserves reduce availability. An unexplained Payout debit does not prove payment. Until provider evidence is integrated, paid/processing totals remain0 and transferStatus is NOT_INTEGRATED. Old `paidOrProcessingCfa` is not a last payout.

History adds workforce snapshot and REVIEW_REQUIRED. Raw amounts/directions remain visible, signed effects are displayed without client summing, and SEQUO expense is excluded from rider income. The Rider screen uses loading/empty/error states, hides finance data on refresh or denied/offline reads, and propagates coroutine cancellation. Missing optional legacy totals remain unavailable. Explicit demo mode is retained. Current salary classification does not hide proven historical independent earnings.

Admin FinanceLedgerPanel uses existing authenticated routes. It separates estimated/recorded mission amounts, posted ledger accruals and verified provider payments. A missing legacy sub-order snapshot does not hide a valid ledger, and a failed read does not become a zero balance. No new financial mutation or provider action is added.

## Migration and operator decisions

V50 adds nullable ledger workforce snapshots, a settlement-policy version on missions, and the new posting table. It does not backfill earnings, alter prior money rows or guess historical employment.

- New server-created missions use settlement policy1. Existing database rows retain NULL. Old active/completed orders require financial reconciliation before opting into a new allocation/posting policy, including prior legs, customer contribution already used, workforce and previously recorded payouts/expenses. Do not simply set the version on an old order without review.
- Missing workforce, non-active paid-order state, occupied/legacy financial sources, or unknown prior-leg allocation creates REVIEW_REQUIRED, with no new money entries. Repeated calls return its canonical record; a later correction needs an explicitly reviewed migration/append-only accounting operation.
- Already-completed legacy missions do not gain a fresh accrual merely by replaying a handoff. Existing income/expense remains unchanged. Identify owed but unposted legacy pay through an authorized reconciliation before any grant or transfer.
- Old couriers with `user_id` NULL need a verified account mapping before payout reads. Preserve their ledger and establish the ownership link through authorized operations; do not restore a primary-key guessing fallback.
- Existing merchant return/dispute holds and eligibility are shown as stored. This task does not invent automatic hold release or execute merchant/rider transfers; subsequent payout workflow work must preserve frozen amounts and holds.
- Provider-confirmed transfer records, actual outgoing payments and transfer retry/reconciliation remain Task26. Reconcile unverified historical transfer debits before sending money again.

Useful read-only reconciliation queries for an authorized staging/operations database: inspect `delivery_missions` with `settlement_policy_version is null`; `delivery_settlements where status='REVIEW_REQUIRED'`; and `couriers where user_id is null`. Join their existing source IDs to ledger and paid orders, without exporting customer contact or payment credentials. Decisions and any resulting correction must be reviewed, not auto-applied by these queries.

## Verification

Final focused verification passed as follows. Automated identities/providers/phone numbers are isolated fixtures; no production secret/database or real transfer was used.

## External reproduction — UNVERIFIED

1. On authorized staging, sign in separately as customer, merchant, rider and admin. Use a priced ordinary product. Read the15% default with `/api/commissions/merchant-overrides/{merchantId}`. For the second case, admin PUTs an approved staging10% override with a reason. Quote through `/api/cart/quote`, submit unchanged revision to `/api/orders/checkouts`, and reconcile only trusted staging payment evidence. Read the paid order and merchant sub-order snapshot.
2. Change the current override after payment; the existing sub-order commission/net must stay unchanged. Merchant POSTs `accept`, `start-preparation`, `mark-packed` under `/api/merchant/sub-orders/{id}/...` with its own merchantId/package count. Admin POSTs `/api/delivery/missions/dispatch-ready`, assigns the fixture courier with `/assign`, and offers the mission with `/offer`.
3. Independent rider accepts `/api/rider/missions/{id}/accept`, completes `/pickup/complete` with real handoff proof/stable key, and `/delivery/complete` with the actual valid staging PIN. Use the normal server PIN issuance path; do not use fixed fixture PINs outside disposable tests. Repeat/concurrently submit the same handoff; refresh the customer order, merchant payouts, own rider summary/history and admin ledger by mission/source item. Check one payable/shortfall posting and no provider transfer claim.
4. Repeat with a SEQUO salaried account and verify zero independent liability/expense. For an approved independent fee above the customer fee, verify SEQUO records exactly the difference. For multiple independent legs, inspect posting allocation totals against the single paid order delivery fee.
5. Refresh Rider Payouts and admin Payments/Commissions, then expire/deny the session and disconnect the API. Finance rows must clear or show unavailable, not fabricated zeros or local estimates. Try another rider token and merchant/customer tokens against admin ledger; access must be denied. For an old linked salary account with historical freelance snapshots, verify old earned amounts remain visible; unknown legacy ownership/policy must fail closed or enter review.

Actual remote staging accounts, device rendering and provider transfers were unavailable and remain UNVERIFIED. Do not infer a rider transfer deadline or provider success from accrual or a ledger debit. Rider historical planning is retained separately in PAYOUT_STRUCTURE.md and explicitly marked unimplemented/unapproved.

## Safe dependencies for the next task

Later work can depend on frozen merchant commission/net snapshots, actual paid-completion courier accrual, one-per-mission posting identity, one customer delivery contribution per order, workforce snapshots, net scoped Rider reads and admin ledger lookup. It must not assume a resolved legacy migration, approved production promotional values, released merchant holds, confirmed payment to a rider, automatic provider execution or an approved rider transfer SLA.

- API baseline: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test --tests '*MerchantCommission*Test' --tests '*Settlement*Test' --tests '*RiderPayoutVisibilityTest' --tests '*DeliveryAssignmentPolicyTest'` — PASS, 32 tests before changes.
- API compile: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline compileKotlin` — PASS; final test run also recompiles changed sources.
- API final: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test --tests '*MerchantCommission*Test' --tests '*Settlement*Test' --tests '*RiderPayoutVisibilityTest' --tests '*DeliveryAssignmentPolicyTest' --tests '*OrderFulfillmentPersistenceServiceTest' --tests '*OrderDeliveryLifecycleNotificationTest' --tests '*HandoffReplayIntegrityTest' --tests '*CustomerCartCheckoutHttpTest' --tests '*Consolidation*Test' --tests '*DeliveryBenefitsCheckoutHttpTest' --tests '*AdminWeb*Test'` — PASS, 89 tests. Includes default/override calculations, frozen paid snapshots versus live Merchant-list rate, scoped actual ledger, workforce, replay, multi-leg races, legacy review and signed correction/hold/transfer netting.
- API/PostgreSQL final: `SEQUO_SETTLEMENT_JOURNEY_TEST_URL=jdbc:postgresql://127.0.0.1:55434/sequo_task14_settlement_v50 JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test --tests '*SettlementJourneyHttpTest'` — PASS, 4 scenarios with Flyway through finalized V50 and Hibernate schema validation. The guarded datasource is a disposable task-owned cluster on loopback port55434, sequo_fixture/local trust; no production credentials or database. SQL checks verify zero invalid allocations and zero orders allocating more customer contribution than paid delivery fee.
- Rider: `./gradlew --offline :shared:testAndroidHostTest :shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64` — PASS, 11 host tests, including3 payout HTTP fixtures. Android and iOS simulator main compile. The last readable/signed-history layout also passed `./gradlew --offline :shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64`.
- Admin: `node --experimental-strip-types --test tests/*.test.mjs` — PASS, 13 tests; `npm run type-check` — PASS; `VITE_SEQUO_API_BASE_URL='' npm run build-only` — PASS.
- Admin browser: `NODE_PATH=/opt/homebrew/lib/node_modules/@google/gemini-cli/node_modules node scripts/finance-ledger-browser-check.cjs` — PASS: authenticated persisted finance reads; net/commission/margin; frozen15% versus later10% override;400 courier payable versus80 SEQUO expense; legacy ledger without snapshot; failed versus empty reads; reload and expired-session clearing. Mock HTTP only; server scope/amounts verified separately by the journeys. `/tmp/sequo-task22-admin-finance.png` was visually checked.
- `git diff --check` in sequo-api, SequoRider and sequo-admin — PASS.

Local logs: `/tmp/sequo-task22-{baseline,compile,api-tests,postgres,postgres-final,rider-checks,rider-layout-compile,admin-tests,admin-type,admin-build,admin-browser}.log`; final89-test XML under `/tmp/sequo-task22-h2-results/`. Temporary fixture artifacts are not committed. The task-owned PostgreSQL cluster was stopped after final verification. Initial regression iteration identified missing linked fixture owner records and an anonymous-session401/403 expectation; production checks were kept strict and fixtures corrected. Read-only review also found the transfer-correction edge, now covered. No checks used real outgoing payments.

## Changed files

sequo-api:

- [docs/TASK22_COMMISSIONS_RIDER_PAY.md](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/docs/TASK22_COMMISSIONS_RIDER_PAY.md)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/admin/AdminWebSurface.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/admin/AdminWebSurface.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/ConsolidationPersistence.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/ConsolidationPersistence.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/DeliveryMissionPersistence.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/DeliveryMissionPersistence.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/order/OrderFulfillmentPersistence.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/order/OrderFulfillmentPersistence.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/settlement/DeliverySettlementPosting.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/settlement/DeliverySettlementPosting.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/settlement/RiderPayoutController.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/settlement/RiderPayoutController.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/settlement/SettlementController.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/settlement/SettlementController.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/settlement/SettlementLedger.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/settlement/SettlementLedger.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/settlement/SettlementPersistence.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/settlement/SettlementPersistence.kt)
- [src/main/resources/db/migration/V50__completed_delivery_settlements.sql](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/resources/db/migration/V50__completed_delivery_settlements.sql)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/order/OrderDeliveryLifecycleNotificationTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/order/OrderDeliveryLifecycleNotificationTest.kt)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/order/OrderFulfillmentPersistenceServiceTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/order/OrderFulfillmentPersistenceServiceTest.kt)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/settlement/RiderPayoutVisibilityTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/settlement/RiderPayoutVisibilityTest.kt)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/settlement/SettlementJourneyHttpTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/settlement/SettlementJourneyHttpTest.kt)

SequoRider:

- [PAYOUT_STRUCTURE.md](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider/PAYOUT_STRUCTURE.md)
- [README.md](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider/README.md)
- [shared/src/androidHostTest/kotlin/dev/orestegabo/sequorider/RiderPayoutClientTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider/shared/src/androidHostTest/kotlin/dev/orestegabo/sequorider/RiderPayoutClientTest.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequorider/core/payout/DemoPayoutRepository.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider/shared/src/commonMain/kotlin/dev/orestegabo/sequorider/core/payout/DemoPayoutRepository.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequorider/core/payout/RiderPayoutApiClient.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider/shared/src/commonMain/kotlin/dev/orestegabo/sequorider/core/payout/RiderPayoutApiClient.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequorider/feature/settings/AppText.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider/shared/src/commonMain/kotlin/dev/orestegabo/sequorider/feature/settings/AppText.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequorider/model/RiderModels.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider/shared/src/commonMain/kotlin/dev/orestegabo/sequorider/model/RiderModels.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequorider/ui/app/SequoRiderShell.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider/shared/src/commonMain/kotlin/dev/orestegabo/sequorider/ui/app/SequoRiderShell.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequorider/ui/payout/PayoutScreen.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider/shared/src/commonMain/kotlin/dev/orestegabo/sequorider/ui/payout/PayoutScreen.kt)

sequo-admin:

- [scripts/finance-ledger-browser-check.cjs](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin/scripts/finance-ledger-browser-check.cjs)
- [src/App.vue](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin/src/App.vue)
- [src/FinanceLedgerPanel.vue](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin/src/FinanceLedgerPanel.vue)
- [src/adminReads.ts](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin/src/adminReads.ts)
- [src/financeReads.ts](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin/src/financeReads.ts)
- [tests/financeReads.test.mjs](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin/tests/financeReads.test.mjs)

Pre-existing output/audit/invoice files and the intentionally uncommitted `TASK16-SETTINGS-TO-DECIDE.md` remain untouched. No source from other repositories was changed in Task22.

## Focused commits

- API `d8f11f4` — completed-mission accrual, workforce persistence, safe legacy policy and single customer-fee allocation.
- API `9820f07` — scoped/net Rider finance, effective current merchant rates and actual settlement journeys.
- Admin `585e150` — posted-ledger and frozen-commission finance views.
- Rider `3fb0917` — authoritative recorded earnings, signed history and safe financial refresh/error states.
- Rider `c959f1b` — actual runtime remuneration documentation with historical proposals preserved.
- This evidence report is committed separately from the runtime changes.
