# Task 26 - Live refunds and electronic rider payouts

## Status and signed scope

**LIVE REFUNDS/PAYOUTS BLOCKED - BOTH OPERATORS NOT_CONNECTED.** The user's Task25 confirmation that neither approved operator specifications nor authorized sandbox access is available still applies. Refund/payout capability, credentials and finality/idempotency contracts are unverified. No outgoing funds, sandbox refund/payout or confirmed asynchronous operator result was produced. Production execution remains disabled.

Read the signed PDF `/Users/muhirwagabooreste/Downloads/contrat_mvp_sequo_signed-2.pdf`:44-45 (page4) prioritize electronic transfers and make electronic rider payment conditional on capability;65-66 (page5) require effective SEQUO receipt and condition check before refund, and contain the unresolved deadline unit after "3";125 (page12) requires independent rider remuneration calculation/display. Complete relevant pages and audit rows44/45/65/66/125 were read. The PDF controls scope; stale audit/planning notes do not establish provider capability or a refund deadline.

Normal return/refund instructions, configured adapters, settlement ledger, rider endpoints, admin return operations and Rider payouts were traced after inspecting current diffs. Baseline35 API tests passed before changes. Existing Task20 parent-order-then-return locking already prevents two legacy return rows for the same order authorizing two instructions. Task22 already records independent mission liability and excludes salaried mission pay. Task24/25 already keep both provider transports disconnected.

## Available execution and provider limitations

| Provider / operation | Actual execution/reference/status reconciliation | Required inputs before live/sandbox implementation |
| --- | --- | --- |
| Yas / electronic refund | BLOCKED, NOT_CONNECTED; no operator reference or confirmed payment | Approved refund capability/guide, original sandbox paid transaction and merchant account, permitted amount/currency/full/partial rules, idempotency mapping, callback/status query/finality, authorized credentials and acceptance cases. |
| Yas / rider payout | BLOCKED, NOT_CONNECTED; no submitted/paid transfer | Approved business transfer capability for this use, verified beneficiary/wallet mapping, funding/limits/fees/holds, payout idempotency and asynchronous/query/statement proof, authorized sandbox and rollout approval. |
| Flooz / electronic refund | BLOCKED, NOT_CONNECTED; no operator reference or confirmed payment | The corresponding Flooz Togo refund specification/capability, original paid transaction/account and authorized sandbox, exact auth/reference/idempotency/finality rules. |
| Flooz / rider payout | BLOCKED, NOT_CONNECTED; no submitted/paid transfer | Flooz business transfer approval for independent riders, beneficiary verification, funding/limits/fees and approved API/auth/reconciliation plus sandbox acceptance. |

The [Task24 input matrix](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/docs/TASK24_PROVIDER_PREPARATION.md) and [Task25 live collection blockers](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/docs/TASK25_COLLECTIONS_RECONCILIATION.md) remain prerequisites. Public salary/merchant-payment advertising does not establish the required API capability. No third-party or other-country request format is adopted.

Current runtime calls stop at persisted refund instructions or recorded rider liabilities. No application workflow calls `PaymentProcessor.refundPayment` or `PaymentProviderAdapter.issuePayoutInstruction` to execute outgoing operations; the refund wrapper merely defines a future seam. Adapter calls return failed/not-connected or an unsent Pending result, never Submitted/Completed proof. The provider readiness read keeps refundEnabled/courierPayoutEnabled=false even with credentials or stored live flags. A Pending adapter result is not evidence of submission.

ReturnRefundInstruction already stores the canonical instruction UUID, amount, XOF currency, server author/time, REQUESTED status and NOT_SUBMITTED payment state. The parent order is not marked REFUNDED and no provider-confirmed refund posting is manufactured. PaymentRefundRequest currently lacks an instruction UUID/idempotency field; the future approved caller/mapping must carry the persisted UUID to the operator, rather than inventing a fresh attempt identity. ProviderPayoutInstructionRequest has an idempotencyKey but no live caller, durable outgoing operation store or reconciliation implementation; none is claimed by these preparations.

## Independent corrections

Return custody/refund writes now fail closed on unreadable stored custody history and roll back checked processing exceptions as well as runtime exceptions. A malformed legacy audit must not leave a partially recorded collection, receipt, inspection or refund instruction. Unreadable history bytes are preserved; no silent repair or synthetic actor/inspection evidence is added. Error responses use a fixed review message rather than private JSON/parser text. The parser rejects non-array/schema/trailing-token and duplicate-key ambiguity. Valid legacy arrays remain supported, and appending audit retains existing nodes' unknown fields and timestamp literals. GET authorizes the loaded owner/staff/relay context before parsing history, preserving403 denial even for another customer's malformed record. Canonical instruction replays remain supported.

Admin now uses the server's refundInstructionState, including LEGACY_REVIEW_REQUIRED for keys on Requested or ReceivedBySequo records. Unknown/contradictory states block a new instruction. Supported older responses retain the durable-object/workflow fallback. A frozen refund intent is cleared only when readback contains a durable instruction ID, REQUESTED state/status and the intended amount; RefundPending alone or missing/mismatched audit details preserve the retry key. This is confirmation of instruction persistence, not funds transferred.

Rider uses salary wording only for the same known salary aliases as the backend. Unknown/null/unsupported workforce is displayed as unknown rather than inferred salaried from payable=false. Existing recorded earnings, holds, corrections, historical freelance snapshots and NOT_INTEGRATED/paid0/processing0 values remain authoritative server reads, with no cash-out or transfer button. Proven historical independent liability remains visible if the rider later becomes salaried.

## Current operational treatment and decisions still required

The technical treatment is instruction-only refunds and recorded rider liabilities while execution is unavailable. An instruction/ledger balance must not be marked transferred, refunded or paid to close a case. No manual cash/wallet fallback, new beneficiary policy, tariff, provider fee deduction or payment deadline is authorized by this task. SEQUO finance must agree an operational procedure for outstanding obligations, including independent verification of any external payment and reconciliation before a later live adapter can retry it.

Clause66's missing deadline unit remains a business blocker: refundDeadlineState=UNCONFIGURED and refundDueAt=null. No hours/days/week or calendar is inferred. A written clarification is needed before due-date handling can be implemented or verified.

Before rider execution, select and lock actual unconsumed independent liabilities, preserve holds/corrections and verified historical workforce, and bind one durable payout instruction to one approved beneficiary/provider operation. Current profile/payable flags and displayed available balance alone are not payout authorization. Existing untagged legacy credits and unverified transfer debits need reconciliation under an approved financial policy; no automatic backfill or financial-history rewrite occurs here. Salaried missions accrue no independent payout; genuine older independent debt is a separate recorded obligation.

For either operation, an ambiguous timeout must lead to the operator's authoritative query/statement reconciliation using the original operation identity. It must not generate another money instruction. Actual operation-reference storage, asynchronous verification/finality and ledger posting need approved provider semantics before implementation/activation. Current zero outgoing calls prove no transfer during code verification; they do not prove an unavailable provider's live exactly-once behavior.

## Acceptance and verification

| Acceptance / contract point | Result |
| --- | --- |
| Sandbox refund and payout/reconciliation (44,45) | BLOCKED/UNVERIFIED separately for Yas and Flooz. Neither operator's approved capability/specs/access is available. No network call, operation reference, submitted/confirmed payment or permitted rollout is claimed. |
| Receipt/inspection before refund (65) | Existing authorized workflows still refuse before actual SEQUO receipt and explicit ACCEPTED inspection; wrong roles, rejected condition and legacy pending/key states cannot authorize a new instruction. Customer/foreign relay access is checked before stored history parsing. |
| Retry/idempotency/no funds | Two eligible legacy rows for one order compete under the existing parent lock; exactly one internal UUID is recorded. Same/fresh retry keys preserve UUID/actor/history, changed amount and other-row requests are rejected. Both real adapter spies record zero refund/payout calls; ledger count stays unchanged, instruction remains REQUESTED/NOT_SUBMITTED and parent order is not REFUNDED. This is zero-send proof while disabled, not proof of a future operator's exactly-once transfer. |
| Checked failures and historical evidence | Malformed/non-array/trailing/schema/duplicate-key history refuses all five custody/refund writes with safe review errors, preserves raw data and hides private sentinels on authorized GET; foreign GET stays403. Injected checked audit serialization failure rolls back status/key/amount/UUID/actor/time. Valid extra legacy fields and offset timestamp strings survive an append. |
| Salaried/independent ledger (45,125) | Existing paid journey and payout visibility regressions pass: salaried missions have no independent payable, freelance liabilities/SEQUO shortfall are correctly separate, historical proven freelance earnings survive later salary status, and unverified debits are never treated as confirmed transfers. Unknown classification is no longer displayed as salary in Rider. |
| Instruction versus transferred funds | Admin tests/browser distinguish server legacy/unknown/contradictory states, incomplete or wrong-amount readback, durable-object fallback, stable retry after timeout/reload and NOT_SUBMITTED. Rider reads retain NOT_INTEGRATED with paid0/processing0 and scoped historical balances. |
| Deadline (66) | BLOCKED on written unit/calendar clarification. UNCONFIGURED/null due-date behavior remains explicit; no invented due-date boundary is tested as contractual. |

Baseline API35 tests passed before edits. Final focused command passed52 tests,0 failures/errors:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test \
  --tests '*ReturnCustodyHttpTest' --tests '*ReturnCustodyRestartTest' \
  --tests '*ReturnProcessingServiceTest' --tests '*ReturnPersistenceServiceTest' \
  --tests '*ReturnWorkflowNotificationTest' --tests '*CustomerReturn*Test' \
  --tests '*RiderPayoutVisibilityTest' --tests '*SettlementJourneyHttpTest' \
  --tests '*MerchantBusinessReadsHttpTest' --tests '*PaymentProviderAdapterTest' \
  --tests '*PaymentReadinessConfigurationTest' --tests '*PaymentWebhookFailClosedHttpTest' \
  --tests '*H2V51FlywayCompatibilityTest' --tests '*NotificationPreferenceRepositoryTest'
```

PostgreSQL verification passed8 custody tests with real Flyway throughV51 and Hibernate validation, plus production jar build:

```sh
SEQUO_RETURN_CUSTODY_TEST_URL=jdbc:postgresql://127.0.0.1:55434/sequo_task14_refund_safety \
  JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test \
  --tests '*ReturnCustodyHttpTest' bootJar
```

The guarded datasource uses only a disposable loopback55434 fixture with local-trust `sequo_fixture`; no production/staging secret or database was opened. SQL confirms original V51 success/checksum285089329, no order with multiple internal refund instructions, no Refund/Payout ledger postings and no REFUNDED parent order. The task-owned cluster was stopped afterward. No schema migration was added or modified in Task26.

The initial focused run exposed test-fixture issues (Kotlin's null Mockito matcher and malformed rows retained into another test), which were corrected without weakening assertions. It also found that committed V51's PostgreSQL multi-column ALTER cannot execute directly in H2 Flyway-backed tests. Test-only auto-configuration now executes equivalent separated ALTER statements for exact V51 on H2 and preserves the original SQL metadata/checksum; PostgreSQL and every other resolver stay unchanged. The original NotificationTest Flyway/validate configuration and assertions are retained. A dedicated H2 migration/schema/checksum regression passes. The main V51 file remains byte-identical to the committed migration; no checksum repair was performed. Production jar inspection confirms test-only H2 auto-configuration/metadata and payment fixture verifiers are absent.

Admin commands from `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin`:

```sh
node --test tests/*.test.mjs
# Baseline17 tests, final22 tests PASS (5 new refund-state regressions).
npm run type-check
VITE_SEQUO_API_BASE_URL='' npm run build-only
NODE_PATH=/opt/homebrew/lib/node_modules/@google/gemini-cli/node_modules \
  node scripts/return-operations-browser-check.cjs
# PASS:baseline/final browser, type check, production build.
```

The local mock-HTTP browser covers legacy markers in Requested/ReceivedBySequo, no new instruction action, frozen retry through reload and canonical instruction recovery after lost response. Screenshot `/tmp/sequo-task26-admin-refund.png` was visually checked. Mock responses are not operator evidence.

Rider commands from `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider`:

```sh
./gradlew --offline :shared:testAndroidHostTest --tests '*RiderPayoutClientTest'
# Baseline PASS:3 tests.
./gradlew --offline :shared:testAndroidHostTest --tests '*RiderPayoutClientTest' \
  :shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64
# PASS:3 targeted HTTP tests plus Android/iOS main compilation.
```

Independent read-only review checked safe history handling, checked rollback, auth-before-parse, existing parent locks, stable admin amount/key readback, salary aliases and instruction-versus-funds wording. `git diff --check` passes in all touched repositories. No real funds were sent. Authorized remote staging, operator sandbox and physical-device checks remain UNVERIFIED.

Local logs: `/tmp/sequo-task26-{baseline,api-first-run,api-tests,postgres}.log`; `/tmp/sequo-task26-admin-{baseline-tests,baseline-browser,tests,type,build,browser}.log`; `/tmp/sequo-task26-rider-{baseline,checks}.log`. Final XML is preserved under `/tmp/sequo-task26-h2-results/` and `/tmp/sequo-task26-postgres-results/`.

## Authorized staging/provider reproduction - UNVERIFIED

1. Obtain each operator's written refund/payout capability, versioned specifications, authorized sandbox configuration and approved acceptance cases. Confirm beneficiary verification, funding/limits/fees, auth/callback/status-query/finality and provider idempotency retention. Keep production operations disabled. For refunds, obtain a genuinely paid sandbox collection through the approved Task25 path, not a locally seeded paid order.
2. Customer requests an eligible return, authorized relay/SEQUO operators record actual collection and physical receipt, then explicit ACCEPTED condition inspection. Admin records a refund instruction and refreshes the canonical UUID/amount/REQUESTED/NOT_SUBMITTED state. Refuse before receipt/inspection, for wrong roles and for legacy review-required history. Repeat identical/fresh-key submissions and concurrent eligible legacy rows for one order: only one canonical instruction may exist.
3. Once an approved adapter is implemented, bind that exact instruction UUID to one durable outgoing operation and provider idempotency identity. Test accepted/refused/unknown-timeout responses in the operator sandbox without production credentials. Query/reconcile before retrying unknown outcomes; replay identical requests/callbacks and reject mismatched amount/currency/original transaction/payee/reference. Only authoritative final proof may mark refunded/post the corresponding ledger; submitted/processing remains distinct.
4. Complete a paid independent rider mission through the normal staged journey and read its immutable workforce/payable/shortfall ledger. Resolve holds, corrections, prior transfers and approved beneficiary ownership. Submit one approved sandbox payout operation for that liability, then exercise timeout/status queries, callback verification and repeated/concurrent requests. At most one provider debit and one settlement posting may occur. Client amounts/roles cannot authorize payment.
5. Repeat with a salaried mission: no independent liability or delivery-based payout. Test a rider now salaried with proven historical freelance liability and an unknown-workforce legacy row separately; apply the approved legacy policy without erasing history or treating current profile alone as proof. Verify cross-rider/customer/merchant access denied and no wallet credentials/contact disclosed.
6. Refresh/restart admin and Rider views during submitted/pending/failed/confirmed states. Compare actual provider operation references/status and ledger, clear stale views after denied/offline reads and check English/French copy on authorized devices. Validate the clause66 due boundary only after its exact unit/calendar is clarified in writing. Obtain operator/SEQUO rollout approval and operational reconciliation before any production activation; this task has not enabled or sent funds.

## What subsequent work can safely depend on

Canonical return instruction IDs, order-level locking, receipt/inspection gates, independent ledger accruals, owner/role boundaries and explicit unsent/unconfirmed states remain available. Admin retries preserve their identity until audited readback; Rider does not infer salary from unknown classification or transfer success from a ledger amount. Live collection, refund and rider payout execution, provider references/finality, a new transfer store/worker and the refund deadline are not implemented/approved by this blocked task. A later provider integration must supply those missing contracts and preserve one operation identity and reconciliation across retries.


## Changed files and focused commits

sequo-api (8 files):

- [SETTLEMENTS_AND_RETURNS.md](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/SETTLEMENTS_AND_RETURNS.md)
- [docs/TASK26_REFUNDS_RIDER_PAYOUTS.md](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/docs/TASK26_REFUNDS_RIDER_PAYOUTS.md)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/returns/ReturnController.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/returns/ReturnController.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/returns/ReturnPersistence.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/returns/ReturnPersistence.kt)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/returns/ReturnCustodyHttpTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/returns/ReturnCustodyHttpTest.kt)
- [src/test/kotlin/dev/orestegabo/sequo_api/support/H2V51FlywayCompatibility.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/support/H2V51FlywayCompatibility.kt)
- [src/test/kotlin/dev/orestegabo/sequo_api/support/H2V51FlywayCompatibilityTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/support/H2V51FlywayCompatibilityTest.kt)
- [src/test/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports)

sequo-admin (4 files):

- [scripts/return-operations-browser-check.cjs](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin/scripts/return-operations-browser-check.cjs)
- [src/ReturnOperationsPanel.vue](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin/src/ReturnOperationsPanel.vue)
- [src/refundInstructionState.ts](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin/src/refundInstructionState.ts)
- [tests/refundInstructionState.test.mjs](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin/tests/refundInstructionState.test.mjs)

SequoRider (1 files):

- [shared/src/commonMain/kotlin/dev/orestegabo/sequorider/ui/payout/PayoutScreen.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider/shared/src/commonMain/kotlin/dev/orestegabo/sequorider/ui/payout/PayoutScreen.kt)

Focused commits: API `8f7bfaf` (refund audit atomicity/privacy and regressions), `3099720` (test-only H2 V51 compatibility/checksum); admin `81e43e3` (audited refund readback and frozen retry); Rider `94ca9da` (unknown workforce wording). This evidence report and planning-note correction are committed separately.

Existing API output/audit/invoice artifacts and the intentionally uncommitted Task16 decision note remain untouched. No customer/Vendor source, production migration, deployment secret, provider transport or real transfer was changed by Task26.
