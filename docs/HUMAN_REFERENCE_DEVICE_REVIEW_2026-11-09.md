# Installed short-reference review — 9 November 2026

This follow-up installs the four updated local fixture apps on Android and iPhone simulators and compares their actual UI references with the authenticated local API and admin. It follows the [implementation review](HUMAN_REFERENCE_REVIEW_2026-11-09.md); the earlier review's compilation-only mobile limitation is superseded by the device results recorded here.

## Installed builds

Release ID: `sequo-reference-device-20261109-01`. Environment: `LOCAL_FIXTURE`. All eight installed artifacts match their freshly built APK or complete iOS bundle hashes. Hub was rebuilt/reinstalled after its input fix; the receipts identify the final source separately from its first installation. [Installation receipts and full hashes](evidence/human-reference-devices-2026-11-09/installed-packages.json).

| App | Native source | Android package suffix | iPhone bundle suffix |
| --- | --- | --- | --- |
| Sequo | `52263d6` | `sequo.fixture` | `sequo.Sequo.fixture` |
| SequoVendor | `a013450` | `sequovendor.fixture` | `sequovendor.SequoVendor.fixture` |
| SequoHub | `c050d76` | `sequohub.fixture` | `sequohub.SequoHub.fixture` |
| SequoRider | `ad28b08` | `sequorider.fixture` | `sequorider.SequoRider.fixture` |

All package/bundle IDs begin with `dev.orestegabo.`. Android is the dedicated Pixel 6 emulator, Android 16/API 36, AVD `Sequo_References_API_36_1`, serial `emulator-5554`. iPhone is `Sequo-Task28-iPhone16`, iOS 18.6 simulator, UDID `59D90F12-C368-449B-8752-6BD86241E8DB`. These are simulator checks, not physical-device certification. The iOS artifacts are simulator `.app` bundles, not distribution IPAs.

API: retained isolated workspace at port 55840; Android uses `10.0.2.2`, iPhone uses `127.0.0.1`. Admin: `http://127.0.0.1:5173`, source `2cc5cb3`. Native logins use the normal, server-offered local account chooser and protected sessions. No token is injected by either UI harness.

## Reference comparisons

| Surface | Local account | Exact server/admin reference |
| --- | --- | --- |
| Customer order history | Ama | Returned order `C2J-8SP-V0R`; delivered shared order `C96-BBG-0DY` |
| Vendor ready queue and detail | Yawa | Merchant order `MNS-BX2-A3H`; parent order `C96-BBG-0DY` |
| Rider completed history and recorded pay | Senam | Mission `DA3-KTY-93W` |
| Hub parcel list and stored history | Esso, Adidogomé | `PC4-AWP-0WK`, `PP3-Z4S-J9N`, `PRM-E2K-MZ8` |
| Hub checked return lookup | Dédé, Tokoin | Valid `R2D-W22-814`; changed checksum rejected; lowercase/no-hyphen entry normalized on Android |

Order C, merchant order M, mission D, parcel P and return R identify different record kinds. The same entity has the same reference across clients and admin. Rider currently displays the D mission source in recorded pay; its W ledger identity `WWK-H3T-QGD` and C parent relation are verified through the API/admin rather than claimed visible in Rider's completed-history UI.

The independent [authenticated API/admin comparison](evidence/human-reference-devices-2026-11-09/api-admin-reference-checks.json) passes **167/167** checks, including separate role/relay scopes and independent checksum validation. All six temporary comparison sessions were logged out. The actual admin UI separately displayed C96, MNS/C96, DA3/C96 and the three P parcel references.

## Native UI results

| Installed app | Android | iPhone |
| --- | --- | --- |
| Sequo | PASS — C2J and C96 order references | PASS — same C2J and C96 |
| SequoVendor | PASS — MNS part and C96 parent | PASS — same MNS and C96 |
| SequoRider | PASS — DA3 in completed history and recorded pay | PASS — same DA3 in both views |
| SequoHub | PASS — three parcels/history, typo, normalized lookup and Cancel | PASS — same parcels/history, typo, valid lookup and Cancel |

Final Android run 07: **4 passed, zero failures/errors/skips**, 3m22s including Gradle. Final iPhone run 04: **4 passed, zero failures/skips**. Both include the rebuilt Hub keyboard fix. [Android receipt](evidence/human-reference-devices-2026-11-09/android-full-suite.xml), [iPhone receipt](evidence/human-reference-devices-2026-11-09/ios-full-suite.json), [iPhone source/payload provenance](evidence/human-reference-devices-2026-11-09/ios-test-provenance.json).

Generated screenshots are local test artifacts and are excluded from Git. Android reference assertions require readable content outside the floating navigation, rather than merely an offscreen accessibility node.

The Hub input exercise uses only dummy `000000`, opens the normal scoped lookup/review dialog, and taps **Cancel**. No genuine PIN/QR is issued, opened or retained. No custody, order, catalog, provider or money transition is submitted. The notification prompt encountered on fresh Android customer startup is declined, not granted.

## Fixes and verification

Admin commit `2cc5cb3` fixes the merchant-order M display instead of retained legacy `subOrderCode`, resolves the real D source of a delivery ledger entry without confusing it with W, and uses the typed actor/replacement references. Its **103 Node tests**, type check and production build pass.

Hub commit `c050d76` makes reference/PIN entry single-line, disables autocorrect, uses appropriate keyboards, and provides keyboard Done plus an explicit localized Done control beside the numeric PIN. Clearing focus makes the next action reachable on iPhone. Only `feature/live/LiveHubWorkspace.kt` changes; optional condition notes and other fields keep their behavior. Hub's **21 host tests** pass, and both rebuilt installations pass the final native runs. API business behavior and the other product-app source remain unchanged.

The independent [Android instrumented suite](../scripts/device-reference-tests/android/README.md) and [iPhone XCTest suite](../scripts/device-reference-tests/ios/README.md) navigate the real installed apps. Guards restrict them to the designated local fixture devices/packages. Results require actual, unskipped assertions; screenshots come from the official test frameworks. iOS launcher tests: **14 passed**.

Initial test-harness attempts found selector/configuration issues: French logout labels, retained Vendor detail, Compose tab/TextView accessibility, asynchronous field updates, notification prompts, and scroll-return semantics. These are corrected in the harnesses; failed attempts are not counted as successful app checks. Xcode configuration is delivered through the generated `.xctestrun` environment, and summary counts reject failed/skipped/incomplete suites. Android screenshot artifacts use the standard additional-test-output directory before Gradle removes the temporary harness APKs.

Commands, from `sequo-api` with the retained local workspace running:

```sh
python3 scripts/human-references.py
python3 scripts/mobile-design.py
python3 -m unittest discover -s scripts/device-reference-tests/ios -p 'test_*.py'

# Restore expectations if /tmp was cleaned. These apply to the retained local
# database; a reset database needs a fresh authenticated comparison sheet.
cp docs/evidence/human-reference-devices-2026-11-09/api-admin-reference-checks.json \
  /tmp/sequo-device-reference-sheet.json

ANDROID_SERIAL=emulator-5554 \
ANDROID_HOME=/Users/muhirwagabooreste/Library/Android/sdk \
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
python3 scripts/device-reference-tests/android/run-connected.py \
  --sheet /tmp/sequo-device-reference-sheet.json

python3 scripts/device-reference-tests/ios/run.py \
  --simulator 59D90F12-C368-449B-8752-6BD86241E8DB \
  --release-id sequo-reference-device-20261109-01 \
  --output-dir /tmp/sequo-reference-xcuitest-next \
  --include-hub-confirmation \
  --hub-return-reference R2D-W22-814 --hub-typo-reference R2D-W22-815
```

The guarded native build helpers in each product repository produced the artifacts using `scripts/build-acceptance.py`, the release ID above, `--environment LOCAL_FIXTURE`, the corresponding loopback API URL and platform `android` or `ios`. Full local artifacts/logs remain under `/tmp/sequo-reference-devices`; Xcode result bundles remain under `/tmp/sequo-reference-xcuitest-*`. They are intentionally excluded from Git. Use a fresh output directory for every iPhone test run.

Hub host command, from `SequoHub`: `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --offline -PsequoApiBaseUrl=http://10.0.2.2:55840 -PsequoReleaseId=sequo-reference-device-20261109-01 -PsequoEnvironment=LOCAL_FIXTURE :shared:testAndroidHostTest` — **21 passed**. Admin commands: `node --test tests/*.test.mjs` — **103 passed**; `npm run type-check` and `npm run build-only` — **passed**. Shared reference/vector and mobile-design copy checks above also pass.

## Focused commits and files

- `sequo-admin` — `2cc5cb3`: `src/adminReferences.ts`, `src/adminControls.ts`, `tests/adminReferences.test.mjs`.
- `SequoHub` — `c050d76`: `shared/src/commonMain/kotlin/dev/orestegabo/sequohub/feature/live/LiveHubWorkspace.kt`.
- `sequo-api` — `cdda86c`: isolated Android instrumentation project/runner/readme; `f9b45fb`: isolated XCTest project/runner/readme and launcher tests.
- The review, previous-review follow-up link and selected evidence are recorded in a separate documentation commit. [Machine-readable commands/counts/source and changed-file inventory](evidence/human-reference-devices-2026-11-09/verification.json).

## Preserved work and limits

Customer/Vendor's original Xcode-project edits and Hub's six original dirty files are preserved. The installed Hub includes those changes; receipts record its source revision separately from that working-tree state. [All nine protected-file/frozen-release hashes](evidence/human-reference-devices-2026-11-09/preserved-originals.json) are unchanged. Existing decision notes, audit/output files and frozen Task 28 release are excluded from these commits. No production app is overwritten: these are separate `.fixture` installations.

This verifies short-reference installation, display, lookup and cross-surface agreement. It does not certify every feature or physical Android/iPhone hardware. Live providers, production payments and formal contract delivery remain outside this local check. The Task 28 release manifest and the unstarted contractual acceptance clock are unchanged.
