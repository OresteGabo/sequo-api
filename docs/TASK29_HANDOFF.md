# Task 29 — SEQUO handoff and contract closeout

The handoff documentation is prepared. **The contract checklist remains open:** a local release and passing local tests do not establish delivery, acceptance, payment, transfer rights or legal compliance. No source/account transfer, invitation, secret transfer, external message, deployment or financial operation was performed by Task 29.

Review date: 8 November 2026. Scope authority: [signed source PDF](/Users/muhirwagabooreste/Downloads/contrat_mvp_sequo_signed-2.pdf), SHA256 `b985b2fc6090f75747737e0e1162d7fca876ee8e8955b0e7c41b2dadd08b9ed5`. The [audit](../output/SEQUO-MVP-AUDIT.md) helped locate earlier findings; current source and recorded evidence determine technical status. The PDF's contractual terms and the parties' written decisions determine scope and commercial matters.

## Start here

| Document | What it provides |
| --- | --- |
| [Contract checklist](TASK29_CONTRACT_CHECKLIST.md) | A status, evidence link, action owner and dependency/next action for each of the **56** requested clauses/sections: 7–10, 59, 71–103, 141–153, §9 and §§12–15. |
| [Open items](TASK29_OPEN_ITEMS.md) | **16** remaining items, with owners to appoint, dependencies and closure evidence. This includes Annex B decisions, access gaps and the known security setup issue. |
| [Technical inventory](TASK29_TECHNICAL_INVENTORY.md) | Five source repositories/checkpoints, eight artifact identifiers, build tools/instructions, dependency/asset review, distribution limits and preserved Vendor provenance. |
| [Operations and support handoff](TASK29_OPERATIONS_HANDOFF.md) | Deployment, V1–V52 migrations, backup/restore steps, safe access/secret custody, support intake and the distinction between warranty and maintenance. |
| [Task 28 release report](TASK28_RELEASE_ACCEPTANCE.md), [runbook](TASK28_RELEASE_RUNBOOK.md), [functional matrix](TASK28_ACCEPTANCE_MATRIX.csv) | Existing build/test results, exact reproduction steps and **45** Annex A acceptance rows distinguishing local proof from blocked or unverified checks. |

The chosen relay operator interface is the simple API-served web module `/relay-operator.html`. A dedicated SequoHub app remains conditional on written scope; it is not part of this release. The source inventory includes API, Customer, Vendor, Rider and admin. No sibling repository or runtime code was changed in Task 29.

## Release and evidence available

Candidate: **`sequo-mvp-rc-20261108-01` / `LOCAL_FIXTURE`**. The [manifest](../build/releases/sequo-mvp-rc-20261108-01/release-manifest.json) binds eight artifacts, six mobile build reports, nine evidence reports and the 52 migration source hashes. Manifest SHA256: `61398bffd7cfc1cbd0a380b7c835920ba659ac273d33fd998af9825defa7efda`. The release bundle remains unchanged; later handoff commits do not relabel its recorded runtime revisions.

| Evidence | Recorded result and limit |
| --- | --- |
| Server and database | Task 28 records local automated regressions, PostgreSQL migrations/schema validation and a two-merchant order retained after restart. The final JAR read back the same delivered order under separate customer, merchant, rider and admin fixture identities. This is local HTTP evidence, not a completed human mobile journey. |
| Shared order | `SQ-cart-ecddaff46d9e7cd74504c9b35a14abf6dfd65f6573d3a089a2fafa34f9aa4011`; persisted `DELIVERED`, rider `DELIVERED_TO_CUSTOMER`. See [runtime readback](../build/releases/sequo-mvp-rc-20261108-01/evidence/runtime-readback.json). No credential or handoff PIN is included. |
| Mobile | Customer/Vendor/Rider full host suites passed (17/27/12 tests); six fixture builds installed and cold-launched on fresh Android/iOS targets. APKs are debug-signed and iOS packages are unsigned simulator apps. Physical distribution, authenticated journeys and actual camera/provider flows remain unverified. |
| Admin and relay web | Admin type/build, 31 Node tests and seven intercepted browser fixtures passed; relay browser fixture passed. Deployed HTTPS, normal human role authentication and session-expiry journeys remain unverified. |
| Integrity | Task 29's read-only verifier passed; artifacts/reports remained bound to the manifest. Supplementary [screenshots](../build/releases/sequo-mvp-rc-20261108-01-evidence/evidence-index.json) have a separate checksum inventory. |
| Live money | Yas and Flooz remain **NOT_CONNECTED** individually. Collection/refund/rider-payout capabilities remain disabled. Internal staged events, refund instructions and ledger liabilities do not establish transferred funds. |

Local services and task-owned test devices were stopped after Task 28. Retained local fixtures are not an always-on test service. Reproducing a clean-machine build also requires dependency bootstrap and a decision on Vendor's acknowledged pre-existing Xcode project edit; see [O-REPRO](TASK29_OPEN_ITEMS.md#o-repro).

## What prevents closeout

The [open-item register](TASK29_OPEN_ITEMS.md) is the detailed action list. The following sequence does not impose new commercial terms or authorize spending:

1. The parties confirm the applicable payment/rights conditions, permitted recipient and source/account handoff contents privately. They also confirm open scope, Maps/provider level, policy and legal/operating responsibilities. No actual payment status, entitlement or legal conclusion is inferred from source files.
2. SEQUO appoints the receiving developer, account/security custodian, test lead, hosting/recovery owner and support contact. They arrange approved test access and distribution; no personal credentials are handed over as a shortcut.
3. The technical maintainer and appointed custodian resolve **[O-SEC](TASK29_OPEN_ITEMS.md#o-sec)** before online activation: the frozen candidate lacks admin-login encryption validation/templates; current source fixes this in `0fb7f61`, while remote key continuity and staff-login verification remain open. Existing protected records need a continuity plan before any key change. The frozen candidate contains no fix; an approved fix requires a newly identified build and relevant checks.
4. The authorized team deploys an approved separate test copy, verifies recovery and normal scoped accounts, then executes the blocked device/web journeys from the matrix. Providers need their own official specifications and authorized sandbox evidence before live integration/paid launch can be considered.
5. The parties record actual testable delivery and later validation separately, review written anomalies, and complete the permitted transfer/receipt record below. Continuing maintenance is agreed separately from the signed warranty.

**Dates remain unset.** The user confirmed that no testable delivery has been recorded. The current candidate remains `NOT_DELIVERED`; its maximum **50-calendar-day** reporting period is `NOT_STARTED`. Validation and the warranty start/end are unconfirmed. The signed warranty is **three months from validation**, with covered design/development errors corrected **without additional charge**; it is not 90 days or a clock started by this document or a build. No continuing support agreement or response-time promise is created here.

## Receipt and closeout record — template, not executed

Keep the completed record and sensitive supporting evidence in the parties' restricted handoff file. The repository should contain only permitted nonsecret confirmation references. Do not backdate a receipt or insert a build timestamp in place of delivery.

| Field to confirm | Current state / evidence required |
| --- | --- |
| Parties' authorized representatives and recipient | UNCONFIRMED; private appointment/authority reference. |
| Payment/rights precondition or written alternative | PARTIES_CONFIRM; restricted confirmation and allowed handoff scope, not invoices or credentials in Git. |
| Source checkpoints and permitted contents | PREPARED in inventory; recipient verifies access, authorized Vendor patch/new revision, required notices and excluded personal/third-party elements. |
| Runtime artifacts/environment | LOCAL_FIXTURE candidate above; actual delivered target/builds and read-only identity/checksum proof still required. |
| Organization accounts and secure configuration | NOT_TRANSFERRED; environment, roles, custodian, secure reference, continuity check and recipient access proof. No secret values here. |
| Backup/restore and operational owner | UNVERIFIED for an online target; restricted backup/key references, restored-database evidence and appointed recovery owner. |
| Actual testable delivery | NOT_RECORDED; actual timestamp/offset, timezone, written reference and recipient's usable access evidence. Follow the Task 28 delivery-receipt procedure only when delivery occurs. |
| Written acceptance findings and validation | OPEN; journey evidence, blocking reports/assessment, applicable decisions and separate validation reference/date. |
| Warranty dates and support route | UNCONFIRMED; derive only from the confirmed validation milestone, with support ownership/scope recorded separately. |
| Remaining open items and exclusions | OPEN; each applicable register item needs its named owner and closure evidence or written treatment. A blocked item is not silently converted to a pass. |
| Actual transfer/receipt and contractual sign-off | NOT_PERFORMED/OPEN; both parties record what was authorized, received and confirmed. A generated checklist cannot provide assent. |

## Task 29 verification and acceptance result

This change is documentation-only. Existing Task 28 tests are linked as historical evidence; no runtime suite was repeated, no dependency was upgraded and no release artifact was rebuilt.

| Check / command | Result |
| --- | --- |
| Read signed clauses and reconcile current source/audit findings | Requested scope covered; no financial document or secret configuration inspected. |
| `python3 scripts/release_candidate.py verify build/releases/sequo-mvp-rc-20261108-01` | PASS, `NOT_DELIVERED`; no receipt created or clock started. |
| `python3 - <<'PY'` inline local documentation checker: file/anchor resolution, exact clause set, open-item fields and extracted shell blocks through `sh -n` | PASS; 159 local links/anchors, 56 unique clause/section rows, 16 complete open items and six shell blocks. The snippets are reproduction instructions, not deployment/restore evidence. |
| `git diff --check` and selected-path review | PASS; existing untracked notes/output and sibling changes preserved. |
| Task acceptance: concise per-clause status linked to evidence | PREPARED in the checklist. Technical material and open dependencies are documented; contractual closure remains OPEN. |
| Task acceptance: owner, dependency and next action for every open item | PREPARED in the checklist/register. Appointments and completion remain unconfirmed. |

Files changed: [README](../README.md), [SECURITY](../SECURITY.md), this entry point, [contract checklist](TASK29_CONTRACT_CHECKLIST.md), [open items](TASK29_OPEN_ITEMS.md), [technical inventory](TASK29_TECHNICAL_INVENTORY.md) and [operations handoff](TASK29_OPERATIONS_HANDOFF.md). Existing deliberately uncommitted notes remain uncommitted. Task 29 uses separate documentation commits for the clause/decision review and the technical/operational handoff.

The next task can rely on the immutable candidate identity, linked local evidence, complete requested-clause inventory and explicit open-item register. It must obtain the missing approvals/access and produce new evidence before claiming online readiness, actual handoff, acceptance, payment connectivity, rights transfer or compliance.
