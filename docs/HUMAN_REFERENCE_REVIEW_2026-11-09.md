# Short reference implementation review — 9 November 2026

Follow-up: the four updated apps have since been installed and checked on Android and iPhone simulators. See the [installed-device review and receipts](HUMAN_REFERENCE_DEVICE_REVIEW_2026-11-09.md), which supersede the mobile installation limitation below.

Implemented nine-character display references, grouped `XXX-XXX-XXX`, with a record-type prefix and two checksum characters. The local order **CH1-HXS-621** is available in the admin Orders page. Lowercase, spaces and omitted hyphens normalize correctly. Changing its last character to `2` produces an immediate input error.

The [format and compatibility specification](HUMAN_REFERENCES.md) describes the alphabet, checksum, prefixes, registry, migration and lookup authorization. This is a display/lookup addition. Database keys, business retry codes, provider identifiers, custody credentials and financial state transitions retain their existing values.

## Result

| Check | Result | Evidence |
| --- | --- | --- |
| Same format across API, admin and four native apps | PASS — identical common Kotlin sources, identical TypeScript contract and 19 published vectors | [Verification inventory](evidence/human-references-2026-11-09/verification.json) |
| Detect input errors before lookup | PASS — changed canonical symbols, distinct adjacent swaps, wrong type, length/alphabet and Unicode/control-character cases; client invalid-input tests prove no resolver request | API codec/controller tests; native codec/Hub tests; admin input fixture |
| Correctly typed lookup keeps canonical target | PASS — normalized reference resolves to original ID; stale/failed resolutions clear pending targets; existing domain readback checks remain intact | API scoped HTTP/controller tests; admin nine-case isolated browser fixture; actual admin UI |
| Existing records and retries | PASS — immutable per-kind mapping, forced collision/retry and concurrent claim tests; legacy order business version/time/payment binding unchanged | Registry/core HTTP tests |
| Reference survives server restart | PASS — all **92** observed mappings unchanged; **306/306** checks before restart and **307/307** afterward | [Before](evidence/human-references-2026-11-09/before-restart.json), [after](evidence/human-references-2026-11-09/after-restart.json) |
| Ownership and relay scope | PASS — separate customers, merchants and relay operators; forged relay rejected; assigned foreign return unavailable | Same local evidence and scoped HTTP tests |
| Redacted identities stay redacted | PASS — hidden customer, return actor and cooperative reviewer IDs do not reappear through aliases | Core HTTP and cooperative privacy tests |
| Ledger/payout/source distinction | PASS — ledger W, merchant accrual F, actual mission D; unknown legacy source is not presented as a mission or proof of payment | Core HTTP tests; Rider payout regression; typed admin display tests |
| PostgreSQL migration and normal local startup | PASS — V61 applied to retained isolated database; startup succeeds with bounded compiler heap | Local startup; runtime build identity in verification inventory |
| Existing mobile design source | PASS — all four design-system copies remain identical | `scripts/mobile-design.py` |

The stored local scenarios include received returns at Dede's relay and parcels at Esso's relay. Esso has no assigned return fixture: the PostgreSQL exercise checks Esso's own parcel references and denies cross-site return lookup. The separate HTTP regression creates two eligible operators, resolves one awaiting return at both approved sites, records normal intake at one, then denies the other site. No fixture was added to the retained workspace to manufacture that case.

## Source revisions and changed files

| Repository | Focused implementation commits |
| --- | --- |
| sequo-api | `a7d3101` format/registry/migration; `b180a79` scoped API projections/lookup/privacy regressions; `6033c5e` bounded compiler heap for normal clean local startup |
| sequo-admin | `c741004` verified input/resolver; `cdb33d8` displays/search/workflow integration; `00e42f2` hide technical audit keys |
| Sequo | `52263d6` customer display references |
| SequoVendor | `a013450` merchant display references |
| SequoRider | `62fba13` mission/profile/history references; `ad28b08` distinguish ledger record from mission source |
| SequoHub | `1d52978` relay display references and scoped return input |

The [machine-readable inventory](evidence/human-references-2026-11-09/verification.json) lists the full source hashes and changed paths for each repository. This review, the read-only reproduction script and saved evidence are committed separately after implementation verification.

Primary API additions are the common `HumanReference` codec, `PublicReferenceRegistry`, V61, `PublicReferenceController` and the explicitly allowlisted admin response projection. Existing order/checkout, merchant fulfillment, delivery/assignment, relay, return, consolidation, catalog, cooperative, profile and settlement DTOs gain additive reference fields. Action and replay responses use the same reference mapping as list/detail reads.

The four mobile clients retain internal IDs for navigation and actions and prefer server references for display. Hub return intake validates a new reference locally, resolves it under its current relay, and freezes the original canonical return ID/PIN/retry intent. The explicit older-ID path remains available.

Admin keeps canonical models, routes, authorization and verified readback comparisons. Its cards, tables, searches, selectors, confirmations and human detail views use references. Technical grid/audit/retry keys are hidden from human detail displays. CSV export retains original technical columns and includes the additive server reference fields. Provider event/payment identifiers and operational credentials keep their exact meanings.

## Commands and results

API final regression run, from `sequo-api`: **90 tests, zero failures/errors/skips; boot JAR passed**. [Per-suite counts](evidence/human-references-2026-11-09/api-tests.json).

```sh
./gradlew --offline test \
  --tests 'dev.orestegabo.sequo.reference.HumanReferenceTest' \
  --tests 'dev.orestegabo.sequo_api.domain.reference.*' \
  --tests '*AdminPublicReferenceProjectionTest' \
  --tests '*AdminWebReadHttpTest' \
  --tests '*CustomerCartCheckoutHttpTest' \
  --tests '*MerchantBusinessReadsHttpTest' \
  --tests '*CustomerReturnHttpTest' \
  --tests '*ReturnCustodyHttpTest' \
  --tests '*RelayOperatorHttpTest' \
  --tests '*RiderPayoutVisibilityTest' \
  --tests '*H2V51FlywayCompatibilityTest' \
  --tests '*AdminRelayAdministrationHttpTest' \
  --tests '*ConsolidationPersistenceServiceTest' \
  --tests '*DeliveryFulfillmentWorkflowTest' \
  --tests '*MerchantCommissionAdminHttpTest' \
  --tests '*MerchantStoreHttpTest' bootJar
```

An integration failure caught an omitted optional `legacyId` primitive flag being rejected by strict request binding. The request now treats omission/null as normal short-reference mode and enables older-ID mode only for explicit `true`. Both the omitted-flag HTTP flow and explicit modes passed the final run. A cold JDK 21 local build also exposed Kotlin's insufficient default compiler heap; `gradle.properties` bounds it to 1,536 MB. The normal launcher then built successfully in 21 seconds and restarted successfully.

Each native repository ran the following host task: **168 tests across the four suites, zero failures/errors/skips**. After the final two kind additions, the 19-vector codec tests were rerun in all four apps; the last Rider ledger display change also reran its three payout client tests. These are reruns, not additional unique test counts.

```sh
./gradlew --offline \
  -PsequoApiBaseUrl=http://10.0.2.2:55840 \
  -PsequoReleaseId=sequo-reference-review-20261109 \
  -PsequoEnvironment=LOCAL_FIXTURE :shared:testAndroidHostTest
```

All four apps passed `:shared:compileAndroidMain` with the same parameters and `:shared:compileKotlinIosSimulatorArm64` with `-PsequoApiBaseUrl=http://127.0.0.1:55840`. Rider's final follow-up was recompiled for both targets. These are compilation checks; no new native package installation or device journey is claimed for this change.

Admin: `node --test tests/*.test.mjs` **100 passed**; `npm run type-check` and `npm run build-only` **passed** (68 modules). The isolated Vue browser input fixture passed **nine cases and 19 codec vectors**, including invalid-input no-request behavior, mismatched kind/record, stale response, changed request/field and unmount cases. On this machine its exact command was `NODE_PATH=/opt/homebrew/lib/node_modules/@google/gemini-cli/node_modules node scripts/human-reference-browser-check.cjs`; it uses the already available Playwright runtime and does not authenticate or change the real workspace. The actual local admin was separately exercised through CUA: authenticated refresh, visible references, malformed input message, lowercase/space normalization, reference search without hyphens, server order readback and repeat read after server restart.

Local reproduction, with the already seeded guarded workspace:

```sh
# From sequo-admin; retain the terminal while trying the UI.
npm run dev:local -- --no-browser --with-fixtures

# From sequo-api, in another terminal.
python3 scripts/human-references.py
python3 scripts/mobile-design.py
python3 scripts/check-human-references-local.py \
  --evidence /tmp/sequo-human-reference-before-restart.json

# Stop/restart the same local launcher; do not reset its database.
python3 scripts/check-human-references-local.py \
  --compare /tmp/sequo-human-reference-before-restart.json \
  --evidence /tmp/sequo-human-reference-after-restart.json
```

The checker uses only the fixed loopback workspace. It issues/revokes temporary local sessions and reads records/resolves references; it does not seed/reset business records, issue PINs, change custody or invoke provider operations. Evidence retains no tokens, PINs or private configuration. The local launcher keeps its original guarded synthetic scene setup and does not reset retained operations.

## Limits and next work

- Apply V61 through the existing migration process with the next API deployment. Back up the reference table with domain data so previously shared references remain stable. No business-value approval is needed for this display-only migration.
- Current native source compiles on Android and iOS; full packages containing this change have **not** been installed on simulators/devices. To verify manually, build each app through the existing local release helper, use its guarded local account, open order/mission/relay history and compare the reference with admin. In Hub Returns, try a changed checksum, then the customer's genuine return reference with its separate PIN. Never substitute the display reference for a credential.
- Live provider execution, production rollout and a new formal testable contract delivery are outside this change. Provider gates and the existing frozen Task 28 release remain unchanged.
- Original Customer/Vendor Xcode project edits and all six original Hub edits match their initial hashes. The existing uncommitted decision notes, audit and output artifacts were preserved and excluded from these commits.

Next work can rely on immutable server references, per-kind lookup, checksum validation shared across all clients, legacy compatibility and unchanged canonical action/retry identities. A correct checksum continues to prove transcription only; server existence and authorization checks remain mandatory.
