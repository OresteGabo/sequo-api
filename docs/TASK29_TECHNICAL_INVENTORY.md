# Task 29 — Technical handoff inventory

Inspection date: 8 November 2026. This inventory records local source, build inputs and existing acceptance evidence. It does not record delivery, grant repository access, transfer a signing key, establish payment status or decide contractual rights. The parties must confirm those matters. The signed PDF controls scope, particularly 100 and §§12–15; third-party rights and personal/private access are not inferred from possession of a checkout.

The identified candidate is **sequo-mvp-rc-20261108-01 / LOCAL_FIXTURE**. The [release manifest](../build/releases/sequo-mvp-rc-20261108-01/release-manifest.json), [acceptance report](TASK28_RELEASE_ACCEPTANCE.md), [runbook](TASK28_RELEASE_RUNBOOK.md) and [45-row matrix](TASK28_ACCEPTANCE_MATRIX.csv) are the evidence baseline. Read-only verification during this inventory returned `OK`, `acceptanceState: NOT_DELIVERED`; no receipt or acceptance clock was created. Task 29 has not rebuilt or deployed anything.

## Source checkpoints and working trees

The recorded workspace is `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService`. Origins below are read from local Git configuration with any credential/user-information component removed. Their visibility, remote state, ownership and recipient access have **not** been checked online. Current HEAD means the local observation before Task 29 documentation commits; later documentation commits do not change an artifact's recorded runtime source.

| Repository / local locator | Sanitized origin | Artifact source checkpoint | Current HEAD at inspection | Working-tree observation |
| --- | --- | --- | --- | --- |
| `sequo-api` | [sequo-api](https://github.com/OresteGabo/sequo-api.git) | `96d89dda242f481258bf3c3e4984dd592977ade6` | `f3990bfbe7918d8d9110d46170d19a5f4b5dde7c` | Runtime checkpoint differs intentionally from later release-tool/evidence commits. Existing untracked notes/output/tmp files remain outside this inventory's changes. |
| `Sequo` | [customer](https://github.com/OresteGabo/sequo.git) | `1bccb1e64337ff5e9d96415a6abccd82e80b39b8` | Same | Clean. The former single-space test typo was restored exactly to HEAD before the full suite passed; no separate test edit was committed. |
| `SequoVendor` | [Vendor](https://github.com/OresteGabo/SequoVendor.git) | `3d9b3ad62ec49622f30b3aacafaa4e814845a989` | Same | Only the pre-existing `iosApp/iosApp.xcodeproj/project.pbxproj` edit remains. It was neither staged nor repaired. |
| `SequoRider` | [Rider](https://github.com/OresteGabo/sequorider.git) | `e2ce24c2e22724feefa33900e1bb1b017d0c60f8` | Same | Clean. Includes the isolated-build work and observed iOS safe-area correction. |
| `sequo-admin` | [admin](https://github.com/OresteGabo/sequo-admin.git) | `cfc65e75e2ecd8825ff9592e439a416b8b132d0e` | Same | Clean. The archive is dist-only and identifies its actual source independently of package version `0.0.0`. |

No `AGENTS.md` was found in these repositories or their ancestor paths. Existing notes/audit/roadmaps are implementation locators; use current runtime and the acceptance matrix for completed-versus-pending claims.

**Vendor provenance remains conditional.** The manifest records the existing project file's SHA256 as `a7f7b50149460a7df096ea86593ca2644a0d463d0add8e59f42cd0c1c16e3af9`. A clean clone at the checkpoint alone is not evidence of that exact working tree. Repository owner and receiving maintainer must review this local project change and decide whether to supply an authorized, reviewed patch or prepare a subsequent clean revision/candidate. Do not silently commit it, copy personal signing configuration, or rewrite the immutable candidate's provenance.

The selected relay surface is API-served `/relay-operator.html` and its script. `SequoHub` is optional/conditional pending written scope; it was not added to this eight-artifact release, changed, or treated as a delivered dedicated app. The public marketing/developer sites are not substituted for the signed MVP interfaces.

## Eight recorded artifacts

The manifest and `SHA256SUMS` bind the actual bytes, source identities, migration inventory and six mobile build reports. They are not a signature or rollout approval. The Docker image is separately identified in container evidence; it is not an additional archive silently substituted for the JAR.

| Artifact ID | Local artifact in the release bundle |
| --- | --- |
| `sequo-api.jvm` | [identified JAR](../build/releases/sequo-mvp-rc-20261108-01/artifacts/sequo-api/jvm-sequo-api-0.0.1-SNAPSHOT.jar) |
| `Sequo.android` | [customer APK](../build/releases/sequo-mvp-rc-20261108-01/artifacts/Sequo/android-Sequo-sequo-mvp-rc-20261108-01-LOCAL_FIXTURE.apk) |
| `Sequo.ios_simulator` | [customer simulator ZIP](../build/releases/sequo-mvp-rc-20261108-01/artifacts/Sequo/ios_simulator-Sequo-sequo-mvp-rc-20261108-01-LOCAL_FIXTURE.zip) |
| `SequoVendor.android` | [Vendor APK](../build/releases/sequo-mvp-rc-20261108-01/artifacts/SequoVendor/android-SequoVendor-sequo-mvp-rc-20261108-01-LOCAL_FIXTURE.apk) |
| `SequoVendor.ios_simulator` | [Vendor simulator ZIP](../build/releases/sequo-mvp-rc-20261108-01/artifacts/SequoVendor/ios_simulator-SequoVendor-sequo-mvp-rc-20261108-01-LOCAL_FIXTURE.zip) |
| `SequoRider.android` | [Rider APK](../build/releases/sequo-mvp-rc-20261108-01/artifacts/SequoRider/android-SequoRider-sequo-mvp-rc-20261108-01-LOCAL_FIXTURE.apk) |
| `SequoRider.ios_simulator` | [Rider simulator ZIP](../build/releases/sequo-mvp-rc-20261108-01/artifacts/SequoRider/ios_simulator-SequoRider-sequo-mvp-rc-20261108-01-LOCAL_FIXTURE.zip) |
| `sequo-admin.web` | [admin dist archive](../build/releases/sequo-mvp-rc-20261108-01/artifacts/sequo-admin/web-sequo-admin-sequo-mvp-rc-20261108-01-cfc65e75e2ec.tar.gz) |

Android artifacts are debug-signed, versionCode `1`, versionName `1.0-sequo-mvp-rc-20261108-01`, with `.fixture` application IDs. They target the emulator host alias `http://10.0.2.2:55828`. iOS packages are full unsigned **arm64 simulator** apps, minimum iOS 18.2, with `.fixture` bundle IDs and `http://127.0.0.1:55828`. A debug executable can be only a loader; the full ZIP hash, not that executable alone, binds the payload. These are not Play/App Store, TestFlight or signed physical-iPhone releases.

## Build inputs and guides

| Component | Source-proven configuration / existing guide |
| --- | --- |
| API | `build.gradle.kts`: Java/JVM 21, Kotlin 2.3.21, Spring Boot 4.1.0. Wrapper `gradle/wrapper/gradle-wrapper.properties`: Gradle 9.5.1. [Runbook](TASK28_RELEASE_RUNBOOK.md) covers exact identity, PostgreSQL 16, V1–V52, isolated staging, rollback and role fixtures. |
| Customer | `Sequo/scripts/build-acceptance.py`, `shared/build.gradle.kts`, `gradle/libs.versions.toml`: Gradle 9.1.0, Kotlin 2.4.10, AGP 9.0.1, Compose 1.11.1, Ktor 3.3.0. Runtime entry: `shared/.../App.kt` → `ui/app/SequoShell.kt`; catalog/cart/auth use the generated target. |
| Vendor | `SequoVendor/scripts/build-acceptance.py` and version catalog: Gradle 9.5.1, Kotlin 2.4.20, AGP 9.0.0, Compose 1.11.1, Ktor 3.3.0. Runtime entry: `shared/.../App.kt` → `ui/app/SequoVendorShell.kt`; normal scoped fulfillment and activity clients remain separate from explicit demo data. |
| Rider | [acceptance build guide](../../SequoRider/ACCEPTANCE_BUILD.md), `scripts/build-acceptance.py` and version catalog: Gradle 9.1.0, Kotlin 2.4.10, AGP 9.0.1, Compose 1.11.1, Ktor 3.3.0. Runtime entry: `shared/.../App.kt` → `ui/app/SequoRiderShell.kt`; demo identity is not a real rider session. |
| Admin | [release guide](../../sequo-admin/docs/RELEASE_RECETTE.md), `scripts/prepare-release.mjs`, `scripts/verify-release.mjs`, `package-lock.json`. Entry: `src/main.ts` → `src/App.vue`. Node engines are `^22.18.0 || >=24.12.0`. Locked Vue 3.5.42, Vite 8.3.0, TypeScript 6.0.3 and vue-tsc 3.3.11 are observed; package ranges alone are not the lockfile. |

Local capabilities observed, not recipient prerequisites silently assumed: JDK/toolchain 21; Xcode 26.3 (17C529) with simulator SDK 26.2; Android compile/target SDK 36 and minimum SDK 24; installed build-tools 35.0.0/36.0.0/36.1.0; Python 3.9.6; Node 25.2.1/npm 11.10.0; Chrome 156.0.8078.80. Mobile daemon criteria use JDK 21 even though mobile bytecode target is JVM 11. The iOS candidate was tested on an iOS 18.6 simulator, not every supported OS or Intel simulator; no `iosX64` target is configured. Rider's existing ICU 18.5 linker warning is recorded in its evidence and is not physical iOS 18.2 verification.

### Clean-machine bootstrap examples — online resolution first

These examples are prepared from the existing scripts/configuration; Task 29 has not replayed a fresh-machine installation. The receiver needs authorized source access, an appropriate Mac for iOS, installed vendor tools/SDKs and network access to approved dependency registries. Use an isolated authorized checkout at each recorded checkpoint. Provision an individual `local.properties` SDK location; do not copy a developer's home, caches or personal access files wholesale. Retain the checked-in wrapper, catalogs, npm lockfile and each Xcode `Package.resolved`; do not upgrade dependencies during handoff.

The mobile helpers deliberately run Gradle `--offline` and Xcode with package updates/automatic resolution disabled. A clean machine must first download the pinned wrapper/plugins/artifacts/native tools and Swift packages. In each app checkout, with only the separately approved local API/fixtures in use:

```sh
sequo_release_id=sequo-mvp-rc-20261108-01
# Online Android resolution/build and the normal app host suite:
./gradlew -PsequoApiBaseUrl=http://10.0.2.2:55828 \
  -PsequoReleaseId="$sequo_release_id" -PsequoEnvironment=LOCAL_FIXTURE \
  :shared:testAndroidHostTest :androidApp:assembleDebug

# Online native-tool/artifact resolution, then pinned SwiftPM resolution.
# Choose an absolute output directory owned by the receiving maintainer.
sequo_ios_output=/absolute/approved-output/APP-ios
./gradlew -PsequoApiBaseUrl=http://127.0.0.1:55828 \
  -PsequoReleaseId="$sequo_release_id" -PsequoEnvironment=LOCAL_FIXTURE \
  :shared:linkDebugFrameworkIosSimulatorArm64
xcodebuild -resolvePackageDependencies -onlyUsePackageVersionsFromResolvedFile \
  -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -derivedDataPath "$sequo_ios_output/DerivedData"

# Identifiable helpers now use the corresponding populated caches/output.
python3 scripts/build-acceptance.py --platform android \
  --api-base-url http://10.0.2.2:55828 --release-id "$sequo_release_id" \
  --environment LOCAL_FIXTURE --output-dir /absolute/approved-output/APP-android
python3 scripts/build-acceptance.py --platform ios \
  --api-base-url http://127.0.0.1:55828 --release-id "$sequo_release_id" \
  --environment LOCAL_FIXTURE --output-dir "$sequo_ios_output"
```

Replace `APP` and output directories per component. The Xcode package-resolution flags above were confirmed in installed `xcodebuild -help`; that is not a fresh bootstrap run. Use an approved HTTPS origin and `STAGING` for a real separate test host. **Ordinary mobile builds still default to the production API:** do not install an unqualified bare debug build as a staging test. All API-target overrides require the three matching Gradle properties. The helpers do not install or launch an app, provision signing, register OAuth clients or start the API.

For the API runtime checkpoint, bootstrap JVM compilation/dependencies online, then follow the runbook's identified offline build and PostgreSQL test/deployment steps. Supply only public identity as build inputs:

```sh
SEQUO_RELEASE_ID=sequo-mvp-rc-20261108-01 \
SEQUO_SOURCE_REVISION="$(git rev-parse HEAD)" \
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  ./gradlew classes testClasses bootJar
```

Actual runtime secrets and databases belong in separately authorized staging configuration; a successful compile is not a running acceptance environment. Resolve dependencies for the particular later test commands as needed before using their `--offline` form. Never stamp a current documentation-only HEAD onto an older prebuilt JAR.

For admin, the browser fixtures additionally need **puppeteer-core 24.40.0** and a compatible installed Chrome. That tool is currently supplied outside the project lockfile; the workstation-specific Gemini `NODE_PATH` is not a portable dependency handoff. A temporary tool prefix avoids changing the application dependencies:

```sh
npm ci
sequo_browser_tools=$(mktemp -d "${TMPDIR:-/tmp}/sequo-browser-tools.XXXXXX")
npm install --prefix "$sequo_browser_tools" --no-save --package-lock=false \
  --ignore-scripts puppeteer-core@24.40.0
sequo_admin_output=/absolute/approved-output/admin-release
npm run release:prepare -- --release-id sequo-mvp-rc-20261108-01 \
  --environment LOCAL_FIXTURE --same-origin-fixture --output "$sequo_admin_output"
CHROME_PATH="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
NODE_PATH="$sequo_browser_tools/node_modules" \
  npm run release:verify -- "$sequo_admin_output"
```

Choose the installed Chrome path on the recipient machine. These are online bootstrap instructions, not commands executed by Task 29. Preparation requires a clean tracked tree; verification runs intercepted local browser fixtures, not deployed-role journeys. Approved staging uses the guide's explicit `--environment STAGING --api-origin <actual-https-origin>` mode. `VITE_*` values and built `release.json` are public; never place credentials in them.

## Verified checks and remaining evidence limits

| Evidence level | Actual recorded result | Remaining work / owner |
| --- | --- | --- |
| Mobile automated/packaging | Normal full host suites: Customer **17**, Vendor **27**, Rider **12**, zero failures/skips. Three fixture APKs and three full unsigned Xcode simulator wrappers built. URL guards and normal release transport restrictions checked. | Receiving developer replays appropriate checks on their own supported toolchain; clean-machine/bootstrap repeat remains unverified. |
| Device startup | All six apps installed/cold-launched on fresh Android 36 and iOS 18.6 targets; settled screens inspected. Platform-image 36.1 crash and Rider first-start retry are recorded separately. | SEQUO test lead + mobile maintainer: physical devices, all approved OS versions, authenticated cross-role journeys, camera/image and failure/offline UI. |
| Customer public read | iOS guest catalog matched local HTTP 200 product names/prices. Notifications were declined; no auth/cart/provider write. | Android guest browsing, induced device error state and server access-log attribution were not exercised. Reproduction is in bundled role evidence/runbook. |
| Admin web | Type/build, **31** Node tests, **7** intercepted browser fixtures and archive/file identity checks passed. | SEQUO test lead + web/hosting owner: actual HTTPS host, real admin auth/session expiry/role denials, refresh/rollback and common-order operations. External font failure is a recorded visual improvement, not an invented provider integration. |
| Shared server journey | Persisted delivered two-merchant fixture order read through real local customer/merchant/rider/admin HTTP identities from the final identified JAR; PostgreSQL/migration validation in bundled evidence. | Authenticated phone/browser journeys are a separate missing evidence level. A successful internal test callback is not Yas/Flooz proof. |
| Integrity / delivery | Task 29 repeated `python3 scripts/release_candidate.py verify build/releases/sequo-mvp-rc-20261108-01`: **OK / NOT_DELIVERED**. Supplementary screenshots have a separate index/checksum inventory. | Parties/SEQUO authorized recipient: actual testable delivery reference/date and acceptance follow-up. Source access, checksums or this inventory do not start the clock. |

Detailed reports: [Customer/Vendor](../build/releases/sequo-mvp-rc-20261108-01/evidence/sequo-task28-customer-vendor-report.json), [Rider](../build/releases/sequo-mvp-rc-20261108-01/evidence/sequo-task28-rider-verification.json), [admin fixture verification](../build/releases/sequo-mvp-rc-20261108-01/evidence/web-acceptance.json), [packaged server readback](../build/releases/sequo-mvp-rc-20261108-01/evidence/runtime-readback.json), and [supplementary screenshots](../build/releases/sequo-mvp-rc-20261108-01-evidence/evidence-index.json). Existing local services and task-owned devices were stopped after Task 28; retained local data is not an always-on test-host promise.

## Access, signing, distribution and support inputs — OPEN

| Input / dependency | Owner to confirm | Next safe action |
| --- | --- | --- |
| Authorized source recipient, repository destination, branch/CI access and handoff rights | Parties + repository owner + receiving SEQUO technical owner | Confirm commercial/rights conditions and recipient in writing, then grant organization-scoped access or provide an approved source snapshot/history at the checkpoints. No push, ownership transfer or invitation has been performed. |
| Vendor acknowledged local project change | Repository owner + receiving mobile maintainer | Review the existing file and its recorded hash; decide an authorized patch or subsequent clean candidate. Preserve the original while deciding. |
| Android distribution | SEQUO Play Console/account owner + mobile maintainer | Confirm official package IDs, signing/upload-key custody, certificate fingerprints, allowed testers and distribution channel. No `signingConfig` for a release key is present in the three Android app build scripts. Current APKs use debug signing, not proof of production key ownership. |
| Apple distribution | SEQUO Apple Developer/App Store Connect owner + mobile maintainer | Confirm authorized team, official bundle IDs, certificates/profiles, signing roles and physical/TestFlight channel. `TEAM_ID` is blank in each checked-in xcconfig; local project settings do not establish account ownership or transfer permission. Simulator artifacts cannot satisfy this input. |
| Google/OAuth and auth delivery | Authorized OAuth/project owner + API/mobile/web maintainers | Register approved fixture/staging/live package/bundle IDs, Android signing fingerprints and redirect/web origins as appropriate. Admin consumes public `VITE_GOOGLE_WEB_CLIENT_ID`; mobile public IDs are existing code/plist configuration. Verify real auth delivery securely; do not substitute OTP/social messaging into an unapproved destination. |
| Separate test host, admin/API HTTPS origins, CORS, DNS/TLS, storage and backup/restore | SEQUO hosting owner + receiving operations maintainer | Supply an approved isolated staging target and secure access references, rebuild matching explicit-target apps/admin and run the matrix. No separate online host was confirmed; normal production URLs are not permission to test production. |
| Role/test cohort and site memberships | SEQUO operations/test owner + authorized account administrator | Provision a customer, two merchants, two riders, admin and two distinct relay operators using approved staging identities. Verify role/ownership and relay assignment server-side. Store contacts/credentials outside Git/reports. |
| Provider and business approval | SEQUO business/finance owner + authorized provider contacts + backend maintainer | Supply approved Yas/Flooz specifications/sandbox access and outstanding operational decisions through the main checklist. Collection/refund/rider-transfer capabilities remain disabled; an instruction or ledger liability is not funds transferred. |
| Support/incident ownership and recovery | SEQUO operations owner + receiving maintainer; parties for support terms | Name the technical contact/channel, evidence intake, backup/restore and rollback owner, and provider/host escalation route. Use release/role/order IDs, redacted reproduction and logs. No new support SLA, warranty extension or ongoing maintenance agreement is created by this inventory. |

Use delegated organizational access and environment-owned credentials. Do not transmit a developer's personal PAT, SSH private key, Apple private key/profile, private Google credentials or personal account session as a shortcut. If an organization-owned credential is approved for transfer, use its authorized secure channel/vault and review recipient scope/rotation separately; do not paste it into this document or a chat.

## Dependency, asset and licence inventory — review remains OPEN

No top-level project `LICENSE`, `NOTICE` or complete third-party notice/SBOM inventory was found in the four client/admin repositories inspected. **That absence does not establish legal status, ownership or permission, and this document does not license the code by assumption.** Dependency metadata is a locator for the receiving technical/licensing review, not a completed compliance certification. The API owner should include its Maven/Spring/Flyway/JWT/ZXing dependency and container-image terms in the same review.

| Inventory evidence | Observation | Open owner / next action |
| --- | --- | --- |
| Each mobile `gradle/libs.versions.toml`, wrapper/daemon properties and build scripts | Pinned declared Kotlin/Compose/Ktor/AndroidX/Google dependencies; cached Kotlin stdlib 2.4.10 POM identifies Apache-2.0. This is not a complete transitive resolved-licence inventory. | Receiving developer + SEQUO licensing/rights owner: enumerate the actual resolved dependencies, retain required notices and review provider/SDK terms before distribution. Do not treat library declarations as ownership of those libraries. |
| Each tracked `iosApp/iosApp.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved` | Same eight pins: AppCheck 11.3.2, AppAuth 1.7.6, GoogleSignIn 8.0.0, GoogleUtilities 8.1.4, GTMSessionFetcher 3.5.0, GTMAppAuth 4.1.1, Interop 101.0.0, Promises 2.4.1. Their existing local cached checkout `LICENSE` files identify Apache 2.0. | Receiving Apple/mobile maintainer + rights owner: preserve applicable copyright/notices and separately confirm Apple/Google service, branding and account terms. No personal platform licence/access is transferred here. |
| Admin `package-lock.json` and installed dependency licence files | Lockfile metadata identifies Vue/Vite/vue-tsc/plugin packages as MIT and TypeScript as Apache-2.0. `memorystream` lacks a lockfile licence field but its installed `LICENSE` contains MIT permission terms; missing metadata alone is not a contrary licence finding. | Receiving web maintainer + rights owner: retain the actual resolved dependency/notice inventory; distinguish shipped runtime from build/test tools. |
| External browser tooling | Observed `puppeteer-core` 24.40.0 identifies Apache-2.0; it is not a project lockfile dependency. Chrome is a separately installed vendor tool. | Receiving test/tooling owner: provision the pinned tool and compatible browser from approved sources, with their terms, instead of copying a personal/global tool installation. |
| Customer assets, including `shared/src/commonMain/composeResources/drawable/sequo_sticker_fruits.svg` and `auth_whatsapp.svg` | Embedded comments identify SVG Repo as an origin. No per-asset permission/attribution evidence was established by this inspection. Auth-brand graphics and commercial product/merchant images require their own provenance review. | SEQUO content/rights owner + frontend maintainer: establish source/permission and applicable attribution for each distributed asset; preserve SEQUO-supplied ownership evidence. Do not assume that an image being present in Git makes its rights transferable. |
| Admin `src/assets/main.css` and `src/App.vue` | Runtime references external Google Material Symbols CSS and Google Identity script; these are external availability/service/asset dependencies. The font's network-isolated rendering problem is already recorded. | Web maintainer + SEQUO rights/operations owner: review font/service terms, approved origin/CSP/network availability and notice requirements; decide any later self-hosting change separately. |

Before any authorized source/distribution handoff, the receiving owner should record resolved third-party versions, required notices, asset provenance decisions and any excluded/replaced item. This inspection has not contacted a vendor, fetched new packages, changed dependencies, produced a legal-compliance verdict or copied licence-bearing personal access material.

## Safe package boundaries

Include only authorized source/history at recorded revisions, reviewed project configuration, non-secret templates, the identified artifact/evidence bundle, required third-party notices once reviewed, build/deployment/runbooks, the acceptance matrix and named ownership/dependency records. Preserve migrations and financial/custody history; do not flatten them into demo records for handoff.

Exclude personal/private keys, credentials, sessions, signing stores/profiles, raw OTPs/PINs, private contacts/customer exports, provider/account secrets, production database dumps and unreviewed raw logs. Machine SDK paths, whole home directories, `.gradle`/Xcode/browser caches and personal tool installations are not source-transfer inputs. Do not copy ignored `.env*`, `local.properties` or temporary secret configuration just because a local build used them. Supply approved templates and secure references instead.

The deliberately uncommitted `TASK16-SETTINGS-TO-DECIDE.md` and `TASK28-START-HERE.md`, existing audit/output/tmp work, invoices/receipts and the unrelated Vendor project edit were not copied into this document or staged by this work. Their owners decide separately what may be shared. A dedicated Hub source handoff or external marketing site is not silently added to the MVP release.

Task 29's independent technical check was read-only bundle verification and local source/tool/licence metadata inspection. No rebuild, remote access, message, repository permission change, signing/export operation or device action was performed. The main contractual checklist owns commercial, delivery, warranty/maintenance and Annex B decisions; this inventory supplies technical locators and explicit OPEN inputs for that process.
