package dev.orestegabo.sequo.devicereferencetests;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.Direction;
import androidx.test.uiautomator.StaleObjectException;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import java.io.InputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

/** Real installed UI only: no HTTP adapter, fake rows, application-data reset or business action. */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public final class InstalledReferenceUiTest {
    private static final String CUSTOMER = "dev.orestegabo.sequo.fixture";
    private static final String VENDOR = "dev.orestegabo.sequovendor.fixture";
    private static final String HUB = "dev.orestegabo.sequohub.fixture";
    private static final String RIDER = "dev.orestegabo.sequorider.fixture";
    private static final List<String> PACKAGES = Arrays.asList(CUSTOMER, VENDOR, HUB, RIDER);
    private static final long LOAD_TIMEOUT = 30_000;
    private UiDevice device;
    private JSONObject sheet;
    private String activePackage;
    private File screenshotDirectory;
    private boolean hubDummyPinEntered;

    @Rule public final TestWatcher failureEvidence = new TestWatcher() {
        @Override protected void failed(Throwable error, Description description) {
            if (device == null || activePackage == null || !activePackage.equals(device.getCurrentPackageName())) return;
            // A controlled Hub form contains only this public reference, dummy PIN and blank note.
            // All other editable fields or possible handoff credentials remain excluded.
            boolean controlledHub = controlledHubReturnForm();
            boolean unknownPin = device.findObjects(By.text(java.util.regex.Pattern.compile("[0-9]{6}"))).stream()
                    .anyMatch(node -> !controlledHub || !"000000".equals(node.getText()));
            if ((device.hasObject(By.clazz("android.widget.EditText")) && !controlledHub) || unknownPin) {
                Log.w("SequoReferenceUI", "Failure screenshot omitted: editable/credential content may be visible");
                return;
            }
            try { captureReferenceScreen("failure-" + description.getMethodName().replaceAll("[^A-Za-z0-9_-]", "") + ".png"); }
            catch (Throwable captureError) { Log.w("SequoReferenceUI", "Failure screenshot unavailable: " + captureError.getClass().getSimpleName()); }
        }
    };

    @Before public void requireDedicatedFixtureEmulatorAndRealReferenceSheet() throws Exception {
        assertEquals("LOCAL_FIXTURE", InstrumentationRegistry.getArguments().getString("fixtureMode"));
        assertEquals("emulator-5554", InstrumentationRegistry.getArguments().getString("expectedSerial"));
        String output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir");
        assertNotNull("Run through Gradle instrumentation so screenshot evidence is collected before uninstall", output);
        assertFalse("An official additional-test-output directory is required", output.trim().isEmpty());
        screenshotDirectory = new File(output);
        assertTrue(screenshotDirectory.isAbsolute());
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        assertTrue("Refusing physical devices", hardware.contains("ranchu") || hardware.contains("goldfish"));
        assertEquals("This suite is scoped to the task-owned API36 emulator", 36, Build.VERSION.SDK_INT);
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open("reference-sheet.json")) {
            sheet = new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals("SEQUO_REFERENCE_UI_V1", sheet.getString("protocol"));
        assertEquals("LOCAL_FIXTURE", sheet.getString("environment"));
        for (String key : Arrays.asList("customerRecent", "sharedOrder", "vendorPart", "riderMission", "riderLedger", "returnLookup")) {
            assertTrue("Missing real reference: " + key, sheet.getString(key).matches("[0-9A-Z]{3}-[0-9A-Z]{3}-[0-9A-Z]{3}"));
        }
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    }

    @Test public void test01CustomerPersistedOrderReferencesAgreeWithAdmin() throws Exception {
        launch(CUSTOMER);
        enterFixtureAccountIfAuthIsVisible("[TEST] Ama", "Orders", "Suivi");
        navigation("Orders", "Suivi", "Commandes");
        assertReferenceVisible(sheet.getString("customerRecent"));
        assertReferenceVisible(sheet.getString("sharedOrder"));
        captureReferenceScreen("01-customer-order-reference.png");
    }

    @Test public void test02VendorPartAndParentReferencesAgreeWithAdmin() throws Exception {
        launch(VENDOR);
        enterFixtureAccountIfAuthIsVisible("[TEST] Yawa", "Orders", "Commandes");
        UiObject2 backToQueue = visibleAny("Back to orders", "Retour aux commandes");
        if (backToQueue != null) click(backToQueue);
        navigation("Orders", "Commandes");
        click(button("Ready", "Pretes"));
        UiObject2 part = requireText(sheet.getString("vendorPart"));
        // Open the existing detail affordance belonging to this card, never a preparation action.
        UiObject2 parent = part;
        UiObject2 detail = null;
        for (int i = 0; parent != null && i < 8; i++, parent = parent.getParent()) {
            detail = parent.findObject(By.text("View detail"));
            if (detail == null) detail = parent.findObject(By.text("Voir le detail"));
            if (detail != null) break;
        }
        assertNotNull("The actual merchant card must expose its normal detail action", detail);
        click(detail);
        assertReferenceVisible(sheet.getString("vendorPart"));
        assertReferenceVisible(sheet.getString("sharedOrder"));
        captureReferenceScreen("02-vendor-part-reference.png");
    }

    @Test public void test03HubParcelHistoryAndCheckedReturnLookupUseRealReferences() throws Exception {
        launch(HUB);
        switchHubAccount("[TEST] Esso");
        navigation("Hub", "Relais");
        JSONArray parcels = sheet.getJSONArray("parcelReferences");
        assertEquals(3, parcels.length());
        for (int i = 0; i < parcels.length(); i++) assertReferenceVisible(parcels.getString(i));
        navigation("History", "Historique");
        for (int i = 0; i < parcels.length(); i++) assertReferenceVisible(parcels.getString(i));
        captureReferenceScreen("03-hub-parcel-history-reference.png");
        switchHubAccount("[TEST] Dédé");
        navigation("Returns", "Retours");
        String valid = sheet.getString("returnLookup");
        String typo = valid.substring(0, valid.length() - 1) + (valid.endsWith("0") ? "1" : "0");
        setReturnField(0, typo);
        setReturnField(1, "000000"); // Deliberately invalid; lookup does not verify or submit this PIN.
        hideKeyboardIfVisible();
        assertNotNull(requireAnyText("Check the reference: enter the nine letters and numbers shown to the customer.",
                "Vérifiez la référence : saisissez les neuf lettres et chiffres affichés au client."));
        UiObject2 lookup = button("Confirm physical intake", "Confirmer la réception physique");
        assertFalse("A transcription error must disable the lookup action", actionable(lookup).isEnabled());
        assertTrue("Reference evidence may contain only our public reference, dummy PIN and blank note", controlledHubReturnForm());
        captureReferenceScreen("05-hub-invalid-reference.png");
        setReturnField(0, valid.toLowerCase(Locale.ROOT).replace("-", ""));
        hideKeyboardIfVisible();
        lookup = waitForEnabledButton("Confirm physical intake", "Confirmer la réception physique");
        click(lookup);
        try {
            assertNotNull("The authorized resolver must reach the physical confirmation dialog",
                    waitAnyText(LOAD_TIMEOUT, "Confirm physical operation", "Confirmer l'opération physique"));
            click(button("Cancel", "Annuler"));
            assertTrue("Cancel closes the dialog without submitting custody",
                    device.wait(Until.gone(By.text("Confirm physical operation")), 5_000));
            assertFalse(device.hasObject(By.text("Confirmer l'opération physique")));
            assertTrue("Cancelled-form evidence must exclude any real credential or note", controlledHubReturnForm());
            captureReferenceScreen("06-hub-cancelled-lookup.png");
            setReturnField(1, "");
            hideKeyboardIfVisible();
            Log.i("SequoReferenceUI", "PASS normalized return lookup; physical confirmation CANCELLED; no intake sent");
        } finally {
            // Failure cleanup remains cancel-only. There is intentionally no code path clicking Confirm.
            UiObject2 cancel = visibleAny("Cancel", "Annuler");
            if (cancel != null) click(cancel);
        }
    }

    @Test public void test04RiderCompletedMissionReferenceAgreesWithAdminAndRecordedEarnings() throws Exception {
        launch(RIDER);
        enterFixtureAccountIfAuthIsVisible("[TEST] Senam", "Missions", "Missions");
        navigation("History", "Historique", "Archives");
        assertReferenceVisible(sheet.getString("riderMission"));
        navigation("Payout", "Paiement");
        requireAnyText("Recorded pay history", "Historique de rémunération enregistré");
        assertReferenceVisible(sheet.getString("riderMission"));
        captureReferenceScreen("04-rider-mission-source-reference.png");
        // The current UI labels the real D source as Mission; it does not show the W row identifier.
        // W identity and C parent agreement are independently established by the sanitized API/admin sheet.
        Log.i("SequoReferenceUI", "Ledger identity API/admin-only: " + sheet.getString("riderLedger")
                + "; parent API/admin-only: " + sheet.getString("sharedOrder"));
    }

    private void launch(String packageName) {
        assertTrue("Only installed fixture applications may be launched", PACKAGES.contains(packageName));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(packageName);
        assertNotNull("Install the identified fixture APK first: " + packageName, launch);
        activePackage = packageName;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        context.startActivity(launch);
        boolean opened = false;
        long deadline = android.os.SystemClock.uptimeMillis() + LOAD_TIMEOUT;
        do {
            dismissOnlySystemUiAnr();
            denyFixtureNotificationPermissionIfVisible();
            if (packageName.equals(device.getCurrentPackageName()) && device.hasObject(By.pkg(packageName))) {
                opened = true;
                break;
            }
            device.waitForIdle(250);
            android.os.SystemClock.sleep(100);
        } while (android.os.SystemClock.uptimeMillis() < deadline);
        assertTrue("Fixture app did not open: " + packageName, opened);
        device.waitForIdle(1_000);
    }

    private void enterFixtureAccountIfAuthIsVisible(String label, String readyEnglish, String readyFrench) throws Exception {
        UiObject2 account = waitAnyText(8_000, label);
        if (account == null && visibleAny(readyEnglish, readyFrench) == null) {
            if (VENDOR.equals(activePackage)) {
                UiObject2 chooser = visibleAny("Local test merchant accounts", "Comptes commerçants de test local");
                if (chooser == null) chooser = findTextScrolling("Local test merchant accounts");
                if (chooser == null) chooser = findTextScrolling("Comptes commerçants de test local");
                assertNotNull("Open the existing local merchant account chooser", chooser);
                click(chooser);
            }
            account = findTextScrolling(label);
        }
        if (account != null) click(account); // Existing server-offered account button only; no token/API injection.
        assertNotNull("Normal account entry or its existing authenticated navigation must be reachable",
                waitAnyText(LOAD_TIMEOUT, readyEnglish, readyFrench));
    }

    private void switchHubAccount(String label) throws Exception {
        UiObject2 account = waitAnyText(2_000, label);
        if (account == null) {
            UiObject2 signOut = device.findObject(By.desc("Sign out"));
            if (signOut == null) signOut = device.findObject(By.desc("Se déconnecter"));
            if (signOut != null) click(signOut); // Normal logout to change the two authorized test operators.
            account = findTextScrolling(label);
        }
        assertNotNull("The normal local fixture account button is required: " + label, account);
        click(account);
        assertNotNull(waitAnyText(LOAD_TIMEOUT, "Parcels", "Colis", "Logical slots", "Emplacements logiques"));
    }

    private void navigation(String english, String french, String... aliases) throws Exception {
        assertForeground();
        String[] labels = new String[aliases.length + 2];
        labels[0] = english; labels[1] = french;
        System.arraycopy(aliases, 0, labels, 2, aliases.length);
        UiObject2 target = null;
        long deadline = android.os.SystemClock.uptimeMillis() + 5_000;
        do {
            denyFixtureNotificationPermissionIfVisible();
            // Exact labels avoid accidentally clicking history's "Payout preview" text.
            // A page heading can share its name with navigation, so choose the lowest clickable match.
            for (String label : labels) {
                List<UiObject2> candidates = new java.util.ArrayList<>(device.findObjects(By.text(label)));
                candidates.addAll(device.findObjects(By.desc(label)));
                for (UiObject2 candidate : candidates) {
                    // Compose tabs expose visible enabled text without consistently exporting
                    // leaf package/clickable flags. Foreground is guarded before every click.
                    if (!candidate.isEnabled() || candidate.getVisibleBounds().height() == 0) continue;
                    if (target == null || candidate.getVisibleBounds().centerY() > target.getVisibleBounds().centerY()) target = candidate;
                }
            }
            if (target != null) break;
            android.os.SystemClock.sleep(100);
        } while (android.os.SystemClock.uptimeMillis() < deadline);
        assertNotNull("Expected exact visible navigation: " + english, target);
        click(target);
        device.waitForIdle(1_000);
    }

    private void assertReferenceVisible(String value) throws Exception {
        UiObject2 node = requireText(value);
        assertTrue("Reference must be readable inside app content, clear of the floating navigation", referenceInScreenshotBand(node));
        Log.i("SequoReferenceUI", "PASS " + activePackage + " visibly rendered " + value);
    }

    private UiObject2 requireText(String text) throws Exception {
        UiObject2 node = findTextScrolling(text);
        assertNotNull("Reference/text is not visible in the actual app: " + text, node);
        return node;
    }

    private UiObject2 requireAnyText(String english, String french) throws Exception {
        UiObject2 node = waitAnyText(3_000, english, french);
        if (node == null) node = findTextScrolling(english);
        if (node == null) node = findTextScrolling(french);
        assertNotNull("Expected reference validation state", node);
        return node;
    }

    private UiObject2 findTextScrolling(String text) throws Exception {
        UiObject2 found = waitAnyText(2_000, text);
        if (found != null) return found;
        for (Direction direction : Arrays.asList(Direction.DOWN, Direction.UP, Direction.DOWN)) {
            for (int i = 0; i < 24; i++) {
                assertForeground();
                UiObject2 scroll = verticalScrollContainer();
                boolean more = false;
                if (scroll != null) {
                    try { more = scroll.scroll(direction, 0.75f); }
                    catch (StaleObjectException changed) { /* Reacquire bounds for the official gesture below. */ }
                }
                found = waitAnyText(400, text);
                if (found != null) return found;
                // Compose may report false after a successful partial scroll. It is not a
                // reliable end-of-list signal; continue bounded gestures inside app content.
                if (!more) {
                    swipeAppContent(direction);
                    found = waitAnyText(400, text);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }

    private void swipeAppContent(Direction direction) {
        assertForeground();
        int height = device.getDisplayHeight();
        int width = device.getDisplayWidth();
        Rect bounds = new Rect(0, height / 5, width, height * 4 / 5);
        UiObject2 container = verticalScrollContainer();
        if (container != null) {
            try { bounds = container.getVisibleBounds(); }
            catch (StaleObjectException changed) { /* Use conservative content-only bounds. */ }
        }
        int top = Math.max(bounds.top + 24, height / 6);
        int bottom = Math.min(bounds.bottom - 24, height * 5 / 6);
        if (bottom - top < 120) { top = height / 4; bottom = height * 3 / 4; }
        int x = Math.max(width / 5, Math.min(bounds.centerX(), width * 4 / 5));
        // Official UiAutomator gesture; fixed navigation and system bars stay outside the path.
        if (direction == Direction.DOWN) device.swipe(x, bottom, x, top, 36);
        else device.swipe(x, top, x, bottom, 36);
        device.waitForIdle(600);
        android.os.SystemClock.sleep(150);
    }

    private UiObject2 verticalScrollContainer() {
        return device.findObjects(By.scrollable(true)).stream()
                .filter(v -> v.getVisibleBounds().height() > 120)
                .max(Comparator.comparingInt(v -> v.getVisibleBounds().height())).orElse(null);
    }

    private UiObject2 button(String english, String french) throws Exception {
        UiObject2 result = visibleAny(english, french);
        if (result == null) result = findTextScrolling(english);
        if (result == null) result = findTextScrolling(french);
        assertNotNull("Expected existing button: " + english, result);
        return result;
    }

    private UiObject2 waitForEnabledButton(String english, String french) {
        long deadline = android.os.SystemClock.uptimeMillis() + 5_000;
        do {
            assertForeground();
            UiObject2 fresh = visibleAny(english, french);
            try {
                if (fresh != null && actionable(fresh).isEnabled()) return fresh;
            } catch (StaleObjectException changed) { /* Re-read after Compose publishes its new semantics. */ }
            android.os.SystemClock.sleep(100);
        } while (android.os.SystemClock.uptimeMillis() < deadline);
        fail("A correctly entered reference must enable the normal resolver after recomposition");
        return null;
    }

    private UiObject2 actionable(UiObject2 node) {
        UiObject2 current = node;
        for (int i = 0; current != null && i < 5; i++, current = current.getParent()) {
            if (current.isClickable()) return current;
        }
        return node;
    }

    private void click(UiObject2 node) {
        assertForeground();
        String text = node.getText();
        assertFalse("Physical confirmation is forbidden by this suite", "Confirm".equals(text) || "Confirmer".equals(text));
        actionable(node).click();
        device.waitForIdle(1_000);
    }

    private UiObject2 visibleAny(String... texts) {
        for (String text : texts) {
            List<UiObject2> matches = device.findObjects(By.textContains(text));
            boolean reference = text.matches("[0-9A-Z]{3}-[0-9A-Z]{3}-[0-9A-Z]{3}");
            for (UiObject2 match : matches) {
                if (!reference || referenceInScreenshotBand(match)) return match;
            }
        }
        return null;
    }

    private boolean referenceInScreenshotBand(UiObject2 node) {
        Rect bounds = node.getVisibleBounds();
        int height = device.getDisplayHeight();
        return bounds.height() > 0 && bounds.width() > 0
                && bounds.centerY() >= height * 18 / 100
                && bounds.centerY() <= height * 75 / 100;
    }

    private UiObject2 waitAnyText(long timeout, String... texts) {
        long end = android.os.SystemClock.uptimeMillis() + timeout;
        do {
            denyFixtureNotificationPermissionIfVisible();
            UiObject2 found = visibleAny(texts);
            if (found != null) return found;
            device.waitForIdle(250);
            android.os.SystemClock.sleep(100);
        } while (android.os.SystemClock.uptimeMillis() < end);
        return null;
    }

    private void setReturnField(int index, String value) {
        assertForeground();
        assertNotNull("Fields may only be edited on the existing return-intake screen",
                visibleAny("Customer return intake", "Réception de retour client"));
        List<UiObject2> inputs = device.findObjects(By.clazz("android.widget.EditText"));
        inputs.sort(Comparator.comparingInt(v -> v.getVisibleBounds().top));
        assertTrue("Expected return reference/PIN fields", inputs.size() > index);
        inputs.get(index).setText(value);
        if (index == 1) hubDummyPinEntered = "000000".equals(value);
    }

    private boolean controlledHubReturnForm() {
        if (!HUB.equals(activePackage) || sheet == null || !hubDummyPinEntered
                || visibleAny("Customer return intake", "Réception de retour client") == null) return false;
        String reference = sheet.optString("returnLookup");
        String typo = reference.substring(0, reference.length() - 1) + (reference.endsWith("0") ? "1" : "0");
        List<String> publicValues = Arrays.asList(reference, reference.toLowerCase(Locale.ROOT).replace("-", ""), typo);
        boolean sawReference = false, sawDummy = false;
        for (UiObject2 input : device.findObjects(By.clazz("android.widget.EditText"))) {
            String value = input.getText();
            if (value == null) return false;
            if (publicValues.contains(value)) sawReference = true;
            else if ("000000".equals(value)) sawDummy = true;
            else if (!value.isBlank()) return false; // Includes any retained note or unknown credential.
        }
        return sawReference && sawDummy;
    }

    private void hideKeyboardIfVisible() {
        if (device.hasObject(By.pkg("com.google.android.inputmethod.latin"))
                || device.hasObject(By.pkg("com.android.inputmethod.latin"))) device.pressBack();
    }

    private void assertForeground() {
        dismissOnlySystemUiAnr();
        denyFixtureNotificationPermissionIfVisible();
        assertTrue(PACKAGES.contains(activePackage));
        assertEquals("Refusing to operate on another app or system/provider dialog", activePackage, device.getCurrentPackageName());
    }

    private void dismissOnlySystemUiAnr() {
        // A known emulator System UI modal may be acknowledged; never dismiss any SEQUO app ANR.
        UiObject2 systemUi = device.findObject(By.text("System UI isn't responding"));
        if (systemUi != null) {
            UiObject2 wait = device.findObject(By.text("Wait"));
            assertNotNull("System UI modal has no standard Wait action", wait);
            wait.click();
            device.waitForIdle(1_000);
            Log.w("SequoReferenceUI", "Acknowledged emulator System UI ANR with Wait; application ANRs are not ignored");
        }
    }

    private void denyFixtureNotificationPermissionIfVisible() {
        String current = device.getCurrentPackageName();
        if (!"com.google.android.permissioncontroller".equals(current)
                && !"com.android.permissioncontroller".equals(current)) return;
        assertTrue("Permission handling is restricted to a launched fixture app", PACKAGES.contains(activePackage));
        // This suite requests no location/camera/storage permission. Only a fixture's startup
        // notification prompt may be dismissed, and denial is the only permitted action.
        assertNotNull("Unexpected permission dialog; refusing to grant or bypass it",
                device.findObject(By.textContains("notifications")));
        UiObject2 deny = device.findObject(By.res(current, "permission_deny_button"));
        if (deny == null) deny = visibleAny("Don't allow", "Don’t allow", "Ne pas autoriser");
        assertNotNull("The standard notification denial button is required", deny);
        deny.click();
        assertTrue("Notification prompt did not close after denial",
                device.wait(Until.gone(By.pkg(current)), 5_000));
        Log.i("SequoReferenceUI", "Denied fixture startup notification permission for " + activePackage);
    }

    private void captureReferenceScreen(String filename) {
        assertForeground();
        assertNotNull(screenshotDirectory);
        assertTrue(screenshotDirectory.isDirectory() || screenshotDirectory.mkdirs());
        File image = new File(screenshotDirectory, filename);
        assertTrue("Official UiAutomator screenshot failed", device.takeScreenshot(image));
        Bundle evidence = new Bundle();
        evidence.putString("additionalTestOutputFile_reference_" + filename.replaceAll("[^A-Za-z0-9_]", "_"), image.getAbsolutePath());
        InstrumentationRegistry.getInstrumentation().sendStatus(2, evidence);
        Log.i("SequoReferenceUI", "Reference-only UI evidence: " + image.getAbsolutePath());
    }
}
