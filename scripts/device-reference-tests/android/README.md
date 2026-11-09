# Installed fixture app reference checks

This isolated AndroidX UiAutomator/JUnit host tests the **real installed** Customer, Vendor, Hub and Rider fixture APKs. It does not replace app repositories, responses or account/session code. The only editable fields are Hub's return reference and the deliberately invalid `000000` PIN. The physical confirmation is always cancelled. There is no test code for accepting orders, pickup/release/intake confirmation, payment, seed/reset, clearing app data, or entering provider credentials.

The suite requires the task-owned API36 emulator (`ANDROID_SERIAL=emulator-5554`, `Sequo_References_API_36_1`) and the four `dev.orestegabo.{sequo,sequovendor,sequohub,sequorider}.fixture` packages. The mandatory launcher verifies that this serial is the sole connected device and that its emulator-console AVD name matches exactly before delegating to Gradle. The instrumented suite additionally rejects physical hardware. It reuses existing authentication when present; otherwise it selects only the normal server-offered `[TEST]` account buttons. Hub logs out through its normal UI to switch Esso to Dédé. It must run without concurrent desktop/device automation.

The retained local API must be available to these already-built APKs at `http://10.0.2.2:55840`, with its normal guarded mobile fixture entry enabled. No test-side HTTP session injection is used.

## Build and run

Use the existing native wrapper (Gradle9.1/AGP9.0.1). Runner1.7.0, ext-junit1.3.0, JUnit4.13.2 and SDK36 were already cached when authored; UiAutomator2.3.0 was not. The first invocation therefore needs Google's public Maven repository. Subsequent runs may use `--offline`.

From `SequoService/sequo-api`:

```sh
# Saved expectations for the retained local database, if /tmp was cleaned:
cp docs/evidence/human-reference-devices-2026-11-09/api-admin-reference-checks.json \
  /tmp/sequo-device-reference-sheet.json

ANDROID_SERIAL=emulator-5554 \
ANDROID_HOME=/Users/muhirwagabooreste/Library/Android/sdk \
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
python3 scripts/device-reference-tests/android/run-connected.py \
  --sheet /tmp/sequo-device-reference-sheet.json
```

The build reads the authorized sanitized sheet, verifies `LOCAL_FIXTURE`/synthetic accounts/no business or provider writes, and generates a reduced test asset under `harness/build/generated/referenceAssets`. No tokens, PINs, contacts or proof fields are copied. The asset supplies the expected references, not fake app data. Missing/mismatched records fail before device testing.

The saved sheet is an expectation for the retained database, not a portable seed. Reset/reseeded data needs a new authenticated comparison sheet; do not edit expected references just to make a failing UI run pass.

The launcher defaults to `--offline`; use its `--resolve-online` flag only for the first dependency download. To compile without launching/installing anything, use the native wrapper directly with `-p scripts/device-reference-tests/android -PreferenceSheet=/tmp/sequo-device-reference-sheet.json :harness:assembleDebug :harness:assembleDebugAndroidTest`. A single test can be selected with launcher argument `--class dev.orestegabo.sequo.devicereferencetests.InstalledReferenceUiTest#test01CustomerPersistedOrderReferencesAgreeWithAdmin`.

Standard instrumentation APKs are under `harness/build/outputs/apk`; JUnit/device results are under `harness/build/outputs/androidTest-results/connected` and the HTML report under `harness/build/reports/androidTests/connected`. Four official `UiDevice.takeScreenshot` images are written to the runner's `additionalTestOutputDir` and reported with `additionalTestOutputFile_*` status bundles. Gradle collects them under `harness/build/outputs/connected_android_test_additional_output` before removing the harness APKs. A missing output directory fails setup instead of claiming uncollected evidence. Failure captures are omitted when editable fields or possible six-digit credentials are present. The `SequoReferenceUI` instrumentation log emits only package names and already-sanitized public references. No custom adb input, accessibility dumps or screenshot scripts are used. The known emulator-only **System UI** ANR can be acknowledged with its standard Wait action and is logged; a SEQUO application ANR is never dismissed or counted as a pass. Startup notification permission prompts are denied only inside the standard permission controller while a fixture app is active; other permission requests fail and no permission is granted.

## Assertions and limits

1. Customer Ama: real recent order and shared order references.
2. Vendor Yawa: real merchant part, then its existing detail with the shared parent reference.
3. Hub Esso: all three parcel references and persisted history; Dédé: wrong checksum disables lookup, normalized valid reference opens the normal confirmation, **Cancel** closes it. Two additional official captures record the invalid state and the cancelled form only after verifying all editable values are the known public reference, dummy `000000` and blank note; the dummy PIN is cleared afterwards.
4. Rider Senam: completed mission reference in History and Payout. The current app shows its typed D mission source; its W ledger identifier and C parent relation are verified by the independent sanitized API/admin sheet, not claimed visible in Rider UI.

All four methods must pass before claiming Android UI verification. Source preparation or compilation alone is not a pass. Existing sessions/data remain installed after the run. Provider/system dialogs, unavailable account entries, stale fixtures, inaccessible controls or mismatched references fail with a readable assertion and require review; the suite does not bypass them.

API usage follows the official [AndroidX UiAutomator instrumentation guidance](https://developer.android.com/training/testing/other-components/ui-automator-legacy).
Artifact reporting follows the [AndroidX additional-test-output convention](https://android.googlesource.com/platform/frameworks/support/+/f2e05c341382db64d127118a13451dcaa554b702/benchmark/perfetto/src/main/java/androidx/benchmark/perfetto/PerfettoRule.kt).
