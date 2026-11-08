# Task 20 — Return custody, inspection and refund instruction

## Scope and evidence

Signed source: `/Users/muhirwagabooreste/Downloads/contrat_mvp_sequo_signed-2.pdf`, clauses 63–66 (page 5) and 120 (page 12). Relevant audit findings: rows 63–66 and 120 in `output/SEQUO-MVP-AUDIT.md`; the source PDF controls scope. Inspected current diffs and runtime controllers/services before edits. Baseline: 17 tests in ReturnPersistenceServiceTest, ReturnProcessingServiceTest, ReturnWorkflowNotificationTest and CustomerReturnHttpTest passed.

| Contract | Implemented evidence |
| --- | --- |
| 63: eligible relay intake | Existing persisted return ID and actual server-issued PIN are entered at `/relay-operator.html`; authorized site, immutable order classifications and current return capability/availability are checked. Intake, scoped lists and detail read back persisted custody. Click-and-collect policy stays PENDING. |
| 64: SEQUO collection | Explicit staff collection from DroppedAtRelay, followed by a separate physical receipt action. Authenticated actors, server timestamps and operator notes are durable. |
| 65: actual receipt and condition check | Admin refund instruction is refused until receipt and explicit ACCEPTED inspection; rejected inspection cannot authorize an instruction. Original receipt notes/responsibility remain separate from later inspection. |
| 66: instruction and deadline | Exactly one durable instruction per return; other instructed/pending legacy returns for the whole order block further instruction. REQUESTED / NOT_SUBMITTED is distinct from actual provider payment. **Deadline unit remains blocked pending written SEQUO clarification.** |
| 120: merchant visibility by rights | Scoped merchant read returns only its own product lines and coarse return/payment/instruction states. No customer identity/PIN/reason, staff notes, other merchant lines or whole-order refund amount. Vendor presentation can reuse this in its later returns task. |

## Runtime contract

Existing `/api/returns` routes and return/status model are extended. Staff default GET lists all states; status/customer filters remain supported. Customer reads stay owned, including for other requested IDs; staff scope takes precedence for accounts that also have CUSTOMER. Selected operator surface remains the existing small web module, not a new Hub app.

1. Relay operator: `POST /api/returns/{id}/relay-dropoff` with `{relayPointId,rawReturnPin,idempotencyKey,metadata?}`. Old callers without a key retain deterministic intake identity. The operator confirms physical handoff in the UI. Wrong PIN/site/category cannot advance custody.
2. Support/admin: `POST .../collection` with `{idempotencyKey,metadata?}` moves DroppedAtRelay → CollectedBySequo.
3. Support/admin: `POST .../physical-receipt` with `{idempotencyKey,conditionAssessment,responsibility}` moves CollectedBySequo → ReceivedBySequo. It records receipt, not inspection approval.
4. Support/admin: `POST .../inspection` with `{idempotencyKey,decision,conditionAssessment,responsibility}`, decision ACCEPTED or REJECTED. Acceptance retains ReceivedBySequo; rejection records Rejected.
5. Admin: existing `POST .../refund` with `{amountCfa,idempotencyKey}` records RefundPending plus a single instruction reference, amount, XOF currency, author and server time. Provider state is NOT_SUBMITTED; parent order is not marked REFUNDED and no provider refund/financial posting is executed.

All actions lock order then return. Replays preserve original actor/time/history, compare intent and return canonical state after later stages; changed intent is rejected. Repeated/concurrent refund requests, including fresh retry keys, cannot create another instruction. Browser intents stay frozen through POST/readback failures; no optimistic custody or payment success. Relay detail/list are redacted and site-scoped; customer history omits internal actors/notes. No ID image is collected.

`history` contains action, createdAt, actorUserId and metadata. Internal retry keys/hashes are not returned. Refund instruction ID is the stable identity a later provider integration must reuse. Instruction persistence is not evidence of a bank/wallet transaction.

Effective deadline settings: `refundDeadlineState=UNCONFIGURED`, `refundDueAt=null`. No hours/days/week or business calendar is assumed. Once written clarification arrives, implement its exact unit/calendar from immutable receivedBySequoAt and test immediately before/at/after that due instant, including late instructions; this is not currently verified or enabled.

## Migration and active-record policy

V48 adds CollectedBySequo, custody audit, explicit inspection evidence, original receipt context and the single unique instruction identity. It preserves legacy request/receipt/refund data. Existing recorded receipt notes/responsibility are copied as original context, without fabricating collection, inspection approval, actor/time or provider success.

- Legacy ReceivedBySequo: staff can inspect against the existing physical receipt. Existing text does not automatically authorize refund. Original receipt evidence survives and an unchanged old receipt replay remains supported.
- Legacy RefundPending or a prior refund key without the new instruction ID: expose LEGACY_REVIEW_REQUIRED and block another instruction. Operators must reconcile external payment/ledger evidence before a separately reviewed migration or correction; no automatic retry/refund is issued.
- Duplicate legacy returns for one whole order remain readable. Once any row has an instruction or legacy pending/key, another row cannot authorize another payment instruction.
- Existing PIN hashes are preserved; unknown legacy credentials are never fabricated. Collection/receipt cannot precede their persisted custody timestamps.
- Click-and-collect or non-relay/manual transport requires approved instructions before custody movement; no unapproved transport rule is introduced here.

## Verification

Final checks and their results are recorded below. Only disposable local identities/data are used. No production database, credentials or payment provider is used.

## Acceptance and remaining external checks

| Check | Result |
| --- | --- |
| Refuse instruction before receipt or inspection | Automated authenticated HTTP and domain regressions, including every earlier stage and rejected inspection. |
| Ineligible relay drop-off fails | Frozen food classification, wrong site/PIN, and authorized available relay intake regressions. |
| Duplicates cannot issue two refunds | Concurrent instruction requests return one reference and one audit event; changed amount/intent rejected. Legacy pending/duplicate rows require review. No actual money transfer executed. |
| Due date once clarified | BLOCKED: signed PDF omits the unit of “3”; written clarification requested, not received. Default unset state is verified. |
| Persisted who/when and restart | HTTP actor/time chain and actual application close/reopen test; original receipt evidence retained. |
| Operator/customer screens | Browser fixtures and Android/iOS compilation/network tests; actual devices and remote staging remain UNVERIFIED. |

To reproduce on authorized staging: seed a paid, delivered eligible goods order through the normal staging payment fixture; sign in as its customer and request a return from Orders. Copy its actual return ID/PIN to the assigned relay operator web module and confirm handoff. Refresh its own-site list/history. As SEQUO admin, open Returns, record collection, physical receipt, accepted inspection and refund instruction. At each preceding stage POST `/refund` with a stable key and verify rejection. Submit the final instruction twice/concurrently with different keys and GET the return: the reference/actor/time/history count must stay unchanged and provider state must remain NOT_SUBMITTED. Reopen both apps/web pages; verify the same order, return and custody times. Repeat with a different relay/site, wrong PIN, frozen food/perishable/sensitive order and rejected inspection; no instruction may be created. Use separate customer/merchant sessions to verify ownership and scope. Never use production payment credentials for this exercise.

Provider execution, callback reconciliation and any actual refunded balance remain unavailable/unverified here. A later provider task can depend on the stable durable instruction identity and gates, but must add provider evidence rather than treating RefundPending as payment confirmation.

## Commands and results

All commands run from the named repository. Java 21 was selected for the API; Gradle dependencies came from the offline cache.

- API: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline compileKotlin` — PASS.
- API: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test --tests '*Return*Test'` — PASS, 25 tests (including 5 custody HTTP cases, actual custody/customer restart, customer ownership/72-hour cases, persistence, domain and notification regressions). The first implementation run exposed missing Java-time support in the local audit mapper; audit storage now writes ISO timestamps and parses them explicitly. Final rerun is green.
- API notifications: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test --tests '*NotificationOutboxWorkerTest' --tests '*NotificationRoutingServiceTest'` — PASS, 9 tests.
- API/PostgreSQL: `SEQUO_RETURN_JOURNEY_TEST_URL=jdbc:postgresql://127.0.0.1:55434/sequo_task14_return_custody JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test --tests '*ReturnCustodyHttpTest' --tests '*CustomerReturnHttpTest'` — PASS. This variable selects PostgreSQL for the 6 customer HTTP cases; the 5 custody cases in this invocation still used H2.
- API/PostgreSQL custody: `SEQUO_RETURN_CUSTODY_TEST_URL=jdbc:postgresql://127.0.0.1:55434/sequo_task14_return_custody JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test --tests '*ReturnCustodyHttpTest'` — PASS, all 5 custody cases explicitly on PostgreSQL with Flyway and Hibernate schema validation.
- Local PostgreSQL evidence: Flyway version 48 success=true; 1 instructed return / 1 unique instruction / 1 with all required receipt/inspection/author evidence; 5 durable custody events for that instruction. The fixture runs at loopback port 55434, user sequo_fixture, empty local-trust password; no production datasource. The task-owned cluster was stopped after verification.
- API operator browser: `NODE_PATH=/opt/homebrew/lib/node_modules/@google/gemini-cli/node_modules node scripts/relay-operator-browser-check.cjs` — PASS: current parcel flow, real-API-shaped return intake, wrong PIN, uncertain accepted intake/stable retry, scoped history, site switching, restore, offline and authorization errors. Browser uses mock HTTP; server authorization is proven separately by the HTTP suites.
- Admin: `npm run type-check` — PASS; `VITE_SEQUO_API_BASE_URL='' npm run build-only` — PASS; `node --experimental-strip-types --test tests/*.test.mjs` — PASS, 3 existing tests.
- Admin browser: `NODE_PATH=/opt/homebrew/lib/node_modules/@google/gemini-cli/node_modules node scripts/return-operations-browser-check.cjs` — PASS: authenticated queues/detail, collection auth-refresh retry, receipt readback failure/frozen retry, accepted/rejected inspection including legacy receipt, cleared drafts between returns, one instruction recovered after reload, provider-not-submitted state, unconfigured deadline, empty queue and expired/missing/non-admin fail-closed sessions. Mock HTTP only. Mobile operator and desktop admin screenshots were visually checked at `/tmp/sequo-task20-relay-web.png` and `/tmp/sequo-task20-admin.png`.
- Sequo: `./gradlew --offline :shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64` — PASS (Android and iOS simulator main code).
- Sequo: `./gradlew --offline --no-configuration-cache -I /tmp/sequo-task20-customer-tests.init.gradle :shared:testAndroidHostTest --tests '*ReturnClientTest'` — PASS, 2 tests. The temporary external init script excludes only `**/DeliveryPricingTest.kt` from compileAndroidHostTest. Normal host-test compilation remains blocked by the pre-existing malformed `calculateDeliver yPricing` call in that unrelated, already modified commonTest file. It was not edited or staged. To reproduce the isolated check, create the init script with `gradle.projectsEvaluated { rootProject.findProject(':shared').tasks.getByName('compileAndroidHostTest').exclude('**/DeliveryPricingTest.kt') }`.
- `git diff --check` in each touched repository — PASS.

Local logs: `/tmp/sequo-task20-{baseline,compile,api-tests,postgres,postgres-custody,notifications,relay-browser,admin-type,admin-build,admin-browser,customer-compile,customer-tests}.log`. Raw successful 25-test XML is retained under `/tmp/sequo-task20-h2-results/` for this session. These temporary paths are not committed artifacts.

## Changed files

API production:

- `src/main/kotlin/dev/orestegabo/sequo_api/domain/returns/ReturnController.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/returns/ReturnPersistence.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/returns/ReturnProcessing.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/notification/NotificationOutboxWorker.kt`
- `src/main/resources/db/migration/V48__return_custody_and_refund_instruction.sql`
- `src/main/resources/static/relay-operator.html`
- `src/main/resources/static/relay-operator.js`

API checks/documentation:

- `scripts/relay-operator-browser-check.cjs`
- `src/test/kotlin/dev/orestegabo/sequo_api/domain/returns/ReturnCustodyHttpTest.kt`
- `src/test/kotlin/dev/orestegabo/sequo_api/domain/returns/ReturnCustodyRestartTest.kt`
- `src/test/kotlin/dev/orestegabo/sequo_api/domain/returns/ReturnPersistenceServiceTest.kt`
- `src/test/kotlin/dev/orestegabo/sequo_api/domain/returns/ReturnProcessingServiceTest.kt`
- `src/test/kotlin/dev/orestegabo/sequo_api/domain/returns/ReturnWorkflowNotificationTest.kt`
- `SETTLEMENTS_AND_RETURNS.md`
- `docs/POSTMAN_ENDPOINTS.md`
- `docs/contracts/CONTRACT_MVP_TRACKER.md`
- `docs/TASK20_RETURN_CUSTODY.md`

Admin:

- `src/App.vue`
- `src/ReturnOperationsPanel.vue`
- `scripts/return-operations-browser-check.cjs`

Sequo:

- `shared/src/commonMain/kotlin/dev/orestegabo/sequo/core/catalog/CartApiClient.kt`
- `shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/orders/CustomerReturnContent.kt`
- `shared/src/commonMain/kotlin/dev/orestegabo/sequo/feature/settings/AppText.kt`
- `shared/src/commonMain/composeResources/values/strings.xml`
- `shared/src/commonMain/composeResources/values-fr/strings.xml`
- `shared/src/androidHostTest/kotlin/dev/orestegabo/sequo/ReturnClientTest.kt`

Next work can depend on the custody routes, additive DTO fields, scoped merchant read and durable instruction identity above. It must not assume a due date, approved Click-and-collect transport, provider submission/success, item-level return allocation or a reconciled legacy refund. `TASK16-SETTINGS-TO-DECIDE.md` and all pre-existing unrelated user files remain uncommitted.

## Focused commits

- API `21e0402` — custody audit, inspection gates, unique instruction and migration/regressions.
- API `95ad3a5` — scoped relay return intake and browser regression.
- Admin `e115db1` — four-step return operations and browser regression.
- Sequo `eda55ea` — customer custody/instruction state and localized display.
- API documentation is committed separately with this report and updated route/policy notes.
