package dev.personal.adtprobe;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Fake credential store and queued test only; no Keystore, sign-in, device or alarm calls. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public final class AdtAutoLoginActivityTest {
    private Context context;
    private ActivityController<AdtAutoLoginActivity> controller;
    private AdtAutoLoginActivity activity;
    private AdtAutoLoginActivity.Operations oldOperations;
    private Executor oldExecutor;
    private final Queue<Runnable> work = new ArrayDeque<>();
    private FakeOperations fake;

    @Before public void prepare() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("adt_portal_binding", 0).edit().clear().commit();
        assertTrue(AdtPortalSession.bind(context, "inert-system", "inert-partition"));
        oldOperations = AdtAutoLoginActivity.operations; oldExecutor = AdtAutoLoginActivity.testExecutor;
        fake = new FakeOperations(); AdtAutoLoginActivity.operations = fake;
        AdtAutoLoginActivity.testExecutor = work::add;
        Shadows.shadowOf(context.getSystemService(KeyguardManager.class)).setKeyguardLocked(false);
        Shadows.shadowOf(context.getSystemService(KeyguardManager.class)).setIsDeviceLocked(false);
        open();
    }
    private void open() {
        controller = Robolectric.buildActivity(AdtAutoLoginActivity.class).setup().visible();
        activity = controller.get(); controller.windowFocusChanged(true); idle();
    }
    @After public void close() {
        if (controller != null) controller.pause().stop().destroy();
        AdtAutoLoginActivity.operations = oldOperations; AdtAutoLoginActivity.testExecutor = oldExecutor;
    }

    @Test public void openingDoesNotSaveOrTestAndCredentialsAreNotCapturedInViewStateOrAutofill() {
        assertEquals(0, fake.saves + fake.tests + fake.clears);
        assertTrue(work.isEmpty());
        assertEquals(0, input("ADT username").length()); assertEquals(0, input("ADT password").length());
        assertFalse(button("Test saved login").isEnabled());
        assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
        for (String label : new String[]{"ADT username", "ADT password"}) {
            EditText input = input(label);
            assertFalse(input.isSaveEnabled());
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, input.getImportantForAutofill());
            assertEquals(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS, input.getImportantForContentCapture());
        }
        assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD,
            input("ADT password").getInputType() & InputType.TYPE_MASK_VARIATION);
    }

    @Test public void explicitSaveClearsBothInputsAndTemporarySecretThenRunsOnlyOneBoundedTest() {
        enter(); long started = SystemClock.elapsedRealtime();
        button("Save and test automatic login").performClick();
        assertEquals(1, fake.saves); assertEquals("inert-user", fake.savedName);
        assertEquals("fixture-password", fake.passwordSeenDuringSave);
        assertArrayEquals(new char[fake.savedArray.length], fake.savedArray);
        assertEquals(0, input("ADT username").length()); assertEquals(0, input("ADT password").length());
        assertFalse(button("Save and test automatic login").isEnabled());
        assertFalse(button("Test saved login").isEnabled()); assertEquals(1, work.size());
        button("Save and test automatic login").performClick(); assertEquals(1, fake.saves);
        finishTest();
        assertEquals(1, fake.tests); assertEquals(started + 20_000, fake.deadline);
        assertTrue(screenText().contains("Inert status checked"));
        assertTrue(screenText().contains("verify a future sign-in"));
        assertFalse(screenText().contains("fixture-password"));
        assertTrue(button("Test saved login").isEnabled());
        assertFalse(ArmExperimentService.isRunning());
    }

    @Test public void failedSaveStillErasesTheTemporaryPasswordAndDoesNotStartTest() {
        fake.saveSucceeds = false; enter(); button("Save and test automatic login").performClick();
        assertArrayEquals(new char[fake.savedArray.length], fake.savedArray);
        assertEquals(0, input("ADT password").length()); assertEquals(0, input("ADT username").length());
        assertFalse(fake.configured); assertEquals(0, fake.tests); assertTrue(work.isEmpty());
        assertTrue(screenText().contains("could not be saved securely"));
    }

    @Test public void existingLoginIsNeverLoadedIntoInputsAndCanBeTestedWithoutResaving() {
        fake.configured = true;
        controller.recreate(); activity = controller.get(); controller.windowFocusChanged(true); idle();
        assertEquals(0, input("ADT username").length()); assertEquals(0, input("ADT password").length());
        button("Test saved login").performClick(); finishTest();
        assertEquals(0, fake.saves); assertEquals(1, fake.tests); assertTrue(fake.configured);
    }

    @Test public void openingWithASavedLoginSaysWhatAutomaticLoginIsDoing() {
        fake.configured = true; fake.summary = "Automatic login is paused after SUBMIT/HTTP/401.";
        controller.recreate(); activity = controller.get(); controller.windowFocusChanged(true); idle();
        assertTrue(screenText().contains("paused after SUBMIT/HTTP/401"));
        assertTrue(screenText().contains("Tap Test saved login"));
        assertEquals(0, fake.tests); assertTrue(work.isEmpty());
    }

    @Test public void missingBindingRequiresSetupAndManualSignInDoesNotSelectOrSendAnything() {
        context.getSharedPreferences("adt_portal_binding", 0).edit().clear().commit();
        controller.recreate(); activity = controller.get(); controller.windowFocusChanged(true); idle();
        fake.configured = true;
        assertTrue(screenText().contains("select your ADT system"));
        assertFalse(button("Save and test automatic login").isEnabled());
        assertFalse(button("Test saved login").isEnabled());
        button("Save and test automatic login").performClick(); button("Test saved login").performClick();
        assertEquals(0, fake.saves + fake.tests); assertTrue(work.isEmpty());
        button("Open ADT sign-in").performClick();
        Intent opened = Shadows.shadowOf(activity).getNextStartedActivity();
        assertEquals(AdtPortalSetupActivity.class.getName(), opened.getComponent().getClassName());
        assertNull(AdtPortalSession.binding(context));
    }

    @Test public void aSessionVerifiedOnTheAdtHostIsFlaggedBecauseLoginUsesAlarmComOnly() {
        assertFalse(screenText().contains("last verified ADT session"));
        AdtPortalSession.recordVerifiedOrigin(context, "https://smartservices.adt.co.uk");
        try {
            controller.recreate(); activity = controller.get(); controller.windowFocusChanged(true); idle();
            assertTrue(screenText().contains("last verified ADT session was on smartservices.adt.co.uk"));
            assertTrue(screenText().contains("www.alarm.com only"));
            AdtPortalSession.recordVerifiedOrigin(context, AdtLoginClient.ORIGIN);
            controller.recreate(); activity = controller.get(); controller.windowFocusChanged(true); idle();
            assertFalse("An alarm.com session needs no warning", screenText().contains("last verified ADT session"));
        } finally { context.getSharedPreferences("adt_portal_session", 0).edit().clear().commit(); }
    }

    @Test public void pauseCancelsQueuedTestAndClearsUnsubmittedInputsWithoutAutomaticRetry() {
        enter(); controller.pause();
        assertEquals(0, input("ADT username").length()); assertEquals(0, input("ADT password").length());
        controller.resume().windowFocusChanged(true); idle();
        fake.configured = true; controller.windowFocusChanged(true);
        button("Test saved login").performClick(); assertEquals(1, work.size());
        controller.pause(); assertEquals(1, fake.cancels);
        finishTest(); assertEquals(0, fake.tests);
        controller.resume().windowFocusChanged(true); idle();
        assertTrue(screenText().contains("cancelled")); assertTrue(work.isEmpty());
        assertEquals(0, fake.tests); assertTrue(fake.configured);
    }

    @Test public void completionPostedBeforePauseCannotBecomeSuccessAfterReturn() {
        enter(); button("Save and test automatic login").performClick();
        work.remove().run(); // The worker finished, but the UI has not accepted its answer.
        controller.pause(); idle(); controller.resume().windowFocusChanged(true); idle();
        assertEquals(1, fake.tests); assertEquals(1, fake.cancels);
        assertFalse(screenText().contains("Inert status checked"));
        assertTrue(screenText().contains("cancelled"));
    }

    @Test public void deadlineCancelsWorkerAndDoesNotRetryOrForgetSavedLogin() {
        enter(); button("Save and test automatic login").performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20));
        assertEquals(1, fake.cancels); assertTrue(screenText().contains("timed out"));
        finishTest(); assertEquals(0, fake.tests); assertTrue(fake.configured);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30));
        assertTrue(work.isEmpty()); assertEquals(0, fake.tests); assertEquals(0, fake.clears);
    }

    @Test public void forgetCancelsTestBeforeDeletingLoginAndLateResultCannotRestoreSuccess() {
        AdtPortalSession.Binding selected = AdtPortalSession.binding(context);
        enter(); button("Save and test automatic login").performClick();
        work.remove().run();
        button("Forget saved login").performClick(); idle();
        assertEquals(1, fake.cancels); assertEquals(1, fake.clears); assertFalse(fake.configured);
        assertTrue(fake.cancelledBeforeClear);
        assertFalse(button("Test saved login").isEnabled());
        assertTrue(screenText().contains("Saved login removed"));
        assertFalse(screenText().contains("Inert status checked"));
        assertTrue(AdtPortalSession.valid(context, selected));
    }

    @Test public void manualSignInCancelsTestAndKeepsSavedCredentialsAndBinding() {
        AdtPortalSession.Binding selected = AdtPortalSession.binding(context);
        enter(); button("Save and test automatic login").performClick();
        button("Open ADT sign-in").performClick(); finishTest();
        assertEquals(1, fake.cancels); assertEquals(0, fake.tests + fake.clears);
        assertTrue(fake.configured); assertTrue(AdtPortalSession.valid(context, selected));
        assertEquals(AdtPortalSetupActivity.class.getName(),
            Shadows.shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
    }

    @Test public void lockedPhoneAndLostFocusCannotStartOrContinueATest() {
        enter(); Shadows.shadowOf(context.getSystemService(KeyguardManager.class)).setIsDeviceLocked(true);
        button("Save and test automatic login").performClick(); assertEquals(0, fake.saves);
        Shadows.shadowOf(context.getSystemService(KeyguardManager.class)).setIsDeviceLocked(false);
        button("Save and test automatic login").performClick();
        controller.windowFocusChanged(false); finishTest();
        assertEquals(1, fake.cancels); assertEquals(0, fake.tests);
        assertFalse(screenText().contains("Inert status checked"));
        assertFalse(button("Test saved login").isEnabled());
    }

    @Test public void transientKeyboardWindowFocusKeepsEntryEditableUntilActivityPauses() {
        enter(); EditText field = input("ADT password"); field.requestFocus();
        controller.windowFocusChanged(false); idle();
        assertTrue(input("ADT username").isEnabled()); assertTrue(field.isEnabled());
        assertTrue(field.hasFocus());
        assertEquals("inert-user", input("ADT username").getText().toString());
        assertEquals("fixture-password", field.getText().toString());
        button("Save and test automatic login").performClick(); assertEquals(0, fake.saves);
        controller.windowFocusChanged(true); idle();
        assertTrue(field.isEnabled()); assertTrue(field.hasFocus());
        controller.pause(); idle();
        assertEquals("", field.getText().toString()); assertEquals("", input("ADT username").getText().toString());
    }

    @Test public void workerExceptionNeverDisplaysItsDetails() {
        fake.throwOnTest = true; enter(); button("Save and test automatic login").performClick(); finishTest();
        assertTrue(screenText().contains("could not finish"));
        assertFalse(screenText().contains("fixture-exception-detail"));
        assertTrue(fake.configured); assertFalse(ArmExperimentService.isRunning());
    }

    @Test public void verificationRequiredKeepsManualSignInAvailableWithoutRetrying() {
        fake.answer = new AdtAutoLoginActivity.Result(false, "Complete verification through Open ADT sign-in.");
        enter(); button("Save and test automatic login").performClick(); finishTest();
        assertTrue(screenText().contains("Complete verification"));
        assertFalse(screenText().contains("verify a future sign-in"));
        assertTrue(button("Open ADT sign-in").isEnabled());
        assertTrue(fake.configured); assertEquals(1, fake.tests); assertTrue(work.isEmpty());
    }

    private void enter() { input("ADT username").setText("inert-user"); input("ADT password").setText("fixture-password"); }
    private void finishTest() { work.remove().run(); idle(); }
    private void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private Button button(String label) {
        Button result = find(activity.getWindow().getDecorView(), Button.class, label); assertNotNull(result); return result;
    }
    private EditText input(String hint) {
        EditText result = find(activity.getWindow().getDecorView(), EditText.class, hint); assertNotNull(result); return result;
    }
    private String screenText() { return collectText(activity.getWindow().getDecorView()); }
    private static String collectText(View view) {
        StringBuilder result = new StringBuilder();
        if (view instanceof TextView) result.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            result.append(collectText(((ViewGroup) view).getChildAt(i)));
        return result.toString();
    }
    private static <T extends View> T find(View view, Class<T> type, String label) {
        if (type.isInstance(view) && view instanceof TextView && label.contentEquals(
                view instanceof EditText ? ((EditText) view).getHint() : ((TextView) view).getText())) return type.cast(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            T found = find(((ViewGroup) view).getChildAt(i), type, label); if (found != null) return found;
        }
        return null;
    }
    private static final class FakeOperations implements AdtAutoLoginActivity.Operations {
        boolean configured, saveSucceeds = true, cancelledBeforeClear, throwOnTest;
        String summary;
        int saves, tests, clears, cancels;
        long deadline;
        char[] savedArray;
        String savedName, passwordSeenDuringSave;
        AdtAutoLoginActivity.Result answer = new AdtAutoLoginActivity.Result(true, "Inert status checked.");
        @Override public boolean configured(Context context) { return configured; }
        @Override public boolean save(Context context, String username, char[] password) {
            saves++; savedName = username; savedArray = password; passwordSeenDuringSave = new String(password);
            if (saveSucceeds) configured = true;
            return saveSucceeds;
        }
        @Override public void clear(Context context) { clears++; cancelledBeforeClear = cancels > 0; configured = false; }
        @Override public AdtAutoLoginActivity.Result test(Context context, long expires) {
            tests++; deadline = expires;
            if (throwOnTest) throw new IllegalStateException("fixture-exception-detail");
            return answer;
        }
        @Override public void cancelTest() { cancels++; }
        @Override public String describe(Context context) { return summary; }
    }
}
