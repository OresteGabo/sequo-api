# Task 28 — Test release runbook

The signed PDF controls scope:11–16 (page2), §9 (page7), Annex A104–140 (pages12–13), and152 (page13). It requires a usable test/demo release and a maximum50-calendar-day reporting period from actual testable delivery. A build, commit, local automated run or this document is not delivery evidence. The PDF and audit findings were read; earlier reports remain implementation locators rather than proof of deployed acceptance.

## Current release and boundaries

Release identifier: **sequo-mvp-rc-20261108-01**. Current work prepares a **LOCAL_FIXTURE** candidate. No separate online test host, secure access reference or actual SEQUO delivery date has been confirmed. Keep those acceptance rows BLOCKED/UNVERIFIED. A separate host is explained in the deliberately uncommitted root `TASK28-START-HERE.md` note.

Customer, Vendor and Rider remain required on Android/iOS. Admin is the existing Vue web app. Relay is the existing API-served `/relay-operator.html` simple web module; a dedicated Hub app and connected locker hardware are not assumed. API automation, local browser fixtures, emulator launches, physical-device journeys and deployed acceptance are separate evidence categories.

Yas and Flooz remain **NOT_CONNECTED** independently. Approved specifications and sandbox access are unavailable. Test-source payment verifiers are never packaged in the runtime; `sequo.payments.simulator.enabled=true` is rejected. A successful signed local fixture callback proves only internal transitions. Refund instructions are REQUESTED/NOT_SUBMITTED, not transferred money. Paid production auto-deployment additionally requires both `AUTO_DEPLOY_ENABLED=true` and `PAID_LAUNCH_APPROVED=true`; leave the latter unset/false until the gates below are verified. No remote variable was changed and nothing was pushed.

## 1. Build identifiable artifacts

Use a reviewed source commit for each repository and record any remaining tracked differences. Build helpers reject unsafe/missing test origins; Android fixture builds use distinct `.fixture` application IDs, and unsigned simulator wrappers are not physical-device/TestFlight releases. Test credentials belong in the authorized environment, never build arguments or release reports.

API, from `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api`:

```sh
sequo_release_revision=$(git rev-parse HEAD)
SEQUO_RELEASE_ID=sequo-mvp-rc-20261108-01 SEQUO_SOURCE_REVISION="$sequo_release_revision" \
  JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline bootJar
```

The JAR includes `META-INF/build-info.properties` under `BOOT-INF/classes`. Authenticated `/actuator/info` can expose the same public identity when `health,info` are enabled. No secret is put in build metadata. For an image, pass the same public `SEQUO_RELEASE_ID` and exact `SEQUO_SOURCE_REVISION` as Docker build arguments, and record the resulting image ID/digest. The production workflow is pinned to its tested SHA, refuses a dirty remote checkout and forwards both identifiers; it does not follow a later untested `main` head.

In each of `Sequo`, `SequoVendor`, `SequoRider`:

```sh
python3 scripts/build-acceptance.py --platform android \
  --environment LOCAL_FIXTURE --api-base-url http://10.0.2.2:55828 \
  --release-id sequo-mvp-rc-20261108-01 --output-dir /tmp/sequo-task28-APP-android
python3 scripts/build-acceptance.py --platform ios \
  --environment LOCAL_FIXTURE --api-base-url http://127.0.0.1:55828 \
  --release-id sequo-mvp-rc-20261108-01 --output-dir /tmp/sequo-task28-APP-ios
```

Replace APP with the appropriate app name. For approved online staging, use `--environment STAGING` and its actual HTTPS origin, rebuild every app and configure approved auth redirect/client IDs. Local HTTP transport allowances are confined to the test artifact; ordinary release manifests/plists remain restricted. Keep the helper's hash-bound `build-target.json`. iOS simulator `.app` packages can be archived with `ditto -c -k --keepParent APP.app APP.zip`; bind the ZIP checksum in a derivative report and retain its executable checksum. Signed physical-device/Android store/iOS distribution builds require their separately authorized signing configuration.

Admin: use `sequo-admin/docs/RELEASE_RECETTE.md` and `scripts/prepare-release.mjs`. Require an explicit API choice; SAME_ORIGIN_FIXTURE is local fixture evidence only. For online staging use its approved HTTPS API origin, configure CORS, serve the dist-only archive over TLS, disable caching for index/release.json and retain hashed asset caching. Verify the visible release ID, `/release.json`, archive SHA and all file hashes after deployment. Run `scripts/verify-release.mjs` against the exact archive; local intercepted fixtures are not real staging calls.

## 2. Package and verify the evidence

`scripts/release_candidate.py` copies only explicitly supplied artifacts/reports. It records source revisions and hashes of acknowledged tracked changes, hashes every migration and artifact, and binds API/admin embedded metadata or mobile hash-bound build reports to the release/source identity. Unknown artifact provenance is labelled UNKNOWN rather than invented. The inventory verifies every file plus the immutable manifest; hashes detect corruption and are not a signature or deployment approval.

```sh
python3 -m unittest discover -s scripts/tests -v
python3 scripts/release_candidate.py prepare \
  --workspace /Users/muhirwagabooreste/AndroidStudioProjects/SequoService \
  --output build/releases/sequo-mvp-rc-20261108-01 \
  --release-id sequo-mvp-rc-20261108-01 --environment LOCAL_FIXTURE \
  --artifact sequo-api.jvm=/absolute/path/API.jar \
  --artifact Sequo.android=/absolute/path/customer.apk \
  --build-report Sequo.android=/absolute/path/customer-build-target.json \
  --artifact sequo-admin.web=/absolute/path/admin.tar.gz \
  --evidence docs/TASK28_ACCEPTANCE_MATRIX.csv
python3 scripts/release_candidate.py verify build/releases/sequo-mvp-rc-20261108-01
```

Add the actual Vendor/Rider/three simulator artifacts and their reports; do not substitute missing artifacts or silently claim all platforms. An acknowledged pre-existing Vendor project change must be explicitly reviewed via `--allow-dirty-component SequoVendor`; its path/hash remains recorded, and it is not staged into this task's commits. Test artifact preparation does not start acceptance and cannot select PRODUCTION mode.

## 3. Deploy only to a confirmed separate test host — currently UNVERIFIED

1. Obtain the authorized test host/project, HTTPS admin/API origins, secure access and secret reference, backup/restore owner and written isolation from production. Confirm whether a previously delivered testable version already has a written delivery date before starting a new tracker. Do not use the documented production host/database as the test target.
2. Use a separate checkout at the verified API commit, separate database and storage/volume namespace. Review migrations V1–V52 and existing seeded data; back up any existing staging database and validate restored backup in a disposable environment first. Never edit/repair applied migration checksums. V52 preserves existing commission overrides and adds audit without fabricating history.
3. On that host create ignored `.env.staging` from `.env.staging.example`; supply unique environment secrets and exact release/revision. Add only approved existing auth/email/WhatsApp configuration slots in that secure file. Leave providers and outgoing workers unconfigured/disabled. Unedited template secrets fail startup. Configure an HTTPS reverse proxy to the loopback-only API port; do not expose PostgreSQL or Adminer.
4. Validate and start the standalone project. It is not a merge into the production Compose project:

```sh
docker compose --env-file .env.staging -f docker-compose.staging.yml config --quiet
docker compose --env-file .env.staging -f docker-compose.staging.yml up -d --build
docker compose --env-file .env.staging -f docker-compose.staging.yml ps
curl --fail http://127.0.0.1:55828/actuator/health/readiness
```

5. Read Flyway success/checksums and Hibernate validation, exact deployed build identity, provider NOT_CONNECTED state, denied unauthenticated admin/relay reads, then authenticated role-scoped reads. Deploy the matching admin archive and install matching app builds. Confirm all devices reach the same API and release. Record health and role results before continuing; health alone is not acceptance.
6. Rollback: stop only the staging project, retain volumes/logs and the failed candidate, redeploy the prior identified API/admin/client artifacts, and use the approved restore plan if schema/data rollback is needed. Do not delete volumes or run reverse migrations to conceal a failure. Keep exact release IDs and database backup references in the restricted operations record.

## 4. Prepare real test accounts securely

Required cohort: two customers; two independent merchant memberships/stores; two rider accounts (with approved independent/SEQUO workforce fixtures); ADMIN; two active relay points with different assigned operators. Use test contacts controlled by the authorized participants. Register/sign in through configured normal auth first. No production contacts, public role-assignment route or hardcoded OTP is introduced.

An authorized staging database operator can bootstrap existing registered identities using the existing `user_roles(user_id,role_code)` and `merchant_memberships(id,user_id,merchant_id,role_code,active,created_at)` schema. Perform this only in the confirmed isolated database, in a reviewed transaction recorded in the secure operations log. Minimal SQL pattern for an already approved identity/store:

```sql
BEGIN;
DO $$ BEGIN
  IF current_database() <> 'sequo_staging' THEN
    RAISE EXCEPTION 'Use the approved isolated staging database only';
  END IF;
END $$;
INSERT INTO user_roles(user_id,role_code)
SELECT id,'MERCHANT_OWNER' FROM users WHERE id=:'merchant_user_id'
ON CONFLICT DO NOTHING;
INSERT INTO merchant_memberships(id,user_id,merchant_id,role_code,active,created_at)
SELECT gen_random_uuid()::text,u.id,m.id,'MERCHANT_OWNER',true,current_timestamp
FROM users u CROSS JOIN merchants m WHERE u.id=:'merchant_user_id' AND m.id=:'merchant_id'
ON CONFLICT(user_id,merchant_id,role_code) DO NOTHING;
-- Verify exactly the approved IDs/roles/membership before COMMIT; otherwise ROLLBACK.
```

Provision other approved roles/relay assignments through the same existing schema and separately review rider-profile/workforce linkage. Reauthenticate after provisioning; verify `/api/auth/me`, merchant membership and assigned relay reads. Keep account-to-contact mappings, credentials and tokens in secure staging configuration, not this bundle. Without working approved auth delivery/distribution access, real device journeys remain BLOCKED.

## 5. Run Annex A with a common order ID

Use the committed per-requirement matrix. Record release/build, platform/device category, actor role, order/checkout/mission/parcel/return IDs, time, expected/actual state, server readback after refresh/restart, and evidence reference. Never store tokens, PIN values, identity images or provider credentials in the acceptance report. A written blocker prevents an essential signed-MVP journey; icon/font preferences and richer dashboards are separate visual improvements.

1. Customer Android and iOS: normal signup/sign-in/restore, catalogue and real images, basket with distinct products/options, trusted server quote and delivery/relay choice. Invalid options/stock/price change must request review. Negotiate eligible products through separate customer/vendor sessions, exhaustion and valid/expired negotiated checkout. Submit pending checkout/retry; unverified payment must not release fulfillment.
2. Vendor Android/iOS: priced product/description, approved generic image and actual required camera capture, deactivate/reactivate, bargaining toggle and cross-store denial. For the common staged paid-fixture order, read own quantities/options only, accept, prepare and mark packed/ready; repeated taps preserve state. Read sales/return/instruction-versus-settlement results after reload.
3. Rider Android/iOS: dispatch and concurrent acceptance, authorized pickup/proof, wrong/correct PIN, stale/offline/retry and final history. Exercise home delivery and eligible relay deposit/reroute; wrong site/ineligible basket fails. Compare independent payable/SEQUO shortfall with ledger; salaried mission has no independent payout.
4. Admin/relay web: common-order tracking, cooperative approval, two physical package receipts, seal and one final dispatch; early/duplicate/wrong-role actions. Relay credentials/QR/identity confirmation, atomic release, overdue boundaries and explicit decision/seller return, two-site isolation and persisted actor history. Eligible customer return inside/at/outside72h, duplicate/other-customer rejection, relay drop-off/PIN, physical SEQUO receipt/inspection before a single refund instruction. Expire sessions and refresh every role.
5. Settings: all ten Annex A.6 families must show effective values/source/readiness. Unapproved subscription/referral values remain inactive; no historical minimum display or extra Click-and-collect rule is inferred. Delivery zone/ETA values, relay tariff/active-parcel treatment and clause66's missing deadline unit require written decisions where still unresolved.

Local automation can be replayed on a guarded `jdbc:postgresql://127.0.0.1:55434/sequo_task14_*` fixture database using existing `CustomerCartCheckoutHttpTest`, `ReturnCustodyHttpTest` and `MerchantCommissionAdminHttpTest`. Set `SEQUO_RELEASE_EVIDENCE_PATH=/tmp/sequo-task28-cross-app-order.json` only for the grouped-order method to emit redacted common IDs. This is actual server HTTP persistence evidence, not phone UI/provider proof. Runtime test adapters/fixture account seeds are not deployed.

## 6. Record actual delivery and the 50-calendar-day window

Only after SEQUO actually receives a usable identified test/demo version, obtain its written delivery reference, exact timestamp/offset and calendar timezone. Record it once:

```sh
python3 scripts/release_candidate.py record-delivery build/releases/sequo-mvp-rc-20261108-01 \
  --delivered-at ACTUAL_ISO_TIMESTAMP_WITH_OFFSET --timezone ACTUAL_IANA_TIMEZONE \
  --written-reference 'Actual written delivery reference'
python3 scripts/release_candidate.py verify build/releases/sequo-mvp-rc-20261108-01
```

The separate hash-bound receipt records actual local delivery date and date+50 calendar days across DST/leap/year boundaries. Missing/naive/future timestamps and missing written references are refused; an existing receipt cannot be overwritten to reset the clock. Keep written blocking-anomaly reports with reference, date, release, reproduction and essential-journey impact. The tool never declares legal acceptance. No actual-delivery receipt is created by this task without the missing evidence.

## 7. Separate paid-launch gate

Before enabling paid production: approved operator specs/onboarding and authorized credentials; sandbox success/failure/timeout/replay evidence separately for Yas/Flooz; amount/currency/reference/account binding and durable reconciliation; verified refund/payout capability, beneficiary/ledger controls and operational incident/reconciliation ownership; approved business parameters/legacy treatment and deadline decisions; verified migration/backup/restore, signing/distribution, role isolation, essential Android/iOS/web journeys and permitted rollout approval. Independent rider liabilities/instructions must remain distinct from transferred funds. The present local candidate does not satisfy or bypass these gates.
