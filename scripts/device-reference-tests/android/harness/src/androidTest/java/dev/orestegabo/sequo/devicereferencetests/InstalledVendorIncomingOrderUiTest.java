package dev.orestegabo.sequo.devicereferencetests;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.StaleObjectException;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import java.io.File;
import android.util.Log;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Explicit synthetic merchant actions through the installed app only. No HTTP, seed/reset or payment API. */
@RunWith(AndroidJUnit4.class)
public final class InstalledVendorIncomingOrderUiTest {
    private static final String VENDOR = "dev.orestegabo.sequovendor.fixture";
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final long LOAD_TIMEOUT = 30_000L;
    private static final String[] TITLES = {"New paid order", "Nouvelle commande payée"};
    private UiDevice device;
    private String expectedReference;
    private String action;
    private int amountCfa;
    private File screenshotDirectory;

    @Rule public final TestWatcher officialFailureEvidence = new TestWatcher() {
        @Override protected void failed(Throwable error, Description description) {
            if (device == null || screenshotDirectory == null || !VENDOR.equals(device.getCurrentPackageName())) return;
            if (device.hasObject(By.clazz("android.widget.EditText")) ||
                    !device.findObjects(By.text(Pattern.compile("[0-9]{6}"))).isEmpty()) {
                Log.w("SequoIncomingUI", "Failure screenshot omitted because editable/possible credential content is present");
                return;
            }
            try {
                assertTrue(screenshotDirectory.isDirectory() || screenshotDirectory.mkdirs());
                File image = new File(screenshotDirectory, "incoming-failure-" + action + "-" + expectedReference + ".png");
                assertTrue("Official UiAutomator screenshot failed", device.takeScreenshot(image));
                Bundle evidence = new Bundle();
                evidence.putString("additionalTestOutputFile_incoming_failure", image.getAbsolutePath());
                InstrumentationRegistry.getInstrumentation().sendStatus(2, evidence);
                Log.i("SequoIncomingUI", "Guarded local failure evidence: " + image.getAbsolutePath());
            } catch (Throwable unavailable) {
                Log.w("SequoIncomingUI", "Failure screenshot unavailable: " + unavailable.getClass().getSimpleName());
            }
        }
        @Override protected void finished(Description description) {
            // TestWatcher.failed runs before finished; capture the actual failure before normal Home cleanup.
            leaveFixtureInBackgroundWithoutChangingItsData();
        }
    };

    @Before public void requireExplicitSyntheticSceneAndDedicatedEmulator() {
        Bundle args = InstrumentationRegistry.getArguments();
        assertEquals("LOCAL_FIXTURE", args.getString("fixtureMode"));
        assertEquals("emulator-5554", args.getString("expectedSerial"));
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        assertTrue("Physical hardware is forbidden", hardware.contains("ranchu") || hardware.contains("goldfish"));
        assertEquals("Only the dedicated API36 emulator is supported", 36, Build.VERSION.SDK_INT);
        expectedReference = args.getString("incomingExpectedReference", "");
        assertTrue("An actual canonical merchant-part reference with a valid checksum is required", validMerchantReference(expectedReference));
        action = args.getString("incomingAction", "");
        assertTrue("Explicit incomingAction must be accept, refuse or wait", List.of("accept", "refuse", "wait").contains(action));
        String amount = args.getString("incomingExpectedAmountCfa", "");
        assertTrue("Pass the actual fresh fixture subtotal, not a default estimate", amount.matches("[1-9][0-9]{0,6}"));
        amountCfa = Integer.parseInt(amount);
        assertTrue("Only a bounded synthetic fixture amount is accepted", amountCfa <= 1_000_000);
        String output = args.getString("additionalTestOutputDir");
        assertNotNull("Official Gradle additional-test-output collection is required", output);
        screenshotDirectory = new File(output);
        assertTrue("Evidence must remain in the official absolute test output directory", screenshotDirectory.isAbsolute());
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    }

    private void leaveFixtureInBackgroundWithoutChangingItsData() {
        if (device != null && VENDOR.equals(device.getCurrentPackageName())) device.pressHome();
    }

    @Test public void incomingOrderUsesExistingMerchantAction() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(VENDOR);
        assertNotNull("Install the identified LOCAL_FIXTURE Vendor APK first", intent);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        context.startActivity(intent);
        waitForVendorForeground();
        enterYawaOnlyIfSignedOut();
        requireExact(LOAD_TIMEOUT, TITLES);
        assertNotNull("The incoming dialog must contain the exact newly-created scoped part", exact(expectedReference));
        assertNotNull("The normal fixture has quantity two, rendered from the actual server line",
                device.findObject(By.text(Pattern.compile("2\\s*×\\s*.+"))));
        assertTrue("The incoming dialog must show the server subtotal supplied by the fixture readback", amountVisible());

        if ("wait".equals(action)) {
            // Do not silence/dismiss. Observe the app's own server-derived 60-second window.
            awaitIncomingGone(63_000L);
            UiObject2 previousDetail = exact("Back to orders", "Retour aux commandes");
            if (previousDetail != null) clickText("Back to orders", "Retour aux commandes");
            clickNavigation("Orders", "Commandes");
            clickMatching(Pattern.compile("(?:New|Nouvelles) \\([0-9]+\\)"));
            requireExact(5_000L,
                    "Ringing has stopped. You can still accept waiting orders before their deadline.",
                    "La sonnerie est arrêtée. Vous pouvez encore accepter les commandes en attente avant leur échéance.");
            assertScopedRowStatus(LOAD_TIMEOUT, "Waiting for your response", "En attente de votre réponse");
            return;
        }
        if ("accept".equals(action)) clickText("Accept order", "Accepter la commande");
        else clickText("Refuse order", "Refuser la commande");
        awaitIncomingGone(LOAD_TIMEOUT);
        requireExact(LOAD_TIMEOUT, "Order detail", "Detail commande");
        if ("accept".equals(action)) {
            // This affordance exists only for the canonical ACCEPTED server state; never click it.
            requireExact(LOAD_TIMEOUT, "Start preparation", "Commencer la preparation");
            assertScopedRowStatus(LOAD_TIMEOUT, "Accepted", "Acceptee");
        } else {
            assertScopedRowStatus(LOAD_TIMEOUT, "Rejected", "Refusee");
            assertNull("Refusal must not expose an accepted-order preparation action", exact("Start preparation", "Commencer la preparation"));
        }
        assertNull("An error message is not a successful action readback", exact(
                "The order action could not be completed. Try again.", "L'action sur la commande n'a pas abouti. Reessayez."));
    }

    /** Read-only diagnosis against an already-created waiting part; never consumes a new scene. */
    @Test public void existingWaitingQueueIsReadable() {
        assertEquals("This diagnostic performs no order action; pass incomingAction=wait", "wait", action);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(VENDOR);
        assertNotNull("The identified fixture Vendor APK must already be installed", intent);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        context.startActivity(intent);
        waitForVendorForeground();
        enterYawaOnlyIfSignedOut();
        assertNull("Use an existing waiting scene, not an actively ringing fresh scene", exact(TITLES));
        logOnlyPublicNavigationCandidates();
        clickReadOnlyNavigationViaActionableParent();
        clickMatching(Pattern.compile("(?:New|Nouvelles) \\([0-9]+\\)"));
        requireExact(LOAD_TIMEOUT, expectedReference);
        assertTrue("The existing real waiting part must retain its server subtotal", amountVisible());
        assertScopedRowStatus(LOAD_TIMEOUT, "Waiting for your response", "En attente de votre réponse");
    }

    private void logOnlyPublicNavigationCandidates() {
        assertForeground();
        for (String label : new String[]{"Orders", "Commandes"}) {
            for (String source : new String[]{"text", "description"}) {
                List<UiObject2> nodes = source.equals("text") ? device.findObjects(By.text(label)) : device.findObjects(By.desc(label));
                Log.i("SequoIncomingUI", "navigation " + source + " fixedLabel=" + label + " count=" + nodes.size());
                for (UiObject2 candidate : nodes) {
                    try {
                        Log.i("SequoIncomingUI", "candidate " + source + " fixedLabel=" + label + " enabled=" + candidate.isEnabled()
                                + " clickable=" + candidate.isClickable() + " bounds=" + candidate.getVisibleBounds());
                        UiObject2 parent = candidate;
                        for (int level = 0; parent != null && level < 5; level++, parent = parent.getParent()) {
                            if (parent.isClickable()) {
                                Log.i("SequoIncomingUI", "actionable fixedLabel=" + label + " depth=" + level + " enabled=" + parent.isEnabled()
                                        + " bounds=" + parent.getVisibleBounds());
                                break;
                            }
                        }
                    } catch (StaleObjectException changed) { Log.i("SequoIncomingUI", "navigation fixedLabel=" + label + " transientNode=true"); }
                }
            }
        }
    }

    private void clickReadOnlyNavigationViaActionableParent() {
        long deadline = SystemClock.uptimeMillis() + LOAD_TIMEOUT;
        do {
            assertForeground();
            UiObject2 oldDetail = exact("Back to orders", "Retour aux commandes");
            if (oldDetail != null && clickIfStillCurrent(oldDetail)) continue;
            UiObject2 lowest = null;
            int lowestY = -1;
            for (String label : new String[]{"Orders", "Commandes"}) {
                List<UiObject2> nodes = new ArrayList<>(device.findObjects(By.text(label)));
                nodes.addAll(device.findObjects(By.desc(label)));
                for (UiObject2 candidate : nodes) {
                    try {
                        UiObject2 target = actionable(candidate);
                        // A Compose leaf can be disabled or have empty geometry while its actual
                        // clickable navigation parent is enabled and visibly laid out.
                        if (target == null || !target.isEnabled() ||
                                target.getVisibleBounds().height() == 0 || target.getVisibleBounds().width() == 0) continue;
                        int y = target.getVisibleBounds().centerY();
                        if (y > lowestY) { lowest = target; lowestY = y; }
                    } catch (StaleObjectException changed) { /* Reacquire the normal actionable parent. */ }
                }
            }
            if (lowest != null && clickIfStillCurrent(lowest)) return;
            SystemClock.sleep(100L);
        } while (SystemClock.uptimeMillis() < deadline);
        logOnlyPublicNavigationCandidates();
        fail("Read-only normal Orders navigation unavailable; " + publicNavigationState());
    }

    private void enterYawaOnlyIfSignedOut() {
        long deadline = SystemClock.uptimeMillis() + LOAD_TIMEOUT;
        boolean chooserOpened = false;
        boolean accountSubmitted = false;
        do {
            assertForeground();
            // Stored-session restoration can replace the temporary auth screen between snapshots.
            // Non-click readiness never requires an enabled accessibility leaf.
            if (workspaceOrIncomingPresent()) return;
            UiObject2 account = exact("[TEST] Yawa Adjei");
            if (account != null && !accountSubmitted) {
                if (workspaceOrIncomingPresent()) return;
                if (clickIfStillCurrent(account)) accountSubmitted = true;
            } else if (!chooserOpened && !accountSubmitted) {
                UiObject2 chooser = exact("Local test merchant accounts", "Comptes commerçants de test local");
                if (chooser != null) {
                    if (workspaceOrIncomingPresent()) return;
                    if (clickIfStillCurrent(chooser)) chooserOpened = true;
                }
            }
            SystemClock.sleep(100L);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Use the existing Yawa session or normal server-offered local merchant chooser; no auth bypass is permitted");
    }

    private boolean workspaceOrIncomingPresent() {
        for (String title : TITLES) if (device.hasObject(By.text(title))) return true;
        return device.hasObject(By.text("Orders")) || device.hasObject(By.text("Commandes"));
    }

    private void waitForVendorForeground() {
        long deadline = SystemClock.uptimeMillis() + LOAD_TIMEOUT;
        do {
            dismissNotificationPromptOnly();
            if (VENDOR.equals(device.getCurrentPackageName()) && device.hasObject(By.pkg(VENDOR))) return;
            SystemClock.sleep(100L);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("The installed fixture Vendor app did not reach foreground");
    }

    private void awaitIncomingGone(long timeout) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        long absentSince = 0L;
        do {
            assertForeground();
            try {
                // Query title presence without dereferencing a countdown's rapidly replaced node.
                boolean titlePresent = false;
                for (String title : TITLES) if (!device.findObjects(By.text(title)).isEmpty()) titlePresent = true;
                long now = SystemClock.uptimeMillis();
                if (titlePresent) absentSince = 0L;
                else if (absentSince == 0L) absentSince = now;
                else if (now - absentSince >= 500L) return;
            } catch (StaleObjectException changed) {
                absentSince = 0L; // A changing snapshot does not prove the dialog disappeared.
            }
            SystemClock.sleep(100L);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("The incoming overlay did not finish within its bounded server-derived window");
    }

    private void assertScopedRowStatus(long timeout, String... statuses) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        long visibleReferenceSince = 0L;
        do {
            assertForeground();
            boolean referenceVisible = false;
            for (UiObject2 reference : device.findObjects(By.text(expectedReference))) {
                try {
                    android.graphics.Rect bounds = reference.getVisibleBounds();
                    if (bounds.height() == 0 || bounds.width() == 0) continue;
                    int screenHeight = device.getDisplayHeight();
                    referenceVisible = referenceVisible || (bounds.centerY() >= screenHeight * 18 / 100 &&
                            bounds.centerY() <= screenHeight * 75 / 100);
                    UiObject2 row = reference;
                    // The shared title/status row is a close ancestor, never an unrelated order elsewhere.
                    for (int level = 0; row != null && level < 3; level++, row = row.getParent()) {
                        for (String status : statuses) if (row.findObject(By.text(status)) != null) return;
                    }
                } catch (StaleObjectException changed) { /* Reacquire canonical Compose semantics. */ }
            }
            long now = SystemClock.uptimeMillis();
            // Detail has a duplicate reference in its fixed heading; only the wait-case queue
            // target needs a fresh poll. Accept/refuse retain their bounded detail-card search.
            if ("wait".equals(action) && referenceVisible && visibleReferenceSince == 0L) visibleReferenceSince = now;
            if (visibleReferenceSince != 0L) {
                // The overlay closes on its monotonic clock; the status label uses the next
                // authoritative five-second queue poll. Keep the matching card in view, including
                // a brief Compose replacement, instead of scrolling past the exact target.
                if (now - visibleReferenceSince >= 7_500L) {
                    fail("Exact part stayed in view but its expected server status did not arrive after a fresh poll: " + expectedReference);
                }
                SystemClock.sleep(250L);
                continue;
            }
            // Only search by scrolling while no matching reference has been found.
            device.swipe(device.getDisplayWidth() / 2, device.getDisplayHeight() * 70 / 100,
                    device.getDisplayWidth() / 2, device.getDisplayHeight() * 35 / 100, 20);
            SystemClock.sleep(250L);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Expected server status on the exact merchant part " + expectedReference);
    }

    private boolean amountVisible() {
        long deadline = SystemClock.uptimeMillis() + 3_000L;
        do {
            assertForeground();
            for (UiObject2 node : device.findObjects(By.text(Pattern.compile(".*CFA.*")))) {
                try {
                    String text = node.getText();
                    if (text != null && text.replaceAll("[\\s\\u00a0\\u202f]", "").equals(amountCfa + "CFA")) return true;
                } catch (StaleObjectException changed) { /* Requery the real amount after recomposition. */ }
            }
            SystemClock.sleep(100L);
        } while (SystemClock.uptimeMillis() < deadline);
        return false;
    }

    private void clickNavigation(String... texts) {
        long deadline = SystemClock.uptimeMillis() + LOAD_TIMEOUT;
        do {
            assertForeground();
            // A previously open detail can reappear only after dialog dismissal/recomposition.
            // Use its normal Back action before selecting the same Orders tab again.
            UiObject2 oldDetail = exact("Back to orders", "Retour aux commandes");
            if (oldDetail != null && clickIfStillCurrent(oldDetail)) { SystemClock.sleep(250L); continue; }
            UiObject2 lowest = null;
            int lowestY = -1;
            for (String text : texts) {
                // Match the existing proven reference harness: Compose tabs can expose either
                // visible text or an exact content description, depending on role/semantics merging.
                List<UiObject2> candidates = new ArrayList<>(device.findObjects(By.text(text)));
                candidates.addAll(device.findObjects(By.desc(text)));
                for (UiObject2 node : candidates) {
                    try {
                        if (!node.isEnabled() || node.getVisibleBounds().height() == 0) continue;
                        int y = node.getVisibleBounds().centerY();
                        if (y > lowestY) { lowest = node; lowestY = y; }
                    } catch (StaleObjectException changed) { /* Requery; do not retain stale bounds. */ }
                }
            }
            if (lowest != null && clickIfStillCurrent(lowest)) return;
            SystemClock.sleep(100L);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("The existing bottom Orders navigation must be visible and enabled; " + publicNavigationState());
    }
    private String publicNavigationState() {
        StringBuilder value = new StringBuilder("package=" + device.getCurrentPackageName());
        for (String title : new String[]{"Orders", "Commandes", "Order detail", "Detail commande",
                "Back to orders", "Retour aux commandes", "New paid order", "Nouvelle commande payée"}) {
            value.append("; ").append(title).append(" text=").append(device.findObjects(By.text(title)).size())
                    .append(" desc=").append(device.findObjects(By.desc(title)).size());
        }
        return value.toString(); // Only fixed public labels/counts; never a hierarchy or arbitrary app text.
    }
    private void clickText(String... texts) {
        long deadline = SystemClock.uptimeMillis() + 5_000L;
        do {
            assertForeground();
            UiObject2 node = exact(texts);
            if (node != null && clickIfStillCurrent(node)) return;
            SystemClock.sleep(100L);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("The expected normal UI action must remain visible and enabled: " + String.join(" / ", texts));
    }
    private void clickMatching(Pattern pattern) {
        long deadline = SystemClock.uptimeMillis() + 5_000L;
        do {
            assertForeground();
            UiObject2 node = device.findObject(By.text(pattern));
            if (node != null && clickIfStillCurrent(node)) return;
            SystemClock.sleep(100L);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("The normal New pending-order tab is required");
    }
    private UiObject2 actionable(UiObject2 node) {
        UiObject2 current = node;
        for (int i = 0; current != null && i < 5; i++, current = current.getParent()) {
            if (current.isClickable()) return current;
        }
        // Same as the proven InstalledReferenceUiTest: Compose can omit clickable semantics
        // while exposing a visible enabled text/tab node. Use its normal UiAutomator click.
        return node;
    }
    private boolean clickIfStillCurrent(UiObject2 node) {
        assertForeground();
        UiObject2 button;
        try {
            if (node.getVisibleBounds().height() == 0 || node.getVisibleBounds().width() == 0) return false;
            button = actionable(node);
            if (!button.isEnabled() || button.getVisibleBounds().height() == 0 || button.getVisibleBounds().width() == 0) return false;
        } catch (StaleObjectException changed) {
            return false; // Safe to reacquire: no click has been submitted yet.
        }
        // Deliberately outside the stale-query catch: an uncertain submitted business click must
        // fail, never automatically submit Accept/Refuse again under a guessed outcome.
        button.click();
        SystemClock.sleep(200L);
        return true;
    }
    private UiObject2 requireExact(long timeout, String... texts) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        do {
            assertForeground();
            UiObject2 node = exact(texts);
            if (node != null) return node;
            SystemClock.sleep(100L);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Required actual app text was not visible: " + String.join(" / ", texts));
        return null;
    }
    private UiObject2 exact(String... texts) {
        for (int attempt = 0; attempt < 3; attempt++) {
            boolean changed = false;
            for (String text : texts) for (UiObject2 node : device.findObjects(By.text(text))) {
                try {
                    if (node.isEnabled() && node.getVisibleBounds().height() > 0) return node;
                } catch (StaleObjectException stale) { changed = true; }
            }
            if (!changed) return null;
            SystemClock.sleep(50L);
        }
        return null;
    }
    private void assertForeground() {
        dismissNotificationPromptOnly();
        assertEquals("Refusing another app, provider, system prompt or application ANR", VENDOR, device.getCurrentPackageName());
    }
    private void dismissNotificationPromptOnly() {
        String pkg = device.getCurrentPackageName();
        if (!"com.android.permissioncontroller".equals(pkg) && !"com.google.android.permissioncontroller".equals(pkg)) return;
        assertNotNull("Only the fixture's startup notification prompt may be denied", device.findObject(By.textContains("notifications")));
        UiObject2 deny = device.findObject(By.res(pkg, "permission_deny_button"));
        assertNotNull("No permission may be granted by this test", deny);
        deny.click();
        SystemClock.sleep(200L);
    }
    private static boolean validMerchantReference(String reference) {
        if (!reference.matches("M[0-9A-HJKMNP-TV-Z]{2}-[0-9A-HJKMNP-TV-Z]{3}-[0-9A-HJKMNP-TV-Z]{3}")) return false;
        String symbols = reference.replace("-", "");
        int crc = 0;
        for (int i = 0; i < 7; i++) {
            int value = ALPHABET.indexOf(symbols.charAt(i));
            for (int bit = 4; bit >= 0; bit--) {
                int feedback = ((crc >>> 9) ^ (value >>> bit)) & 1;
                crc = (crc << 1) & 0x3ff;
                if (feedback != 0) crc ^= 0x233;
            }
        }
        return symbols.charAt(7) == ALPHABET.charAt(crc >>> 5) && symbols.charAt(8) == ALPHABET.charAt(crc & 31);
    }
}
