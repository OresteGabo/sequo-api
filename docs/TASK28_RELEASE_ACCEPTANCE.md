# Task 28 — Release and acceptance result

**LOCAL RELEASE CANDIDATE VERIFIED; REMOTE ACCEPTANCE REMAINS BLOCKED.** The identified release is `sequo-mvp-rc-20261108-01`, labelled LOCAL_FIXTURE. Three Android APKs, three unsigned iOS simulator packages, the API JAR/image and admin archive were built. All three apps cold-launched on fresh Android 36 and iOS 18.6 targets. Customer iOS guest browsing displayed products/prices matching the local API. Full authenticated mobile journeys, physical-device/distribution releases and a separate online deployment remain unverified.

The user confirmed **no testable delivery has been recorded**. The contractual 50-calendar-day period remains **NOT_STARTED**. No actual-delivery receipt/date/deadline or legal acceptance was fabricated. Neither Yas nor Flooz is connected; collection/refund/rider-transfer capabilities remain false in the verified release. No production deployment, repository push, real payment or message to a test participant was performed.

Start with the deliberately uncommitted [plain-language note](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/TASK28-START-HERE.md). Developer setup and exact external reproduction steps: [runbook](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/docs/TASK28_RELEASE_RUNBOOK.md). Per-requirement result: [45-row acceptance matrix](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/docs/TASK28_ACCEPTANCE_MATRIX.csv).

## Signed scope and evidence boundaries

Read the signed PDF `/Users/muhirwagabooreste/Downloads/contrat_mvp_sequo_signed-2.pdf`:11–16 page 2; §9 page 7; 104–140 pages 12–13; 152 page 13, and the corresponding audit findings. The signed source controls scope. API, Customer, Vendor, Rider, admin and the selected simple relay web module are included. A dedicated Hub application, connected locker hardware and new generic operational controls are not assumed. PDF SHA256: b985b2fc6090f75747737e0e1162d7fca876ee8e8955b0e7c41b2dadd08b9ed5.

Existing runtime/diffs were inspected first. Task27 admin/domain workflows were reused. Concrete release gaps fixed were inconsistent hardcoded mobile API origins (including separate Rider auth), missing identifiable release artifacts/configuration, missing explicit Rider release INTERNET permission, staging isolation/placeholder rejection, a future deployment following untested latest main, an outdated account test's implicit relay assignment, and Rider iOS title clipping on a notched screen. Normal shipping defaults/branding/localization and existing financial/custody history were retained.

## Artifact identities

The immutable [release manifest](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/build/releases/sequo-mvp-rc-20261108-01/release-manifest.json) contains 8 actual artifacts, 6 hash-bound mobile reports and 9 evidence reports/matrices: 23 hashed files plus manifest/checksum inventory. Manifest SHA256: 61398bffd7cfc1cbd0a380b7c835920ba659ac273d33fd998af9825defa7efda. Bundle verification passes and acceptanceState remains NOT_DELIVERED. Hashes establish recorded bytes, not a signature, provider connection or rollout permission.

Runtime source is frozen at each artifact's actual commit. API artifacts use `96d89dda242f481258bf3c3e4984dd592977ade6`; the latest packaging verifier uses `b8ef03a` and its recorded script hash. A detached temporary API checkout at 96d89dd prevented later evidence/tool commits being misrepresented as the artifact source. The API checkpoint is still available in Git after temporary checkout cleanup.

| Artifact | Source commit | SHA256 |
| --- | --- | --- |
| [sequo-api.jvm](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/build/releases/sequo-mvp-rc-20261108-01/artifacts/sequo-api/jvm-sequo-api-0.0.1-SNAPSHOT.jar) | `96d89dda242f` | `d1110b0d5ea12ac5b7d627adc84a427d99c580ad6bdb10acea92140e155fa43b` |
| [Sequo.android](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/build/releases/sequo-mvp-rc-20261108-01/artifacts/Sequo/android-Sequo-sequo-mvp-rc-20261108-01-LOCAL_FIXTURE.apk) | `1bccb1e64337` | `5f324b80d14b815c3ab5739a7a440f8d80cb97d83686e29d25c3c973dbdf4191` |
| [Sequo.ios_simulator](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/build/releases/sequo-mvp-rc-20261108-01/artifacts/Sequo/ios_simulator-Sequo-sequo-mvp-rc-20261108-01-LOCAL_FIXTURE.zip) | `1bccb1e64337` | `da81be879f86cb12f24ae0b6958eae33e5be4652f5ea71b88e60ccd891839de9` |
| [SequoVendor.android](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/build/releases/sequo-mvp-rc-20261108-01/artifacts/SequoVendor/android-SequoVendor-sequo-mvp-rc-20261108-01-LOCAL_FIXTURE.apk) | `3d9b3ad62ec4` | `d059186ca93c919f21658ea3c86acafbfceb1a664c6026c27f53780aa6184b93` |
| [SequoVendor.ios_simulator](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/build/releases/sequo-mvp-rc-20261108-01/artifacts/SequoVendor/ios_simulator-SequoVendor-sequo-mvp-rc-20261108-01-LOCAL_FIXTURE.zip) | `3d9b3ad62ec4` | `9f9cde75ac487afd9d0a37741918352dbd284cbdc4d095cfcd2febab04487a83` |
| [SequoRider.android](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/build/releases/sequo-mvp-rc-20261108-01/artifacts/SequoRider/android-SequoRider-sequo-mvp-rc-20261108-01-LOCAL_FIXTURE.apk) | `e2ce24c2e227` | `64d46ebea1c408999f9e9d6e19a58ae2042ac6f0a0eba7aea8ff85ac80cd7e6d` |
| [SequoRider.ios_simulator](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/build/releases/sequo-mvp-rc-20261108-01/artifacts/SequoRider/ios_simulator-SequoRider-sequo-mvp-rc-20261108-01-LOCAL_FIXTURE.zip) | `e2ce24c2e227` | `58ba81c4c2f1016d953358a7164cd83063fe86d56fe68bebbca57449d479b7cb` |
| [sequo-admin.web](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/build/releases/sequo-mvp-rc-20261108-01/artifacts/sequo-admin/web-sequo-admin-sequo-mvp-rc-20261108-01-cfc65e75e2ec.tar.gz) | `cfc65e75e2ec` | `f9d1d67341c327d475f05fe66cdfa6a788e3bc9288bf41bbfba9972807148906` |

Docker image ID: `sha256:ff87d4054fddb6f360063ce2b0a715931fb367f76463567f5046050413721ba5`. Its actual container JAR source/readiness/migration/provider readback is in [container evidence](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/build/releases/sequo-mvp-rc-20261108-01/evidence/sequo-task28-container-release.json). API JAR and container each record their own hash/layout; the verifier accepts the observed root or BOOT-INF build-info resource and refuses ambiguous identity metadata.

Android artifacts are debug-signed `.fixture` packages, version 1/1.0-sequo-mvp-rc-20261108-01, targeting `http://10.0.2.2:55828`. iOS artifacts are full unsigned arm64 simulator wrappers, minimum iOS 18.2, with `.fixture` bundle identifiers and `http://127.0.0.1:55828`. They are not physical/TestFlight/Play distribution releases. Common release/environment/server metadata is visible in app settings. The admin archive is dist-only, clean committed source and explicit SAME_ORIGIN_FIXTURE mode; release.json and every built file were checked before/after seven browser fixtures. Relay remains API-served `/relay-operator.html`.

Customer source is clean at 1bccb1e; Rider clean at e2ce24c; admin clean at cfc65e7. Vendor at 3d9b3ad retains only the pre-existing Xcode project edit, SHA256 `a7f7b50149460a7df096ea86593ca2644a0d463d0add8e59f42cd0c1c16e3af9`, explicitly captured in provenance and excluded from commits. Customer's sole pre-existing test edit was a stray space in a function name; the exact one-character repair restored the complete file to HEAD bytes without staging it. Its normal full suite then passed with no external exclusion/init script.

## Acceptance checks

| Check | Result |
| --- | --- |
| Traceable test release/migrations/safe config | PASS locally: exact source/artifact hashes, clean/acknowledged dirty provenance, strict staging profile, separate project/volume, PostgreSQL 16 container and V1–V52 success, Hibernate validation and readiness. Remote hosting/access remains BLOCKED. |
| Role accounts/common order | PASS for actual local authenticated HTTP fixture accounts and server persistence; human staging sign-ins/account provisioning remain BLOCKED. |
| Android/iOS required apps | PASS build and cold launch for all six targets; Customer iOS guest public catalog PASS. Essential authenticated multi-role app journeys and physical/store releases remain UNVERIFIED. Android guest browse and device failure UI were not exercised. |
| Web admin/relay | PASS local type/build, 31 admin tests, 7 admin browser fixtures plus relay browser fixture and release identity/integrity. Real deployed authenticated web journeys remain UNVERIFIED. |
| Annex A per-requirement result | 45 separate rows preserve PASS/CONDITIONAL/BLOCKED and evidence level. A shared missing test-host/auth setup gates many rows; local checks are not labelled deployed acceptance. |
| 50-calendar-day period | PASS tracker preparation: 9 integrity/calendar tests include DST/leap/year boundaries, future/naive/missing evidence refusal, complete checksums and receipt binding. User confirms no actual delivery; clock NOT_STARTED. |
| Paid-launch gate | Both providers NOT_CONNECTED; customerCheckoutEnabled/refundEnabled/courierPayoutEnabled=false in actual local container. Runtime simulator rejected. Source CI requires both auto-deploy and paid-launch approval and pins tested SHA; no remote GitHub setting/production state was changed or asserted. |

### Common order evidence

Order `SQ-cart-ecddaff46d9e7cd74504c9b35a14abf6dfd65f6573d3a089a2fafa34f9aa4011` and checkout `cart-ecddaff46d9e7cd74504c9b35a14abf6dfd65f6573d3a089a2fafa34f9aa4011` belong to the same local paid-in-fixture two-merchant journey. Two merchant identities prepared their own quantities, two riders collected packages, admin recorded both physical SEQUO receipts, sealed and dispatched one final mission `e5a5349c-e900-49c2-93a1-00d7ef1a83c2`. Final order state DELIVERED and rider state DELIVERED_TO_CUSTOMER were read through customer, both merchant, rider and admin routes from the final packaged 96d89dd JAR after the test JVM stopped. Grouping actor history remained visible.

[Redacted common IDs](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/build/releases/sequo-mvp-rc-20261108-01/evidence/sequo-task28-cross-app-order.json) and [packaged readback](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/build/releases/sequo-mvp-rc-20261108-01/evidence/runtime-readback.json) contain no token, PIN or customer contact. The original journey ran under the earlier code checkpoint c5704ce; the final artifact readback confirms persistence across process/source-metadata changes. This is server HTTP evidence. Authenticated phone journeys and provider payment proof remain unverified.

## Commands and results

API commands from `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api`:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test \
  --tests '*CustomerCart*Test' --tests '*CustomerReturn*Test' --tests '*ReturnCustody*Test' \
  --tests '*MerchantCatalog*Test' --tests '*MerchantBusiness*Test' --tests '*MerchantCommission*Test' \
  --tests '*Bargaining*Test' --tests '*FoodCustomization*Test' --tests '*Consolidation*Test' \
  --tests '*RelayOperatorHttpTest' --tests '*RelayDeadlinesTest' --tests '*RiderPayoutVisibilityTest' \
  --tests '*SettlementJourneyHttpTest' --tests '*AdminBusinessParameterInventoryHttpTest' --tests '*AdminWeb*Test' \
  --tests '*PaymentReadinessConfigurationTest' --tests '*PaymentSimulationGuardTest' \
  --tests '*ProductionStartupGuardrailsIntegrationTest'
# PASS: 116 baseline checks, zero failures/errors/skips.

SEQUO_RELEASE_ID=sequo-mvp-rc-20261108-01 SEQUO_SOURCE_REVISION=c5704ce4988964c5f86f4b3c2f5bed9e1b0557ac \
SEQUO_RELEASE_EVIDENCE_PATH=/tmp/sequo-task28-cross-app-order.json \
SEQUO_RELAY_JOURNEY_TEST_URL=jdbc:postgresql://127.0.0.1:55434/sequo_task14_release28 \
SEQUO_COMMISSION_ADMIN_TEST_URL=jdbc:postgresql://127.0.0.1:55434/sequo_task14_release28 \
SEQUO_RETURN_CUSTODY_TEST_URL=jdbc:postgresql://127.0.0.1:55434/sequo_task14_release28 \
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test --rerun \
  --tests '*CustomerCartCheckoutHttpTest' --tests '*MerchantCommissionAdminHttpTest' \
  --tests '*ReturnCustodyHttpTest' bootJar
# PASS: 27 PostgreSQL tests, exact common-order JSON and schema/migration validation.

JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test \
  --tests '*AccountSessionHttpTest' --tests '*ProductMediaPolicyServiceTest' \
  --tests '*ProductionStartupGuardrailsIntegrationTest' --tests '*PaymentSimulationGuardTest' \
  --tests '*PaymentReadinessConfigurationTest'
# Final PASS: 31 checks. Initial stale relay fixture failed once; corrected to explicit assignment while guessed-ID denial remains.

python3 -m unittest discover -s scripts/tests -v
# PASS: 9 release tool tests.
python3 scripts/release_candidate.py verify build/releases/sequo-mvp-rc-20261108-01
# PASS; NOT_DELIVERED, no clock started.
NODE_PATH=/opt/homebrew/lib/node_modules/@google/gemini-cli/node_modules node scripts/relay-operator-browser-check.cjs
# PASS selected relay web fixture.
```

API final build used common releaseID and actual96d89dd source from Git for `./gradlew --offline bootJar` and Docker build arguments. Container startup/readiness/provider and packagedJAR readback were checked on loopback only. Exact build commands/logs are preserved as `/tmp/sequo-task28-{baseline-api,postgres-acceptance,auth-media-safety,final-api-build,final-docker-build,relay-browser}.log`, plus `/tmp/sequo-task28-runtime/api.log`, deployment/container verification and 8-artifact manifest. Final XML snapshots are `/tmp/sequo-task28-{baseline,postgres,auth-media-safety}-results/`.

Admin: `npm run type-check`, explicit-target production build,31 Node tests and all7 existing browser scripts against one hash-verified archive PASS. Customer/Vendor/Rider: each acceptance helper built `--platform android`/`--platform ios` with explicit LOCAL_FIXTURE targets and common ID. Normal full host suites PASS: Customer 17, Vendor 27, Rider 12. Helpers/Gradle reject missing/unsafe/nonlocal HTTP/credential/query targets; normal release transport remains restricted. APK manifest/package/version/INTERNET and full unsigned Xcode wrappers were inspected. App commands/results and limitations are in the bundled [Customer/Vendor report](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/build/releases/sequo-mvp-rc-20261108-01/evidence/sequo-task28-customer-vendor-report.json) and [Rider report](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/build/releases/sequo-mvp-rc-20261108-01/evidence/sequo-task28-rider-verification.json); admin's bundled report records 31 tests/seven browser scripts.

## Blockers, resolved findings and visual improvements

**External/decision blockers:** no confirmed separate online test host/access or approved real test sign-in cohort; OAuth/test-client registration and auth delivery for test bundle IDs; physical/distribution signing/access; Yas/Flooz official material/sandbox capabilities; pending approved delivery zone/ETA/relay tariff/legacy treatment; clause 66's missing refund-deadline unit. Subscription/referral production benefits remain inactive as requested. No extra historical-minimum display, category commission precedence or Click-and-collect treatment was invented.

**Resolved local findings:** one stale account test assumed a relay user's ID granted site membership; fixture now explicitly assigns the site and still denies guessed IDs. Customer test's single accidental space restored to HEAD. Rider iOS title clipping corrected by honoring container safe areas while ignoring keyboard only. Vendor ordinary label restored to its existing string resource. Actual container JAR metadata uses a different valid resource layout from the host artifact; verifier now handles both and rejects ambiguity. An initial unpublished image attempt with an incorrectly typed source argument was stopped; final builds derive the revision directly from Git.

**Environment/retry evidence:** the fresh Android 36.1 emulator failed inside platform CertificateTransparencyJob (`stableCount <0`); a fresh Android 36 target worked without weakening trust/security settings. Rider's first start timed out under build load, settled healthy, and an unchanged repeated cold launch passed. These are recorded separately from app journey outcomes.

**Deferred visual improvement:** network-isolated admin capture shows missing external Google MaterialSymbols font, producing visible icon ligature text. Text controls remain usable; this is separate from hosting/auth/payment blockers. No font redesign was added. Real camera capture/device image display, Android guest catalog, failure-state UI and access-log attribution for the iOS guest GET remain unverified; exact reproduction is in the runbook/role reports.

## Local resources and next safe dependency

Task-owned JAR on port 55828, PostgreSQL on port 55434, Compose project `sequo-task28-local` on port 55830 and fresh emulator/simulator processes were stopped after verification. Local fixture database/container volumes and created target data were retained; no other devices/services or production database was stopped. Temporary secret configuration stays outside Git/bundle with restricted permissions. For local container restart use the retained `/tmp/sequo-task28-container.env` with the runbook's Compose command and project `sequo-task28-local`; online staging requires separately approved configuration. No credentials are printed here.

Ten scoped screenshots are preserved outside the core bundle under [supplementary evidence index](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/build/releases/sequo-mvp-rc-20261108-01-evidence/evidence-index.json) with their own SHA256SUMS, including six cold launches, Rider demo identity, Customer iOS public catalog, admin identity and mocked relay history. Screenshot evidence kinds are explicit; no local demo screenshot proves an authenticated order journey.

Subsequent work can use the 8 identified artifacts, source checkpoints, tested staging configuration/migrations, per-role server evidence, explicit target build helpers, 45-row matrix and unstarted 50-day tracker. It must still obtain approved host/auth/signing access and run the full signed-in Android/iOS/web journeys. Live provider execution and paid rollout need the separate approved provider/operational gates. The plain-language Task28 note and earlier Task16 decision note remain intentionally uncommitted. Existing audit/output/tmp work and Vendor project changes were not staged.

## Changed files and commits

API code/runtime commits: c5704ce (isolated staging/identity), 5beb665 (tested-SHA deployment), 4c0fb66 (bundle/tracker/runbook), 96d89dd (explicit relay account fixture), b8ef03a (actual JAR layout verification). Customer 1bccb1e; Vendor 03bde5d +3d9b3ad; Rider a122c14 +e2ce24c; admin cfc65e7. This final matrix/evidence report is committed separately. Nothing was pushed.

Under `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api` (20 files):

- [.dockerignore](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/.dockerignore)
- [.env.staging.example](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/.env.staging.example)
- [.github/workflows/ci-cd.yml](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/.github/workflows/ci-cd.yml)
- [.gitignore](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/.gitignore)
- [CI_CD.md](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/CI_CD.md)
- [DEPLOYMENT.md](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/DEPLOYMENT.md)
- [Dockerfile](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/Dockerfile)
- [build.gradle.kts](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/build.gradle.kts)
- [docker-compose.staging.yml](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/docker-compose.staging.yml)
- [docker-compose.yml](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/docker-compose.yml)
- [docs/TASK28_ACCEPTANCE_MATRIX.csv](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/docs/TASK28_ACCEPTANCE_MATRIX.csv)
- [docs/TASK28_RELEASE_ACCEPTANCE.md](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/docs/TASK28_RELEASE_ACCEPTANCE.md)
- [docs/TASK28_RELEASE_RUNBOOK.md](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/docs/TASK28_RELEASE_RUNBOOK.md)
- [scripts/release_candidate.py](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/scripts/release_candidate.py)
- [scripts/tests/test_release_candidate.py](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/scripts/tests/test_release_candidate.py)
- [src/main/kotlin/dev/orestegabo/sequo_api/config/ProductionStartupGuardrails.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/config/ProductionStartupGuardrails.kt)
- [src/main/resources/application-staging.yml](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/resources/application-staging.yml)
- [src/test/kotlin/dev/orestegabo/sequo_api/config/ProductionStartupGuardrailsIntegrationTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/config/ProductionStartupGuardrailsIntegrationTest.kt)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/auth/AccountSessionHttpTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/auth/AccountSessionHttpTest.kt)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/order/CustomerCartCheckoutHttpTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/order/CustomerCartCheckoutHttpTest.kt)

Under `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo` (15 files):

- [androidApp/build.gradle.kts](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/androidApp/build.gradle.kts)
- [androidApp/src/main/AndroidManifest.xml](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/androidApp/src/main/AndroidManifest.xml)
- [gradle/libs.versions.toml](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/gradle/libs.versions.toml)
- [scripts/build-acceptance.py](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/scripts/build-acceptance.py)
- [shared/build.gradle.kts](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/build.gradle.kts)
- [shared/src/androidHostTest/kotlin/dev/orestegabo/sequo/ReleaseTargetClientTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/androidHostTest/kotlin/dev/orestegabo/sequo/ReleaseTargetClientTest.kt)
- [shared/src/commonMain/composeResources/values-fr/strings.xml](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/commonMain/composeResources/values-fr/strings.xml)
- [shared/src/commonMain/composeResources/values/strings.xml](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/commonMain/composeResources/values/strings.xml)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequo/core/auth/AdminLoginApprovalApiClient.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/core/auth/AdminLoginApprovalApiClient.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequo/core/auth/AuthApiClient.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/core/auth/AuthApiClient.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequo/core/network/ApiHealthClient.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/core/network/ApiHealthClient.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequo/core/network/NetworkConfig.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/core/network/NetworkConfig.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequo/core/network/SequoHttpClient.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/core/network/SequoHttpClient.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/account/AccountContent.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/account/AccountContent.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/catalog/CatalogProductImage.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/catalog/CatalogProductImage.kt)

Under `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor` (18 files):

- [androidApp/build.gradle.kts](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/androidApp/build.gradle.kts)
- [androidApp/src/main/AndroidManifest.xml](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/androidApp/src/main/AndroidManifest.xml)
- [scripts/build-acceptance.py](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/scripts/build-acceptance.py)
- [shared/build.gradle.kts](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/build.gradle.kts)
- [shared/src/androidHostTest/kotlin/dev/orestegabo/sequovendor/ReleaseTargetClientTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/src/androidHostTest/kotlin/dev/orestegabo/sequovendor/ReleaseTargetClientTest.kt)
- [shared/src/commonMain/composeResources/values-fr/strings.xml](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/src/commonMain/composeResources/values-fr/strings.xml)
- [shared/src/commonMain/composeResources/values/strings.xml](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/src/commonMain/composeResources/values/strings.xml)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/activity/MerchantActivityApiClient.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/activity/MerchantActivityApiClient.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/auth/AdminLoginApprovalApiClient.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/auth/AdminLoginApprovalApiClient.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/auth/AuthApiClient.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/auth/AuthApiClient.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/catalog/CatalogApiClient.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/catalog/CatalogApiClient.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/fulfillment/MerchantFulfillmentApiClient.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/fulfillment/MerchantFulfillmentApiClient.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/negotiation/VendorNegotiationApiClient.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/negotiation/VendorNegotiationApiClient.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/network/ApiHealthClient.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/network/ApiHealthClient.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/network/NetworkConfig.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/network/NetworkConfig.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/network/SequoVendorHttpClient.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/network/SequoVendorHttpClient.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/notifications/NotificationsApiClient.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/notifications/NotificationsApiClient.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/ui/account/AccountContent.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/ui/account/AccountContent.kt)

Under `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider` (17 files):

- [ACCEPTANCE_BUILD.md](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider/ACCEPTANCE_BUILD.md)
- [androidApp/build.gradle.kts](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider/androidApp/build.gradle.kts)
- [androidApp/src/main/AndroidManifest.xml](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider/androidApp/src/main/AndroidManifest.xml)
- [iosApp/iosApp/ContentView.swift](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider/iosApp/iosApp/ContentView.swift)
- [scripts/build-acceptance.py](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider/scripts/build-acceptance.py)
- [shared/build.gradle.kts](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider/shared/build.gradle.kts)
- [shared/src/androidHostTest/kotlin/dev/orestegabo/sequorider/ReleaseTargetClientTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider/shared/src/androidHostTest/kotlin/dev/orestegabo/sequorider/ReleaseTargetClientTest.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequorider/core/auth/AdminLoginApprovalApiClient.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider/shared/src/commonMain/kotlin/dev/orestegabo/sequorider/core/auth/AdminLoginApprovalApiClient.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequorider/core/auth/AuthApiClient.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider/shared/src/commonMain/kotlin/dev/orestegabo/sequorider/core/auth/AuthApiClient.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequorider/core/missions/RiderMissionApiClient.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider/shared/src/commonMain/kotlin/dev/orestegabo/sequorider/core/missions/RiderMissionApiClient.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequorider/core/network/ApiHealthClient.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider/shared/src/commonMain/kotlin/dev/orestegabo/sequorider/core/network/ApiHealthClient.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequorider/core/network/NetworkConfig.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider/shared/src/commonMain/kotlin/dev/orestegabo/sequorider/core/network/NetworkConfig.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequorider/core/network/SequoHttpClient.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider/shared/src/commonMain/kotlin/dev/orestegabo/sequorider/core/network/SequoHttpClient.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequorider/core/notifications/RiderNotificationsApiClient.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider/shared/src/commonMain/kotlin/dev/orestegabo/sequorider/core/notifications/RiderNotificationsApiClient.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequorider/core/payout/RiderPayoutApiClient.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider/shared/src/commonMain/kotlin/dev/orestegabo/sequorider/core/payout/RiderPayoutApiClient.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequorider/feature/settings/AccountSettingsScreen.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider/shared/src/commonMain/kotlin/dev/orestegabo/sequorider/feature/settings/AccountSettingsScreen.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequorider/feature/settings/AppText.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider/shared/src/commonMain/kotlin/dev/orestegabo/sequorider/feature/settings/AppText.kt)

Under `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin` (8 files):

- [README.md](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin/README.md)
- [docs/RELEASE_RECETTE.md](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin/docs/RELEASE_RECETTE.md)
- [package.json](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin/package.json)
- [scripts/prepare-release.mjs](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin/scripts/prepare-release.mjs)
- [scripts/release-config.mjs](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin/scripts/release-config.mjs)
- [scripts/verify-release.mjs](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin/scripts/verify-release.mjs)
- [src/App.vue](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin/src/App.vue)
- [tests/releaseConfig.test.mjs](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin/tests/releaseConfig.test.mjs)

