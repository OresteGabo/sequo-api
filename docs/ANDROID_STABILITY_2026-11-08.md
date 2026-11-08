# Android stability and iPhone feature comparison — 8 November 2026

**Android-specific defects were found and corrected. Full feature parity and production stability are not certified.** This follow-up covers Customer (`Sequo`), Vendor and Rider. The iPhone baseline was build/launch evidence and one guest catalogue read, not proof that every signed-in feature works. Shared source is useful evidence of implementation parity; it is not a substitute for device journeys.

All tests used explicit `LOCAL_FIXTURE` targets. No production session, provider operation, external sign-in/message, real mission or money transfer was used. Vendor/Rider screen checks used their explicitly labelled demo modes. Customer public reads used the retained local API, without injecting credentials or a fake authenticated session.

## Identified builds and changes

Verification ID: `sequo-android-check-20261108`. These debug-signed `.fixture` APKs use `http://10.0.2.2:55828`; they are emulator verification artifacts, not store releases or a delivered replacement for the frozen Task 28 candidate. API runtime and database migrations were unchanged. Task 28's [manifest](../build/releases/sequo-mvp-rc-20261108-01/release-manifest.json), [acceptance matrix](TASK28_ACCEPTANCE_MATRIX.csv) and delivery status remain separate.

The frozen candidate does not contain these later client fixes. Rebuild and identify a subsequent full release before distributing it as the updated MVP; do not relabel the old bundle or infer current iPhone runtime coverage from its earlier builds.

| App | Final source / artifact | Focused corrections |
| --- | --- | --- |
| Customer | `f3ae00c4dea652177956fcbc730e7da58c2cd382`; [APK](../build/verification/android-stability-20261108/artifacts/Sequo.apk), [metadata](../build/verification/android-stability-20261108/build-reports/Sequo.android.json) | `93917c1`: remove sensitive sign-in logging; protect device-bound stores; native exports/biometric API guards; root-owned save lifecycle; existing EN/FR mappings. `f3ae00c`: transient login/restore flags and native Back priorities. |
| Vendor | `123feaca39257c28391e388f90689e1dd2efc5ba`; [APK](/tmp/sequo-android-vendor-build-v2/SequoVendor-sequo-android-check-20261108-LOCAL_FIXTURE.apk), [metadata](/tmp/sequo-android-vendor-build-v2/build-target.json) | `7928d43`: backup/legacy biometric compatibility. `ef30a33`: authenticated notification results no longer substitute demo rows for empty/error responses; session changes reject stale results. `123feac`: native Back and existing translations/correct server-confirmation copy. |
| Rider | `2006e42feffd0d319291d7ea4986a1e6e7888567`; [APK](/tmp/sequo-android-rider-build-v3/SequoRider-sequo-android-check-20261108-LOCAL_FIXTURE.apk), [metadata](/tmp/sequo-android-rider-build-v3/build-target.json) | `546a3c5`: encrypted-store backup exclusions. `cd46a84`: safe drawing insets. `2006e42`: Back returns to Missions while root exit remains available. |

Customer APK SHA256: `76dd8d2a8cefab21dea363ba6b4d7a176e13cd08f32e8cdec2a0a3684505f783`. Vendor: `92d95502ea5db27c9244fe7408755430733a83a129c7d61c57468acaf2f1cc36`. Rider: `454c1d8a8253a8c52a35603d285929237bd793c6af228768df28ab2053cfa33e`. Durable local [Vendor](../build/verification/android-stability-20261108/artifacts/SequoVendor.apk) and [Rider](../build/verification/android-stability-20261108/artifacts/SequoRider.apk) copies match the original helper outputs; the index binds all three hashes.

Backup rules are preventive. They exclude Keystore-dependent credential files from backup/transfer without deleting current sessions, changing keys or repairing already-damaged restored stores. Customer's existing `allowBackup=false` remains; explicit extraction exclusions also cover transfer. No production data, ownership rule, financial/custody history or tariff was changed.

## Checks run

| Check | Result / evidence limit |
| --- | --- |
| Customer automated/native checks | **29 final host tests**, zero failures/errors/skips, debug APK and targeted iOS shared-source compilation passed. Coverage includes guarded biometric dispatch, complete/failed/cancelled stream writes, EN/FR lookup coverage and six Back decision priorities. Independent lifecycle/provider/privacy review passed. |
| Vendor host checks | Baseline **27**, zero failures; after notification/navigation/translation changes, **5 targeted checks**, zero failures. Includes network empty/error/retry vs demo separation, configured target and all 473 keys in EN/FR. Debug build and targeted iOS shared-source compile passed. |
| Rider host/native checks | **12 baseline tests**, zero failures; **3 backup-rule checks**, **2 new navigation checks**, debug build and targeted iOS shared-source compile passed. Existing iOS Keychain cast warnings remain; compilation is not a proven Keychain round trip. |
| Android device | Task-owned API 36 emulator, 720×1600 portrait, final APK updates preserve fixture data. No physical Android phone or API 24–30 device run. |
| Customer device | Final cold launch; real public catalogue/images match local API prices (4200/3300 CFA); selected Piment fort/Alloco; Account/build labels render; guest Cart/Orders require sign-in. Back returns from detail/tab/legal screens. Controlled connection failure and repeated offline retry show error without fabricated products; restored connection + Retry recovers the same catalogue. PDF cancel is explicit and creates no file; save after picker rotation produced a valid 48,644-byte/two-page A4 PDF. Bundled-photo save produced a valid 968,197-byte/2401×3602 JPEG with watermark. Native image share chooser opened and was dismissed without sending. |
| Vendor device | Final v2 cold launch, explicit demo entry, Dashboard/Orders/Catalog/editor/Account render. Raw `catalog_add` is now “Add product”; save copy requires server confirmation. Back closes editor, then drawer independently, then returns Catalog → Dashboard. No product was saved/published; no real notification or merchant session was exercised. |
| Rider device | Final v3 cold launch; sign-in title is clear of the status bar; Missions and bottom tabs remain inside system bars. Demo route/detail, History, Payout, Account and French/English render. First Back dismisses the keyboard and retains detail; next Back returns to Missions; root Back exits normally. Account Back also returns to Missions. Demo operations prove UI only. |
| APK compatibility/integrity | All three APKs match committed-source metadata and hashes. `zipalign -c -P 16 4` passed for each; all four native libraries per APK have ELF PT_LOAD alignment ≥16 KiB. This is a static check, not execution on a physical 16-KiB-page device. |
| Crash/ANR observation | No crash-buffer process markers or ANR-event markers for the three fixture app IDs during the retry-emulator run. This bounds the observed tests; it is not an exhaustive crash guarantee. |

An initial emulator process was killed with exit 137 during concurrent builds; Docker was temporarily unavailable too. That is recorded as an environment failure, not a passed app test or an application crash diagnosis. The smaller emulator retry booted successfully; only completed screen observations are reported above. No personal emulator/device or unrelated service was reset.

## Feature comparison and remaining limits

| Area | Android compared with iPhone | What remains unverified or incomplete |
| --- | --- | --- |
| Customer catalogue, options, basket, quote, checkout, tracking, negotiation, relay and returns | Most screens/DTOs/clients are common Kotlin/Compose. Server contract regressions and the earlier persisted HTTP journey remain in Task 28. Missing resource-map entries affecting Account/checkout/relay were corrected. | Normal Google sign-in, saved/expired sessions and complete authenticated Android/iPhone basket/order journeys on devices. Guest screens and host tests cannot certify these. |
| Vendor catalogue, media, bargaining, preparation and sales/return/payment reads | Common screens and scoped clients on both. Android delegates actual capture to the camera app using its confined provider path. A successful local draft/demo is not proof of publication. | Approved merchant sign-in, camera capture/upload/publication/customer display, separate-merchant device isolation and real preparation/status readback. Demo media/options network actions are disabled. Store profile/open-close controls still use a local repository on both platforms; they are not verified persisted operations. |
| Rider missions, proof, relay, history and pay | Common supported manual-proof and persisted-status paths. Android Back/insets were corrected; native identity/session implementations differ. | Actual authorized acceptance/pickup/wrong-PIN/retry/concurrent acceptance/completion/history on phones. Operational requests do not transparently refresh every expired token; shared UI can require reauthentication. Proof drafts/navigation use in-memory state and are not guaranteed across recreation. |
| Native sign-in and device services | Google has platform-specific bridges. Several additional displayed sign-in methods remain not-ready on both Customer/Vendor platforms. Android biometric SDK guards were corrected without enabling a new login workflow. | Real OAuth registration/cancellation/fallback, biometric enrollment, Keystore/Keychain restore and device transfer. Hardware/provider paths were not simulated as successes. |
| Images and file export | Android native save/share paths were repaired; iPhone photo export remains explicitly pending. | Customer photo actions currently export the bundled `imageResource` rather than the fetched catalogue/camera bitmap; post/copy/edit remain shared placeholders. Native export success is not proof of exporting an uploaded merchant image. Older Android/OEM/cloud-document-provider behavior and process-loss feedback remain unverified; the API36 local save, cancel and rotation checks above passed. |
| Push, maps, GPS and scanning | Source inspection found notification inbox/preferences, but no complete native push registration/delivery on both platforms. Rider GPS/background tracking and camera scanning are absent/placeholders on both; manual proof/status tracking is separate. | No claim that a preference switch provides delivered push, or that a status screen provides live GPS. Confirm intended scope before adding capabilities; no unused permission was added to imply support. |
| Collections/refunds/payouts and business decisions | Both platforms use server-owned state. Yas/Flooz remain separately NOT_CONNECTED; unapproved subscription/referral production benefits remain inactive. | Official provider access/sandbox evidence, approved business settings, human staging accounts and an approved separate test host. Existing [O-SEC](TASK29_OPEN_ITEMS.md#o-sec) remains a separate online-activation gate. |

## Commands and reproduction

Use each app repository under `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService`. The following are the normal commands used with explicit target properties; source-specific test counts are above. Dependency caches were already populated; a clean machine must follow the [bootstrap instructions](TASK29_TECHNICAL_INVENTORY.md#clean-machine-bootstrap-examples--online-resolution-first).

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
 ./gradlew --offline -PsequoApiBaseUrl=http://10.0.2.2:55828 \
 -PsequoReleaseId=sequo-android-check-20261108 -PsequoEnvironment=LOCAL_FIXTURE \
 :shared:testAndroidHostTest :androidApp:assembleDebug

JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
 ./gradlew --offline -PsequoApiBaseUrl=http://127.0.0.1:55828 \
 -PsequoReleaseId=sequo-android-check-20261108 -PsequoEnvironment=LOCAL_FIXTURE \
 :shared:compileKotlinIosSimulatorArm64

# Choose a new output directory per app/build; never overwrite the Task 28 bundle.
python3 scripts/build-acceptance.py --platform android \
 --api-base-url http://10.0.2.2:55828 --release-id sequo-android-check-20261108 \
 --environment LOCAL_FIXTURE --output-dir /absolute/approved-new-output
```

Rider additionally ran `python3 -B -m unittest discover -s scripts/tests -p 'test_android_backup_rules.py' -v` and `:shared:testAndroidHostTest --tests '*RiderBackNavigationTest'`. Vendor's final changed-behavior filter was `--tests dev.orestegabo.sequovendor.VendorNotificationClientTest --tests dev.orestegabo.sequovendor.VendorLocalizationTest --tests dev.orestegabo.sequovendor.ReleaseTargetClientTest` with `:shared:testAndroidHostTest :androidApp:assembleDebug` and the Android properties above. Customer's final command ran the complete 29-test host suite. Earlier baseline counts were 17/27/12; those counts describe tests, not accepted product features.

Native-file verification used `pdfinfo /tmp/sequo-android-exported-privacy.pdf`, PDF signature/EOF checks, JPEG signature checks and `sips -g pixelWidth -g pixelHeight /tmp/sequo-android-exported-photo.jpg`. An optional `pypdf` import was unavailable; `pdfinfo` supplied the successful independent PDF parse. Neither file test approves the draft legal text or proves camera-image export.

The device fixture used the existing `Sequo_Task28_API36` in `/tmp/sequo-task28-avds` with `-no-snapshot -no-audio -no-window -no-boot-anim -gpu swiftshader_indirect -memory 2048 -cores 2 -skin 720x1280 -dpi-device 320 -port 5580`. A loopback-only forward mapped 55828 to the retained `sequo-task28-local` API container's 55830. The forward logged only public catalogue GET markers, never credentials. Customer prices were compared against `GET /api/catalog/home`; native actions used `adb -s emulator-5580` install/start/input/UI-tree/screenshot commands. No Google sign-in button was pressed.

For a full remaining device acceptance run, the appointed test owner must use an approved separate host and normal role accounts, then repeat [Task 28's journeys](TASK28_RELEASE_RUNBOOK.md#5-run-annex-a-with-a-common-order-id). Record the exact APK/source, device/OS, allowed and denied role actions, the same order IDs across apps and server state after restart. Run physical capture, wrong proof, retry/offline, two-rider acceptance, return and actual provider sandbox checks separately. Mark failures/blockers explicitly. Do not turn guest/demo identities into authentication evidence or exercise production funds.

The local [evidence index](../build/verification/android-stability-20261108/evidence-index.json) binds **67 payload files** plus its own checksum: three APKs/reports, UI trees, 12 screenshots and two public test exports. All **17 snapshot assertions** passed. The one pre-fix Rider screenshot is explicitly marked as a counterexample. Verify from that directory with `shasum -a 256 -c SHA256SUMS`. These generated local artifacts are ignored by Git; source fixes and this report are committed. Original per-app review/command logs remain under `/tmp/sequo-android-{customer,vendor,rider}-review.md` and the named logs there.

Cleanup verified: the three fixture apps and task-owned emulator are stopped; `adb devices -l` is empty. The local forwarding process and only the `sequo-task28-local` API/PostgreSQL containers were stopped; volumes, fixture records and emulator data were retained. No personal device, unrelated service or production configuration was changed. Customer/Rider trees are clean; Vendor retains only the original unstaged Xcode project edit with unchanged SHA256 `a7f7b50149460a7df096ea86593ca2644a0d463d0add8e59f42cd0c1c16e3af9`. Existing deliberately uncommitted notes and output work remain untouched.

Final `shasum -a 256 -c SHA256SUMS` passed for all 68 inventory entries. The frozen-release verifier again returned `OK / NOT_DELIVERED`; no delivery receipt was created. Documentation link/anchor checks and `git diff --check` passed. Eight focused client commits contain the fixes listed above; the API follow-up commit changes only README and this report. Per-file changes can be inspected from each app's baseline (`1bccb1e`, `3d9b3ad`, `e2ce24c`) to its final source listed above.

This follow-up does not start a contractual reporting or warranty clock and does not certify acceptance. The next task can use the identified fixes, passing automated checks and bounded local screen evidence; it must still obtain and execute the missing real-account/device/provider tests before calling every feature stable.
