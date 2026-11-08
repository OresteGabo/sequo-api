# Task 29 — Operations and support handoff

This is a technical handoff record, not a payment, ownership or binding support-coverage determination. The signed source PDF `contrat_mvp_sequo_signed-2.pdf` controls: 59, 71–103 and §§12–15, pages 5–9. Source SHA256: `b985b2fc6090f75747737e0e1162d7fca876ee8e8955b0e7c41b2dadd08b9ed5`; pages 8–9 were also visually checked. The main Task29 handoff checklist owns contractual sign-off. This companion describes who must operate the delivered system and what evidence they need.

The available candidate remains **LOCAL_FIXTURE**, `sequo-mvp-rc-20261108-01`. Its [immutable manifest](../build/releases/sequo-mvp-rc-20261108-01/release-manifest.json) records eight artifacts, six mobile build reports, nine evidence reports and 52 migration hashes. Use the [Task28 result](TASK28_RELEASE_ACCEPTANCE.md), [deployment runbook](TASK28_RELEASE_RUNBOOK.md) and [acceptance matrix](TASK28_ACCEPTANCE_MATRIX.csv) for verification details. A new documentation commit does not replace the runtime revisions recorded by the artifacts.

No actual testable-delivery or validation date is recorded in this handoff. Bundle acceptance is `NOT_DELIVERED` and Task28 tracks its reporting period as `NOT_STARTED`; warranty validation/start/end dates remain **UNKNOWN** and are not calculated here. A real test host, approved account access, physical-device distribution and complete authenticated mobile journeys remain outstanding. Yas and Flooz remain `NOT_CONNECTED`; refund instructions remain distinct from provider transfers.

## Appoint owners before operational transfer

Every operational appointment below is **UNASSIGNED** until the responsible Party records it. Role names do not grant application, infrastructure or financial permissions.

| Role to appoint | Dependency | Action and completion evidence |
| --- | --- | --- |
| SEQUO service owner | Authorized business representative | Confirm environment, approved rollout, business settings, account responsibilities and written sign-off references. |
| Deployment operator | Approved separate host, DNS/TLS and repository access | Deploy the exact candidate; record runtime identity, health and restricted access checks. |
| Database and recovery owner | Approved database, backup destination and recovery-key access | Own backups, retention/access decisions, disposable restore proof and rollback approval. |
| Security/account custodian | Organization-owned accounts and restricted secret store | Approve least privilege, MFA, key continuity and eventual access changes; address **O-SEC** below. |
| SEQUO support coordinator | Agreed private intake channel and escalation route | Maintain written reports and route incidents to the appropriate owner without exposing credentials or customer data. |
| Prestataire technical maintainer | Party-confirmed access and support scope | Explain source/builds and known gaps; assess and correct work under the applicable recorded agreement. |
| Provider integration owner | Separate Yas/Flooz operator onboarding and specifications | Obtain sandbox/capability evidence and incident contacts; keep disconnected operations disabled. |

## Account and access inventory

Keep a restricted inventory with: service, environment, organization/account owner, granted role, MFA/recovery custodian, nonsecret configuration identifier, restricted credential reference, transfer approval and recipient verification. Put no actual credential, recovery code, private key, private vault URL or personal contact mapping in this repository, release bundle or support report.

| Account family | Handoff action / dependency |
| --- | --- |
| Git repositories and CI | Invite authorized recipient accounts through the existing organization/service. Record repository revisions, branch/workflow access and required third-party licences. Do not share a personal login, personal token or SSH private key. |
| Host, registry, DNS and TLS | Confirm who controls the separate staging host and future production environment, renewal responsibilities, deploy permissions and image/artifact retention. No remote access or ownership transfer has been performed by this task. |
| PostgreSQL and backups | Separate application, migration and recovery privileges as approved. Transfer access to the database and its matching protected recovery material, not a database dump in the public evidence bundle. |
| Mobile OAuth and distribution | Confirm the approved app IDs, redirect/client registrations, Android signing and Apple team/provisioning through the app build guides. Prefer organization invitations or supported platform transfer to exporting personal signing material. Unsigned simulator/debug artifacts are not store releases. |
| Auth delivery, email, WhatsApp and notifications | Confirm configured delivery services, permitted test contacts, sending permissions, billing owner and escalation route. The recipient must test the normal approved sign-in path; this task sends no message and provides no OTP bypass. |
| Yas/Flooz and other paid third parties | Use the separate input matrix in [Task24](TASK24_PROVIDER_PREPARATION.md). Account/credential presence does not establish integration, funds availability, settlement or permission to enable a feature. |
| Relay hardware and operational resources | Confirm physical lockers/boxes, site operators and equipment ownership separately. Contract 59 and 71–80 place hardware and other operating costs outside the development bundle unless agreed otherwise; no equipment or continuing service has been purchased here. |

For clause 100/§13, the Parties must confirm the full-payment condition or any written alternative and the permitted handoff contents. Technical hashes, local files and financial-document filenames establish none of those facts. Preserve the stated exceptions for third-party/licensed elements, personal access, private keys, personal secrets and information whose transfer is prohibited or unsafe. Until the relevant confirmation, retain the recorded test/acceptance access boundary. This document neither grants additional rights nor asserts that the condition is satisfied.

## Key continuity and OPEN security item

Do not mandate a fresh key merely because responsibility changes. First inventory the current environment's key versions, consumers and dependent records in the restricted operations log. Then agree an authorized cutover, recovery and session plan; verify it on a disposable restored environment before changing active keys or removing old access.

| Configuration / consumers | Continuity dependency and required action |
| --- | --- |
| `JWT_SECRET` / [JwtService](../src/main/kotlin/dev/orestegabo/sequo_api/domain/auth/JwtService.kt), current and supported legacy sessions | Changing the signing key changes signature validation. Review the actual access/refresh/session paths and planned reauthentication; prove them rather than assuming every session is preserved or revoked. |
| `NOTIFICATION_TOKEN_ENCRYPTION_SECRET` / [FcmTokenProtector](../src/main/kotlin/dev/orestegabo/sequo_api/domain/notification/FcmTokenProtector.kt) | Existing protected FCM tokens require the matching encryption key. A database backup without its approved recovery-key access is insufficient. |
| The same notification secret / [RelayCustomerPickup](../src/main/kotlin/dev/orestegabo/sequo_api/domain/relay/RelayCustomerPickup.kt) and [CustomerReturnPolicy](../src/main/kotlin/dev/orestegabo/sequo_api/domain/returns/CustomerReturnPolicy.kt) | The key also derives customer relay numeric/QR credentials and return PINs. Check existing issued credentials and persisted hashes; any approved reissue/migration must preserve custody history and site/customer scope. Do not blindly recompute or rewrite old records. |
| `sequo.admin-auth.code-encryption-secret` / [AdminLoginCodeProtector](../src/main/kotlin/dev/orestegabo/sequo_api/domain/auth/AdminLoginCodeProtector.kt) | Pending staff login codes are encrypted with this key. Inventory the effective key and pending ciphertext before an approved change; do not publish or rewrite either. **O-SEC remains OPEN**, as described below. |
| Database, provider and auth-delivery credentials | The appointed custodian must verify the permitted account, environment and recipient access. Revocation/rotation timing requires an approved continuity plan; do not remove the only working recovery path. Provider workers/capabilities remain disabled pending their separate gates. |

Current source adds the admin-code guard/configuration fix in `0fb7f61`; see [the updated open item](TASK29_OPEN_ITEMS.md#o-sec). Current-key continuity and a permitted online staff-login check remain open.

**Frozen Task29 evidence — the technical/configuration guard gap was open at that snapshot.** `AdminLoginCodeProtector` has a development fallback for its encryption property. The checked-in environment templates do not cover this slot, and [strict startup guardrails](../src/main/kotlin/dev/orestegabo/sequo_api/config/ProductionStartupGuardrails.kt) validate JWT/notification keys without validating this admin-code key. The frozen candidate has not fixed that gap and must not be labelled production-ready. Owner: Prestataire technical maintainer plus the appointed security/account custodian (**UNASSIGNED**). Dependency: restricted inventory of the effective current key and pending encrypted codes. Next action: approve a continuity-preserving cutover, add and test strict validation and explicit deployment configuration documentation, then verify the normal staff login flow before online activation. No key, guard, template or ciphertext was changed in Task29.

The existing [environment templates](../.env.staging.example), [production-like configuration](../src/main/resources/application-prod.yml) and startup checks are implementation references, not a complete secret inventory or integration approval. Never enable development defaults to make an online deployment pass validation.

## Reproduce and deploy the identified candidate

Run the existing read-only verifier from `sequo-api`; it checks the immutable bundle and its bound artifact/report bytes:

```sh
python3 scripts/release_candidate.py verify build/releases/sequo-mvp-rc-20261108-01
```

Do not prepare over that bundle, relabel a later working-tree HEAD as its runtime source, or copy secret environment files into a source snapshot. Read the source revisions and acknowledged tracked changes from its manifest. Mobile and admin rebuild/install commands are maintained in the Task28 runbook and per-app guides; rebuild against an explicitly approved HTTPS staging origin before using a nonlocal test host.

On the approved separate test host, the deployment operator must confirm the directory, database and standalone `sequo-staging` namespace first. Create `.env.staging` from the template only if absent; populate it through the approved secret mechanism and address O-SEC before online activation. These are reproduction instructions, **not executed remote deployment evidence**:

```sh
if [ ! -e .env.staging ]; then
  (umask 077; cp .env.staging.example .env.staging)
fi
docker compose --env-file .env.staging -f docker-compose.staging.yml config --quiet
docker compose --env-file .env.staging -f docker-compose.staging.yml up -d --build
docker compose --env-file .env.staging -f docker-compose.staging.yml ps
curl --fail http://127.0.0.1:55828/actuator/health/readiness
```

Use the approved configured port if it differs from the template. Configure TLS/admin origin/CORS and auth registrations through the Task28 runbook. Keep PostgreSQL private. Keep outgoing workers and disconnected provider actions disabled; `config --quiet` is not a security or paid-launch approval. Production CI is a separate path with both `AUTO_DEPLOY_ENABLED` and `PAID_LAUNCH_APPROVED` gates in [ci-cd.yml](../.github/workflows/ci-cd.yml); this task changes neither.

Before serving users, record the exact runtime identity, provider capability state, authenticated allowed/denied role reads, merchant membership, rider user/profile/workforce linkage and relay-site assignments. Recipient accounts must use normal authentication and prove their own authorized reads after refresh. No account is made an administrator or site operator by this checklist.

## Database migration, backup and recovery

The manifest records V1–V52; the [migration directory](../src/main/resources/db/migration) and packaged API are tied to their frozen runtime source. Review the target database's Flyway version/checksums before deployment, then record Flyway success and Hibernate schema validation. Do not edit an applied migration, run `repair` to hide a mismatch, use `ddl-auto=update/create`, or backfill financial/custody history just to make an acceptance screen look complete. Legacy review-required records and effective business settings need the decisions identified in [Task21](TASK21_EFFECTIVE_PARAMETERS.md).

The recovery owner must approve backup access, encryption/key custody, retention, recovery objectives and a restore destination. None is invented here. Current camera payloads are PostgreSQL `bytea` records in [CatalogCameraMedia](../src/main/kotlin/dev/orestegabo/sequo_api/domain/catalog/CatalogCameraMedia.kt); preserve them with the database. Inventory any deployment-specific external assets separately rather than assuming a configured object store.

For an already approved isolated staging deployment, a restricted backup and a new disposable restore database can be exercised as follows. The file must be outside the release/repository in the owner's approved protected location; no password is put in the command line. Do not run this against an unconfirmed host/database or reuse an existing restore database:

```sh
(
 set -eu
 umask 077
 : "${SEQUO_BACKUP_FILE:?Set an approved protected backup path outside this repository}"
 test ! -e "$SEQUO_BACKUP_FILE"
 set -C
 docker compose --env-file .env.staging -f docker-compose.staging.yml exec -T postgres \
   sh -c 'exec pg_dump --format=custom --username="$POSTGRES_USER" --dbname="$POSTGRES_DB"' \
   > "$SEQUO_BACKUP_FILE"
 test -s "$SEQUO_BACKUP_FILE"
 shasum -a 256 "$SEQUO_BACKUP_FILE"
 docker compose --env-file .env.staging -f docker-compose.staging.yml exec -T postgres \
   sh -c 'exec createdb --username="$POSTGRES_USER" --template=template0 sequo_restore_task29'
 docker compose --env-file .env.staging -f docker-compose.staging.yml exec -T postgres \
   sh -c 'exec pg_restore --username="$POSTGRES_USER" --dbname=sequo_restore_task29 --no-owner --no-privileges --exit-on-error' \
   < "$SEQUO_BACKUP_FILE"
 docker compose --env-file .env.staging -f docker-compose.staging.yml exec -T postgres \
   sh -c 'exec psql --username="$POSTGRES_USER" --dbname=sequo_restore_task29 -X -v ON_ERROR_STOP=1 -c "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;"'
)
```

`createdb` must fail if that disposable name already exists; do not delete or overwrite it to force a rerun. A failed/empty dump is not recovery evidence. Retain the backup hash, source database identity/version, matching restricted key reference, command outcomes and restore owner in the private operations record. Keep dump contents and personal records out of the release report.

Next, boot the frozen API only against the isolated restored database using reviewed staging configuration, no outgoing providers/workers and no published production endpoint. Verify schema, stored IDs/history, encrypted-data continuity, normal scoped sessions and a readback after restart. Report metadata/counts and redacted references, not decrypted tokens, PINs or customer records. This target-specific backup/restore drill is **UNVERIFIED** until performed by the appointed owner; prior local fixture/restart tests do not prove recovery of an actual operational backup.

If deployment fails, stop only the affected staging API, retain its logs/volumes and candidate, and assess schema compatibility before selecting the prior identified runtime artifacts. A database restore/cutover needs the owner's approved plan and reconciliation of intervening writes. Do not delete volumes, reverse applied migrations, erase financial/audit records or silently run an old binary against an incompatible schema. Record the recovery result before reopening access.

## Support intake and transition

The support coordinator records: written reference and report time, release/build and environment, platform/role, essential journey affected, expected/actual result, minimum reproduction, relevant redacted order/checkout/mission/return IDs, server state after refresh/restart, and evidence reference. Restrict access to sensitive diagnostics. Exclude credentials, tokens, OTP/PIN values, private keys, identity images, complete contact data and raw provider payloads. Use the approved channel; this task sends no report to another person.

| Track | Operational handling and dependency |
| --- | --- |
| Acceptance blocking anomaly (§9, §12/99) | Compare the essential journey with the agreed scope; retain the written report and Prestataire assessment. The maximum 50-calendar-day reporting window is anchored to actual testable delivery, not a build, commit or this document. Task28 provides the separate delivery-receipt procedure. |
| Warranty (§15) | Retain the recorded validation reference/date. The stated period is **three calendar months from validation**, with covered design/development errors corrected **without additional charge**, distinct from maintenance; do not substitute 90 days or start it at delivery/build time. Reports require assessment under this separate provision. Validation, start and end dates remain unknown here. |
| Operations, maintenance or change (§14; 78–80) | Daily support, long-term hosting, maintenance and evolution need the separately agreed owner/scope. A Party-approved triage outcome and any follow-up agreement are recorded; this document sets no response-time SLA or automatic billing category. Do not assume every post-validation issue is paid maintenance while §15 may apply. |

The Parties confirm any binding classification. No invoice/receipt filename, source access, elapsed test duration or technical inventory proves payment completion, legal acceptance or warranty eligibility. The support record should distinguish an application defect, an unavailable third-party capability, a configuration/ownership dependency and a new request without concealing unresolved blockers.

## Completion evidence still required

| Item | Current technical status / evidence to obtain |
| --- | --- |
| Appointed operators and support route | UNASSIGNED; record appointments and recipient access proof. |
| Safe account/secret transfer | NOT PERFORMED; retain restricted approvals/references and key-continuity checks; resolve O-SEC before online activation. |
| Separate online staging and recovery | UNVERIFIED; obtain host/access, approved backup/key custody, restored-database proof and scoped readbacks. |
| Actual delivery and validation | NOT RECORDED; obtain actual written references/dates without manufacturing a clock or a contractual conclusion. |
| Continuing support/maintenance | UNCONFIRMED; record the Parties' separate agreement, if any, while preserving the distinct warranty track. |
| Third-party/signing/hardware inputs | OPEN as applicable; the development artifacts do not pay costs, convey personal credentials or supply equipment. |

Task29 handoff preparation is documentation-only. It performs no deployment, role assignment, data migration, key transfer/rotation, external message, provider execution or financial-history rewrite. Bundle verification and link checks are recorded by the main Task29 review; no broad tests or remote access are needed for this file.
