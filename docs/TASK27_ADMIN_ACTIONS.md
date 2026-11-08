# Task 27 — Minimum admin actions and access checks

## Scope and source evidence

The signed source is `/Users/muhirwagabooreste/Downloads/contrat_mvp_sequo_signed-2.pdf`. Relevant pages and audit rows 15/18/36/38/64–66/69/107 were read after inspecting normal runtime entry points and current diffs. The audit is a locator; it predates several already implemented workflows.

| Signed reference | Evidence covered |
| --- | --- |
| 15, page 2; 107, page 12 | Existing authenticated internal admin web, live operations and effective parameters. |
| 18, page 3 | Server role checks; customer, merchant and rider sessions cannot authorize admin actions. |
| 36, 38, page 3 | Existing cooperative review, paid-order grouping, per-package receipt, sealing, final dispatch and persisted actor history. |
| 64, 65, page 5 | Existing SEQUO collection, physical receipt and accepted condition inspection before one internal refund instruction. |
| 66, page 5 | The source omits the unit after “3”. No deadline unit is inferred; the server retains UNCONFIGURED and a null due date. |
| 69, page 5 | Merchant overrides reuse the existing selected commission rule. Server default and bounds are displayed; category precedence is not invented. |

The selected relay surface remains the existing API-served `/relay-operator.html` module, permitted by the page 2–3 simple-web-module scope. Admin → Relay points now links to it using the configured API base URL. It has its own authenticated session; tokens are not passed in URLs or shared with another origin. An authorized account must be able to use its existing phone sign-in. Admin/super-admin accounts can operate across sites, relay partners remain assigned-site scoped. A dedicated Hub application, connected locker controls and generic rider disabling are outside these changes.

## Changes and operational contract

- **Commissions:** reuse exact GET/PUT/DELETE `/api/commissions/merchant-overrides/{merchantId}`. Exact reads add policy bounds/default, an opaque revision and the latest 50 persisted audit events. The editor reads the current merchant, previews the before/after rate and reason, then requires explicit confirmation. It does not treat a mutation response as proof of the current effective rate: an authenticated exact read and matching audit entry confirm the saved intent, and the existing summary is refreshed afterward.
- **Guarded writes:** the new editor always sends a stable `idempotencyKey` and `expectedRevision` together. A merchant-row lock serializes changes even when the override does not exist. Identical same-actor retries return their original operation without reapplying it; changing that intent or using a stale revision returns 409. Reset/recreate cannot revive an old revision. DELETE remains 204 and the shared client handles an empty response before doing readback. Session refresh replays the same method and JSON body.
- **Compatibility and audit:** existing unguarded authorized callers remain supported, with every new change audited. Such legacy writes do not acquire optimistic stale protection merely by omitting the guard. New audit records retain actor, before/after rate and override presence, reason, server time, operation ID and retry identity. Paid order and settlement commission snapshots are unchanged. The UI preserves the complete actor-scoped pending intent through failed readback/reload, and does not expose a write action against older servers without policy/revision/history metadata.
- **Grouping:** cooperative decisions, full grouping bodies, receipt references, final package identities and dispatch intent remain frozen after an uncertain action. New actions are blocked until server state is verified or the saved intent is explicitly revised after refresh. Changing the selected manifest clears its receipt draft. Consequential actions show contextual confirmations. Existing server prerequisites and audit remain authoritative.
- **Returns:** existing collection/receipt/inspection/refund actions gain contextual confirmation, including amount and instruction-only wording. Existing stable keys, receipt/inspection gates, legacy-review checks and authoritative readback are retained. An instruction remains REQUESTED/NOT_SUBMITTED; this task does not transfer funds.
- **Relay decisions:** keeping a parcel at the relay or recording physical seller return requires confirmation. Uncertain retries retain the complete decision and event/key, block a different parcel action, and wait for exact server history before reporting success. Scoped parcel history now projects its existing event ID and actor; old nullable actor records remain readable. Seller disposition stays explicit and operator-controlled.
- **Parameters:** Settings continues to read the authenticated ten-item Annex A.6 inventory, including effective/source/readiness/change-procedure data. Read-only preparation remains sufficient for unapproved configuration. No new parameter mutation or business value is invented.

## Access matrix

| Actor | Admin web / inventory / commissions / cooperative review | Grouping and return collection/receipt/inspection API | Refund instruction API | Relay review / physical seller return |
| --- | --- | --- | --- | --- |
| ADMIN, SUPER_ADMIN | Allowed | Allowed, workflow prerequisites enforced | Allowed after required custody/inspection | Allowed / allowed after 30-day review eligibility |
| SUPPORT_AGENT | Denied | Existing operations access retained | Denied | Review module unavailable / existing staff seller-return API access retained |
| Assigned RELAY_PARTNER | Denied | No SEQUO custody authority; own-site return intake remains supported | Denied | Own-site review only / denied |
| Customer, merchant, rider | Denied | Admin mutations denied; existing scoped customer/merchant/rider journeys retained | Denied | Denied |
| Missing, invalid or expired session | Denied; mounted data and saved sensitive drafts clear on session end | Denied | Denied | Denied |

These are existing role boundaries, not newly granted support/relay privileges. The admin application itself accepts active ADMIN/SUPER_ADMIN sessions only.

## Acceptance results and checks

PASS locally: **53 API tests**, **13 PostgreSQL workflow tests**, **4 PostgreSQL upgrade tests**, **28 admin Node tests**, admin type check/production build, API production jar and **5 browser fixtures**. Zero final test failures/errors/skips. Browser fixtures are local HTTP evidence, not deployed staging or provider evidence. Baseline 23 API tests and 22 admin Node tests passed before changes.

| Acceptance | Result |
| --- | --- |
| Allowed/denied actions | PASS locally. Targeted authenticated API and browser checks cover admin, non-admin, missing/expired sessions and two relay operators. Existing paid grouping and return-custody journeys exercise actual server persistence and role enforcement. |
| Stale records | PASS locally. Commission stale/conflicting revisions are rejected; uncertain grouping/commission actions block new writes. Relay review after final disposition is rejected. Failed reads show unverified/unavailable state. |
| Duplicate submits | PASS locally. Guarded commission repeats retain one audit operation; grouping uses the canonical manifest/final dispatch; return instructions retain one canonical identity; relay review/seller-return concurrent repeats retain one custody event. |
| Session expiry | PASS locally. Admin mutation refresh preserves method/body; exhausted or denied sessions unmount data. The relay module clears both parcel and return data on access denial. |
| Server outcomes/history | PASS locally. Exact readback, actor/time/history, browser reload and persisted workflow transitions are exercised. No local mock rows become production records or optimistic financial outcomes. |

### Commands

API working directory: `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api`.

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test \
  --tests '*MerchantCommission*Test' --tests '*OrderFulfillmentPersistenceServiceTest' \
  --tests '*RelayOperatorHttpTest' --tests '*RelayDeadlinesTest' \
  --tests '*ReturnCustodyHttpTest' --tests '*ReturnCustodyRestartTest' \
  --tests '*Consolidation*Test' \
  --tests '*CustomerCartCheckoutHttpTest.approvedTwoMerchantGroupingRequiresSequoReceiptsAndCreatesOneFinalDispatch' \
  --tests '*AdminBusinessParameterInventoryHttpTest' \
  --tests '*AdminWebControllerAuthorizationTest' --tests '*AdminWebReadHttpTest'

NODE_PATH=/opt/homebrew/lib/node_modules/@google/gemini-cli/node_modules \
  node scripts/relay-operator-browser-check.cjs
```

Admin working directory: `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin`.

```sh
node --test tests/*.test.mjs
npm run type-check
VITE_SEQUO_API_BASE_URL='' npm run build-only
NODE_PATH=/opt/homebrew/lib/node_modules/@google/gemini-cli/node_modules node scripts/commission-override-browser-check.cjs
NODE_PATH=/opt/homebrew/lib/node_modules/@google/gemini-cli/node_modules node scripts/consolidation-browser-check.cjs
NODE_PATH=/opt/homebrew/lib/node_modules/@google/gemini-cli/node_modules node scripts/return-operations-browser-check.cjs
NODE_PATH=/opt/homebrew/lib/node_modules/@google/gemini-cli/node_modules node scripts/business-parameters-browser-check.cjs
```

### PostgreSQL and preserved evidence

The task-owned PostgreSQL 15 cluster ran only on loopback port 55434 under `sequo_fixture`, with empty local-trust fixture credentials. It was explicitly stopped after checks. No remote database or provider was opened.

```sh
SEQUO_COMMISSION_ADMIN_TEST_URL=jdbc:postgresql://127.0.0.1:55434/sequo_task14_admin_actions \
SEQUO_RELAY_JOURNEY_TEST_URL=jdbc:postgresql://127.0.0.1:55434/sequo_task14_admin_actions \
SEQUO_RETURN_CUSTODY_TEST_URL=jdbc:postgresql://127.0.0.1:55434/sequo_task14_admin_actions \
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test --rerun \
  --tests '*MerchantCommissionAdminHttpTest' \
  --tests '*CustomerCartCheckoutHttpTest.approvedTwoMerchantGroupingRequiresSequoReceiptsAndCreatesOneFinalDispatch' \
  --tests '*ReturnCustodyHttpTest' bootJar
# PASS: 13 tests, fresh Flyway through V52, Hibernate schema validation, API production jar.

SEQUO_COMMISSION_ADMIN_TEST_URL=jdbc:postgresql://127.0.0.1:55434/sequo_task14_admin_upgrade \
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test --rerun \
  --tests '*MerchantCommissionAdminHttpTest'
# PASS: 4 tests on a disposable V51 database clone upgraded to V52.
```

The clone was seeded with a 12.50% local legacy override before migration. Its exact merchant/rate/reason/actor/createdAt/updatedAt remained unchanged afterward, with zero fabricated audit entries for that merchant. Original V51 checksum 285089329 was retained; V52 checksum -827609837 was successful on both databases. The fresh concurrency tests left 10 expected commission audit events and zero duplicate retry identities. Legacy replay tests also prove an old successful PUT/DELETE cannot overwrite a later rate or a recreated override.

Logs: `/tmp/sequo-task27-{baseline-api,api-tests,postgres,postgres-upgrade,relay-browser,panels-build,consolidation-browser,returns-browser,parameters-browser}.log` and `/tmp/sequo-task27-admin-{tests,type,build,commission-browser}.log`. Preserved final XML: `/tmp/sequo-task27-h2-results/`, `/tmp/sequo-task27-postgres-results/`, `/tmp/sequo-task27-upgrade-results/`. SQL evidence: `/tmp/sequo-task27-upgrade-{before,after}.txt`, `/tmp/sequo-task27-postgres-evidence.txt`.

Screenshots `/tmp/sequo-task27-admin-commission.png`, `/tmp/sequo-task27-grouping-admin.png`, `/tmp/sequo-task27-returns-admin.png` and `/tmp/sequo-task27-relay-web.png` were visually checked. An independent read-only review found no concrete safety/legacy regression in the shared authenticated request integration or relay changes. Both repositories pass `git diff --check`.

## Migration and remaining decisions

V52 adds `merchant_commission_audit` and its integrity/index constraints. It does not edit applied migrations, existing override rows, financial snapshots or custody history. Deploy migration before the new admin editor. Legacy override details remain visible with an explicit incomplete-history message; past approval or actors are not fabricated. Review those current terms before the next change. The guarded protocol is additive for supported older callers; migrating any external unguarded caller to the revision/key pair is an operational follow-up.

Unapproved subscription/referral benefits remain inactive in production, as explicitly requested. Task 16 zone/ETA approvals, relay tariff/active-parcel policy decisions and clause 66's deadline unit remain pending. Neither Yas nor Flooz approved specifications/sandbox access is available; refund/payout instructions must not be marked transferred. No provider or real-funds exercise is claimed. No new Click-and-collect rule is imposed.

### Deployed staging reproduction — UNVERIFIED

1. Apply V52 to authorized staging after the normal backup/review procedure. Compare an existing override, paid order commission and settlement before/after; old values/history must be unchanged. Use real staging accounts for ADMIN, a second admin, customer, merchant, rider and two assigned relay operators. Do not put credentials in this document.
2. In Admin → Commissions, read a real merchant, enter an approved rate/reason and inspect the confirmation before submitting. In a second admin session change that same merchant, then submit the first stale preview: expect 409, refreshed current terms and no overwrite. Retry an identical successful PUT/DELETE: one operation/audit entry only. A changed body under the old key must fail. Refresh/restart and compare actor/history and the previously paid order's frozen rate.
3. In Deliveries, use a paid two-merchant staged order and prepared packages. Cancel a cooperative/grouping confirmation and verify no API write. Approve grouping, collect through the authorized rider flow, confirm each physical SEQUO receipt, seal and dispatch once. Attempt dispatch before prerequisites, wrong-role actions and repeated dispatch. Confirm one final mission and actual actor history after refresh/restart.
4. In Returns, use an eligible persisted return. Attempt refund instruction before collection/physical receipt/accepted inspection; expect refusal. Complete actual custody steps, confirm one approved instruction amount, interrupt/read back/retry with the saved identity, and compare REQUESTED/NOT_SUBMITTED plus audit. A non-admin must not instruct funds. Do not execute an external transfer while verifying this task.
5. Open the relay web module from Admin; sign in through its existing authorized account flow. Review one overdue parcel, cancel then confirm a decision, repeat after a deliberately interrupted response, and verify the stored event/actor after reload. An assigned partner at another site must be denied. Only authorized SEQUO staff may record physical seller return after its prerequisites; no timer performs disposition.
6. Interrupt each read/write with offline mode and expire/revoke the session. Unconfirmed actions must retain their saved body until valid readback, and denied sessions must clear records and sensitive drafts. Reauthenticate with a different account and verify there is no prior-account action/data. Confirm all ten effective parameter families in Settings and their unapproved/unconfigured states. Deployed browser/auth/network and physical handoffs remain unverified locally.

## Safe dependencies for subsequent work

Subsequent tasks can reuse the existing admin grouping/return/relay actions and the guarded merchant commission route, audited exact readback and unchanged frozen financial records. They cannot assume provider execution, an approved refund deadline, approved subscription/referral benefits, new category commission precedence or newly approved delivery/relay tariffs. The explicitly uncommitted Task 16 decision note and unrelated workspace changes remain outside these commits.

## Changed files and focused commits

API commit `405198b` records commission guards/audit/migration and regressions. API commit `a2380b3` records relay confirmation/history/retry behavior and regressions. Admin commit `6716e7a` records grouping/return confirmations and stale handling. Admin commit `de429e6` records the commission editor and authenticated integration/relay link. This report is committed separately. No changes were pushed.

Under `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api`:

- [src/main/kotlin/dev/orestegabo/sequo_api/domain/commission/MerchantCommissionAudit.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/commission/MerchantCommissionAudit.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/commission/MerchantCommissionConfiguration.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/commission/MerchantCommissionConfiguration.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/commission/MerchantCommissionController.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/commission/MerchantCommissionController.kt)
- [src/main/resources/db/migration/V52__merchant_commission_audit.sql](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/resources/db/migration/V52__merchant_commission_audit.sql)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/commission/MerchantCommissionAdminHttpTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/commission/MerchantCommissionAdminHttpTest.kt)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/commission/MerchantCommissionConfigurationServiceTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/commission/MerchantCommissionConfigurationServiceTest.kt)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/commission/MerchantCommissionControllerTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/commission/MerchantCommissionControllerTest.kt)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/order/OrderFulfillmentPersistenceServiceTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/order/OrderFulfillmentPersistenceServiceTest.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/relay/RelayParcelController.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/relay/RelayParcelController.kt)
- [src/main/resources/static/relay-operator.js](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/resources/static/relay-operator.js)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/relay/RelayOperatorHttpTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/relay/RelayOperatorHttpTest.kt)
- [scripts/relay-operator-browser-check.cjs](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/scripts/relay-operator-browser-check.cjs)
- [docs/TASK27_ADMIN_ACTIONS.md](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/docs/TASK27_ADMIN_ACTIONS.md)

Under `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin`:

- [src/ConsolidationPanel.vue](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin/src/ConsolidationPanel.vue)
- [src/ReturnOperationsPanel.vue](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin/src/ReturnOperationsPanel.vue)
- [scripts/consolidation-browser-check.cjs](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin/scripts/consolidation-browser-check.cjs)
- [scripts/return-operations-browser-check.cjs](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin/scripts/return-operations-browser-check.cjs)
- [src/App.vue](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin/src/App.vue)
- [src/CommissionOverridePanel.vue](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin/src/CommissionOverridePanel.vue)
- [src/commissionOverrides.ts](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin/src/commissionOverrides.ts)
- [tests/commissionOverrides.test.mjs](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin/tests/commissionOverrides.test.mjs)
- [scripts/commission-override-browser-check.cjs](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin/scripts/commission-override-browser-check.cjs)
