# Installed Vendor incoming-order XCTest

This separate opt-in XCTest performs only the normal installed Vendor UI: exact fresh M reference, actual server item subtotal and quantity-two line, then Accept, Refuse, or no action while the 60-second overlay ends and the order remains waiting. It does not create fixtures, send HTTP/payment/refund requests, inject credentials, install/build the product, reset data or commit screenshots. Teardown presses Home only for this fixture app. Audio/haptic audibility and fifteen-minute expiry require separate evidence.

`run-incoming.py` accepts only simulator `59D90F12-C368-449B-8752-6BD86241E8DB` and the current identified Vendor artifact under `/tmp/sequo-vendor-timing-60-15-devices/vendor-ios`: source `79ed43cec6307d7e183830332173aed143dc3795`, release `sequo-vendor-incoming-20261109-02`, LOCAL_FIXTURE, loopback port55840, exact `.fixture` bundle. The complete payload is checked against `build-target.json` before preparation and again against the installed app before a test. The prepared xctestrun and test sources are hash-bound; edits require a new prepared bundle. No physical devices, other simulator, production bundle or silently skipped/empty run is accepted.

From `sequo-api`, first compile **only the isolated XCTest harness**, before creating a fresh scene:

```sh
python3 scripts/device-reference-tests/ios/run-incoming.py \
  --simulator 59D90F12-C368-449B-8752-6BD86241E8DB \
  --output-dir /tmp/sequo-vendor-timing-60-15-devices/ios-incoming-tests-01 \
  --prepare-test-bundle
```

Only after that command exits successfully, root creates one unused authorized fixed `ios-accept`, `ios-refuse` or `ios-wait` server scene. From its `subOrder`, supply actual `publicReference` and `itemSubtotalCfa`; quantity is the existing fixed scene's two units. Ensure Yawa's protected session or its ordinary local chooser is available and stop other UI automation. Android Vendor should remain background so it does not compete for foreground attention.

```sh
python3 scripts/device-reference-tests/ios/run-incoming.py \
  --simulator 59D90F12-C368-449B-8752-6BD86241E8DB \
  --output-dir /tmp/sequo-vendor-timing-60-15-devices/ios-incoming-tests-01 \
  --run-prepared --incoming-reference "$REF" \
  --incoming-action accept --incoming-amount-cfa "$ITEM_SUBTOTAL"
```

Repeat with separately created fresh scenes and `refuse` / `wait`. Existing fixture retries do not rearm server deadlines or consumed native alerts. Do not edit expectations or reset records to turn a failure into a pass. Each official `xcresult` must contain exactly one passed test, zero failures and zero skips. Results remain under the output directory; source preparation alone is unverified.

The original `run.py` explicitly selects only `ReferenceDeviceTests` by default, so its read-only suite never runs this new business-action class. Use this dedicated runner for incoming actions. It uses the same official Xcode build-for-testing/xctestrun environment patch/summary checks as the existing reference harness and preserves Xcode loader settings and standard unsigned simulator test-host builds.

The separate `--incoming-action inspect-waiting` selection runs only `testExistingWaitingQueueIsReadable` against an already-created canonical waiting part (for example M0X-4NW-SMQ with its actual subtotal9600). It does not use a new scene or click Accept/Refuse/Silence. Startup waits up to25seconds for real incoming/workspace/chooser content before assuming the account is signed out. Non-click assertions use static-text existence first, without frame/hittability filtering; geometry is checked only before a tap. Failure-only local XCTest screenshot attachments exclude editable/possible credential screens, and diagnostics log only fixed public title counts and frame values. After these source changes, prepare a new output such as `ios-incoming-tests-02`; the old prepared manifest intentionally fails its source-hash guard.
