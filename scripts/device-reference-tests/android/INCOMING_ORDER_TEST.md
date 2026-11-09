# Installed Vendor incoming-order check

This separate, explicitly selected UiAutomator/JUnit test acts only through the installed `dev.orestegabo.sequovendor.fixture` app on the sole task-owned API36 emulator (`emulator-5554`, AVD `Sequo_References_API_36_1`). It never sends HTTP, marks a payment paid, seeds/resets records, clears app data or injects authentication. The launcher defaults to the original **read-only** reference class; this test cannot run accidentally through the normal launcher command.

The operator must first authenticate Yawa through the existing guarded local chooser, or leave the ordinary chooser reachable. Install the identified Vendor LOCAL_FIXTURE APK pointing at `http://10.0.2.2:55840`. Build/cache the instrumentation APK before creating the fresh scene: Gradle configuration/install time counts against the real 45-second window. The existing sanitized reference sheet remains required by the harness build; its records are not used as incoming expectations.

Immediately before each official connected run, root creates one **new, unused** fixed `android-accept`, `android-refuse` or `android-wait` scene through its authorized local fixture route. From its canonical `subOrder`, pass `publicReference` and actual `itemSubtotalCfa`. These scenes have one actual product line with quantity **2**. Existing scene retries never reset deadlines or native presentation receipts; choose another unused authorized attempt if setup consumed the window. The test fails rather than skipping or rearming expired/seen fixtures.

```sh
# These variables must come from the fresh, authorized server readback; no defaults.
# INCOMING_REFERENCE=subOrder.publicReference (M…); INCOMING_AMOUNT=subOrder.itemSubtotalCfa.
ANDROID_SERIAL=emulator-5554 \
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
python3 scripts/device-reference-tests/android/run-connected.py \
  --sheet /tmp/sequo-device-reference-sheet.json \
  --class dev.orestegabo.sequo.devicereferencetests.InstalledVendorIncomingOrderUiTest#incomingOrderUsesExistingMerchantAction \
  --incoming-reference "$INCOMING_REFERENCE" \
  --incoming-action accept \
  --incoming-amount-cfa "$INCOMING_AMOUNT"
```

Repeat with a separately created fresh scene and `--incoming-action refuse`, then `wait`. Exactly one JUnit method runs per selected scene. Missing arguments, wrong fixture mode/device/API/package, invalid M checksum, missing real dialog/reference/quantity/subtotal or unexpected system/provider UI fails the test. Failure-only screenshots use official UiDevice.takeScreenshot and Gradle additionalTestOutputDir, only while the exact Vendor fixture is foreground and no EditText/possible six-digit credential is present. They remain local build artifacts and are never added to Git. After failure evidence (or success), the TestWatcher finished hook presses the normal Home button only if Vendor remains foreground; this stops foreground attention without resetting data, force-stopping, signing out or changing the order.

Before any business action, the test verifies the exact reference and actual quantity/subtotal on the normal incoming dialog. Accept clicks only **Accept order** and requires that exact part's canonical Accepted state plus its normal Start preparation affordance (which is never clicked). Refuse clicks only **Refuse order** and requires that exact part's Rejected readback, without an accepted preparation action. Wait performs no business action and observes the overlay disappear without dismissal within 48 seconds, then checks the exact reference remains **Waiting for your response** in the New queue. The helper requires the overlay title to stay absent for 500 ms, retries stale Compose query nodes within bounded loops, and keeps a matching card in view for up to 7.5 seconds while the normal five-second poll refreshes its server-derived status. It does not scroll past a matching reference while waiting. Query retries happen before a click; an uncertain submitted business click fails instead of automatically resubmitting. This does not claim audio/haptic audibility, background push, automatic refund or final ten-minute expiry. Root performs independent server readback for persisted parent/payment/history outcomes.

Official Gradle results remain under `harness/build/outputs/androidTest-results/connected`; compilation or source preparation alone is **unverified**, not a UI pass. Keep all other device automation stopped during the run. This test source has not been run until its official JUnit result is recorded.

## Existing waiting-order diagnostic

Select `--class dev.orestegabo.sequo.devicereferencetests.InstalledVendorIncomingOrderUiTest#existingWaitingQueueIsReadable` with the same three explicit incoming arguments and `--incoming-action wait`. This separate read-only method opens the already-created canonical waiting part, logs only fixed Orders/Commandes text/description candidate counts, enabled state, bounds and clickable-parent state, and checks its reference, subtotal and waiting status. It never accepts/refuses/silences an order, seeds data or consumes another fresh server slot. Incoming classes must now select an exact method, so the launcher cannot accidentally execute both methods. The business-action method retains its own selection. Prepared iOS sources and data are untouched.
