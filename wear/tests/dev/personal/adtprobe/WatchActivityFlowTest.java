package dev.personal.adtprobe;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import com.google.android.gms.wearable.MessageEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
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
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;
import static org.junit.Assert.*;

/**
 * Production Activity/store/protocol with an inert Sender and real button touch dispatch.
 * Readiness flags stand in for GMS listener registration; production admission seeds reports.
 * GMS query tasks are never drained: tests supply nonce-matched answers directly, or use an inert transport.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w240dp-h240dp-round-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public final class WatchActivityFlowTest {
    private static final String PHONE = "inert-flow-phone";
    private static final int BOOT = 19;
    private Context context;
    private ActivityController<WatchActivity> controller;
    private WatchActivity activity;
    private final List<Sent> sent = new ArrayList<>();
    private boolean realResume;

    @Before public void prepare() {
        context = RuntimeEnvironment.getApplication();
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, BOOT);
        resetStore();
        Shadows.shadowOf(context.getSystemService(KeyguardManager.class)).setIsDeviceSecure(false);
        Shadows.shadowOf(context.getSystemService(KeyguardManager.class)).setIsDeviceLocked(false);
        Shadows.shadowOf(context.getSystemService(KeyguardManager.class)).setKeyguardLocked(false);
    }

    @After public void close() { closeActivity(); stopQueryTransport(); }

    @Test public void renderPlainLauncherAndResumeNeverAuthorizeAnAlarmAction() {
        report(AlarmStateProtocol.State.DISARMED);
        mount(new Intent(Intent.ACTION_MAIN), null, true);
        render(); render();
        assertTrue(sent.isEmpty());
        assertNull(attempt());
        advance(2_001);
        report(AlarmStateProtocol.State.ARMED_STAY);
        render();
        assertTrue("Displaying the opposite action is not a user tap", sent.isEmpty());

        // Exercise the real resume hook, but do not complete the external listener/query tasks.
        controller.resume(); realResume = true;
        assertTrue("onResume cannot synthesize an alarm request", sent.isEmpty());
        assertNull(attempt());
        controller.pause(); realResume = false;
    }

    @Test public void restoredAndHistoryTileLaunchesAreNotFreshTaps() {
        WatchAlarmStore.ViewState state = report(AlarmStateProtocol.State.DISARMED);
        Intent restored = tile(state.action, state.revision);
        mount(restored, new Bundle(), true);
        assertNull(attempt());
        assertTrue(sent.isEmpty());
        assertFalse("Tile credentials are removed from the Activity Intent", restored.hasExtra(AlarmTileService.EXTRA_TILE_REVISION));
        closeActivity();

        Intent history = tile(state.action, state.revision).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY);
        mount(history, null, true);
        assertNull("Returning through history is not another positive tap", attempt());
        assertTrue(sent.isEmpty());
    }

    @Test public void oneVisibleTapCommitsOnlyItsMatchingChallengeOnceForEachAction() {
        for (AlarmAction action : AlarmAction.values()) {
            closeActivity(); resetStore(); sent.clear();
            WatchAlarmStore.ViewState state = report(action == AlarmAction.ARM_STAY
                ? AlarmStateProtocol.State.DISARMED : AlarmStateProtocol.State.ARMED_STAY);
            mount(new Intent(Intent.ACTION_MAIN), null, true);
            tap();
            assertEquals("A single visible tap sends one readiness request", 1, sent.size());
            Sent prepare = sent.get(0);
            assertEquals(AlarmStateProtocol.TOGGLE_PATH, prepare.path);
            assertEquals(PHONE, prepare.node);
            AlarmStateProtocol.Tap selected = AlarmStateProtocol.parseTap(prepare.payload);
            assertNotNull(selected);
            assertEquals(action, selected.action);
            assertNotEquals("The local click token is not the state revision sent to the phone", state.revision, selected.revision);
            assertEquals(ArmExperimentProtocol.Phase.AWAITING_CHALLENGE, attempt().phase());
            assertFalse(controls().alarmButton.isEnabled());
            String challenge = UUID.randomUUID().toString();
            AlarmAction other = action == AlarmAction.ARM_STAY ? AlarmAction.DISARM : AlarmAction.ARM_STAY;
            handle("other-phone", ArmExperimentProtocol.CHALLENGE_PATH,
                ArmExperimentProtocol.encodeChallenge(action, selected.request, challenge));
            handle(PHONE, ArmExperimentProtocol.CHALLENGE_PATH,
                ArmExperimentProtocol.encodeChallenge(other, selected.request, challenge));
            assertEquals(1, sent.size());
            handle(PHONE, ArmExperimentProtocol.CHALLENGE_PATH,
                ArmExperimentProtocol.encodeChallenge(action, selected.request, challenge));
            assertEquals("A matching challenge commits without a second UI gesture", 2, sent.size());
            ArmExperimentProtocol.Message commit = ArmExperimentProtocol.parse(sent.get(1).payload, ArmExperimentProtocol.Kind.COMMIT);
            assertEquals(ArmExperimentProtocol.COMMIT_PATH, sent.get(1).path);
            assertNotNull(commit);
            assertEquals(action, commit.action);
            assertEquals(selected.request, commit.requestId);
            assertEquals(challenge, commit.challengeId);
            handle(PHONE, ArmExperimentProtocol.CHALLENGE_PATH,
                ArmExperimentProtocol.encodeChallenge(action, selected.request, challenge));
            tap();
            ReflectionHelpers.callInstanceMethod(activity, "onAlarmTap", ClassParameter.from(AlarmAction.class, other));
            assertEquals("Duplicate replies or taps cannot send another commit or reverse the action", 2, sent.size());
        }
    }

    @Test public void changedStateBeforeChallengeRequiresAFreshTapForTheOppositeAction() {
        report(AlarmStateProtocol.State.DISARMED);
        mount(new Intent(Intent.ACTION_MAIN), null, true);
        tap();
        AlarmStateProtocol.Tap first = AlarmStateProtocol.parseTap(sent.get(0).payload);
        advance(2_001);
        report(AlarmStateProtocol.State.ARMED_STAY);
        handle(PHONE, ArmExperimentProtocol.CHALLENGE_PATH,
            ArmExperimentProtocol.encodeChallenge(AlarmAction.ARM_STAY, first.request, UUID.randomUUID().toString()));
        assertNull(attempt());
        assertEquals("The old tap cannot commit against a changed state", 1, sent.size());
        assertEquals(AlarmAction.DISARM, WatchAlarmStore.read(context).action);
        tap();
        assertEquals(2, sent.size());
        assertEquals("Only a new physical tap can choose the opposite action", AlarmAction.DISARM,
            AlarmStateProtocol.parseTap(sent.get(1).payload).action);
        assertEquals(0, commits());
    }

    @Test public void focusLossCancelsBeforeCommitAndLateChallengeCannotReviveIt() {
        report(AlarmStateProtocol.State.DISARMED);
        mount(new Intent(Intent.ACTION_MAIN), null, true);
        tap();
        AlarmStateProtocol.Tap first = AlarmStateProtocol.parseTap(sent.get(0).payload);
        controller.windowFocusChanged(false);
        assertNull(attempt());
        controller.windowFocusChanged(true);
        handle(PHONE, ArmExperimentProtocol.CHALLENGE_PATH,
            ArmExperimentProtocol.encodeChallenge(first.action, first.request, UUID.randomUUID().toString()));
        assertEquals(1, sent.size());
        assertEquals(0, commits());
    }

    @Test public void consumedTileTokenCannotReplayAfterTheDisplayedActionReverses() {
        WatchAlarmStore.ViewState original = report(AlarmStateProtocol.State.DISARMED);
        mount(tile(original.action, original.revision), null, true);
        assertEquals("Positive control: the fresh native tile tap begins readiness", 1, sent.size());
        assertEquals(AlarmAction.ARM_STAY, AlarmStateProtocol.parseTap(sent.get(0).payload).action);
        controller.windowFocusChanged(false); // Cancel before any challenge/commit.
        advance(2_001);
        report(AlarmStateProtocol.State.ARMED_STAY);
        controller.windowFocusChanged(true);
        activity.onNewIntent(tile(original.action, original.revision));
        stopQueryTransport();
        assertNull(attempt());
        assertEquals(1, sent.size());
        assertEquals(0, commits());
        WatchAlarmStore.ViewState current = WatchAlarmStore.read(context);
        assertEquals(AlarmAction.DISARM, current.action);
        activity.onNewIntent(tile(current.action, current.revision));
        assertEquals("A fresh token and deliberate new tile tap still work", 2, sent.size());
        assertEquals(AlarmAction.DISARM, AlarmStateProtocol.parseTap(sent.get(1).payload).action);
    }

    @Test public void expiredWrongAndActionMismatchedTileTokensNeverBecomeAnotherAction() {
        WatchAlarmStore.ViewState original = report(AlarmStateProtocol.State.DISARMED);
        mount(tile(original.action, original.revision), null, false);
        advance(10_000);
        report(AlarmStateProtocol.State.ARMED_STAY);
        ready();
        assertNull(attempt());
        assertTrue("Expired launch is dropped even while a fresh opposite action exists", sent.isEmpty());
        WatchAlarmStore.ViewState current = WatchAlarmStore.read(context);
        activity.onNewIntent(tile(current.action, UUID.randomUUID().toString()));
        stopQueryTransport();
        activity.onNewIntent(tile(AlarmAction.ARM_STAY, current.revision));
        stopQueryTransport();
        assertNull(attempt());
        assertTrue(sent.isEmpty());
        assertEquals(AlarmAction.DISARM, WatchAlarmStore.read(context).action);
    }

    @Test public void successfulDisarmShowsCurrentRecoveryStatusAndNewReportEnablesArmWithoutSendingIt() {
        report(AlarmStateProtocol.State.ARMED_STAY);
        mount(new Intent(Intent.ACTION_MAIN), null, true);
        tap();
        AlarmStateProtocol.Tap selected = AlarmStateProtocol.parseTap(sent.get(0).payload);
        String challenge = UUID.randomUUID().toString();
        handle(PHONE, ArmExperimentProtocol.CHALLENGE_PATH,
            ArmExperimentProtocol.encodeChallenge(AlarmAction.DISARM, selected.request, challenge));
        handle(PHONE, ArmExperimentProtocol.RESULT_PATH,
            ArmExperimentProtocol.encodeResult(AlarmAction.DISARM, selected.request, challenge,
                ArmExperimentProtocol.Outcome.REQUESTED));
        stopQueryTransport();
        assertNull(attempt());
        assertFalse(controls().alarmButton.isEnabled());
        assertNull("A fixed request message must not mask changing recovery details",
            ReflectionHelpers.getField(activity, "feedback"));
        assertTrue(controls().alarmButton.getText().toString().contains(WatchAlarmStore.read(context).detail));

        advance(2_001);
        report(AlarmStateProtocol.State.DISARMED, selected.request);
        render();
        assertEquals(AlarmAction.ARM_STAY, WatchAlarmStore.read(context).action);
        assertTrue(controls().alarmButton.isEnabled());
        assertEquals("Status recovery must neither repeat Disarm nor send Arm Stay", 2, sent.size());
        assertEquals(1, commits());
    }

    @Test public void freshPhoneDiagnosisReplacesAnEarlierConnectionError() {
        report(AlarmStateProtocol.State.ARMED_STAY);
        mount(new Intent(Intent.ACTION_MAIN), null, true);
        tap();
        ReflectionHelpers.callInstanceMethod(activity, "endAttempt",
            ClassParameter.from(String.class, "Phone unavailable. Tap Refresh."),
            ClassParameter.from(boolean.class, false));
        stopQueryTransport();
        assertTrue(controls().alarmButton.getText().toString().contains("Phone unavailable"));

        advance(2_001);
        WatchAlarmStore.Query query = WatchAlarmStore.beginQuery(now());
        assertNotNull(query);
        assertTrue(WatchAlarmStore.selectSource(context, query, PHONE, now(), BOOT));
        assertTrue(WatchAlarmStore.accept(context, PHONE, new AlarmStateProtocol.Report(query.nonce,
            AlarmStateProtocol.State.UNKNOWN, AlarmStateProtocol.Availability.NO_ACCESS, "-", 0), now(), BOOT));
        SharedPreferences.OnSharedPreferenceChangeListener changed = ReflectionHelpers.getField(activity, "changed");
        changed.onSharedPreferenceChanged(context.getSharedPreferences(WatchAlarmStore.PREFERENCES,
            Context.MODE_PRIVATE), "contactReceived");
        stopQueryTransport();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(controls().alarmButton.getText().toString().contains("Sign in via ADT Watch Setup"));
        assertFalse(controls().alarmButton.isEnabled());
        assertEquals(1, sent.size());
        assertEquals(0, commits());
    }

    @Test public void matchedEarlyRefusalEndsTheTapWithoutCommitOrOppositeAction() {
        for (AlarmAction action : AlarmAction.values()) {
            closeActivity(); resetStore(); sent.clear();
            WatchAlarmStore.ViewState original = report(action == AlarmAction.DISARM
                    ? AlarmStateProtocol.State.ARMED_STAY : AlarmStateProtocol.State.DISARMED);
            mount(new Intent(Intent.ACTION_MAIN), null, true);
            tap();
            AlarmStateProtocol.Tap selected = AlarmStateProtocol.parseTap(sent.get(0).payload);
            handle(PHONE, AlarmStateProtocol.DECLINED_PATH, new AlarmStateProtocol.Declined(
                    action, selected.request, AlarmStateProtocol.DeclineReason.STATE_CHANGED).encode());
            stopQueryTransport();
            assertNull(attempt());
            assertFalse(stored().getBoolean("busy", false));
            assertFalse(stored().getBoolean("awaiting", false));
            assertFalse(stored().getBoolean("commitMayHaveBeenSent", false));
            assertEquals(1, sent.size());
            assertEquals(0, commits());
            assertTrue(controls().noticeView.getText().toString().contains("Not sent"));

            // The phone's later state report may offer the opposite action, but
            // the refused tap has no authority to perform it or accept a late challenge.
            advance(2_001);
            report(action == AlarmAction.DISARM ? AlarmStateProtocol.State.DISARMED : AlarmStateProtocol.State.ARMED_STAY);
            render();
            handle(PHONE, ArmExperimentProtocol.CHALLENGE_PATH,
                    ArmExperimentProtocol.encodeChallenge(action, selected.request, UUID.randomUUID().toString()));
            assertEquals(1, sent.size());
            assertNull(attempt());
            assertNull(WatchAlarmStore.consume(context, original.revision, WatchAlarmStore.read(context).action));
        }
    }

    @Test public void alreadySatisfiedPreflightRefreshesWithoutCommittingOrReversingTheTap() {
        WatchAlarmStore.StatusTransport originalTransport = ReflectionHelpers.getStaticField(WatchAlarmStore.class, "transport");
        ReflectionHelpers.setStaticField(WatchAlarmStore.class, "transport", new WatchAlarmStore.StatusTransport() {
            @Override public void discover(Context ignored, Consumer<String> found, Runnable failed) { found.accept(PHONE); }
            @Override public void query(Context ignored, String phone, String nonce, AlarmStateProtocol.QueryIntent intent, Runnable failed) { }
        });
        try { for (AlarmAction action : AlarmAction.values()) {
            closeActivity(); resetStore(); sent.clear();
            WatchAlarmStore.ViewState original = report(action == AlarmAction.DISARM
                    ? AlarmStateProtocol.State.ARMED_STAY : AlarmStateProtocol.State.DISARMED);
            mount(new Intent(Intent.ACTION_MAIN), null, true);
            tap();
            AlarmStateProtocol.Tap selected = AlarmStateProtocol.parseTap(sent.get(0).payload);

            handle(PHONE, AlarmStateProtocol.DECLINED_PATH, new AlarmStateProtocol.Declined(
                    action, selected.request, AlarmStateProtocol.DeclineReason.ALREADY_SATISFIED).encode());
            assertNull(attempt());
            assertFalse(stored().getBoolean("busy", false));
            assertFalse(stored().getBoolean("awaiting", false));
            assertFalse(stored().getBoolean("commitMayHaveBeenSent", false));
            // Run only the inert status transport so the posted recovery can start.
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue("Already satisfied still fetches the current ADT state", WatchAlarmStore.isRefreshing());
            assertEquals(1, sent.size());
            assertEquals(0, commits());
            stopQueryTransport();

            advance(2_001);
            report(action == AlarmAction.DISARM
                    ? AlarmStateProtocol.State.DISARMED : AlarmStateProtocol.State.ARMED_STAY);
            render();
            assertEquals(action == AlarmAction.DISARM ? AlarmAction.ARM_STAY : AlarmAction.DISARM,
                    WatchAlarmStore.read(context).action);
            handle(PHONE, ArmExperimentProtocol.CHALLENGE_PATH,
                    ArmExperimentProtocol.encodeChallenge(action, selected.request, UUID.randomUUID().toString()));
            assertNull("A late challenge cannot revive the already satisfied tap", attempt());
            assertEquals("Showing the inverse action after refresh cannot send it", 1, sent.size());
            assertEquals(0, commits());
            assertNull(WatchAlarmStore.consume(context, original.revision, action));
        } } finally {
            stopQueryTransport();
            ReflectionHelpers.setStaticField(WatchAlarmStore.class, "transport", originalTransport);
        }
    }

    @Test public void aDeclineStaysReadableThroughTheRefreshAndClearsOnTheNextTap() {
        report(AlarmStateProtocol.State.DISARMED);
        mount(new Intent(Intent.ACTION_MAIN), null, true);
        tap();
        AlarmStateProtocol.Tap selected = AlarmStateProtocol.parseTap(sent.get(0).payload);
        handle(PHONE, AlarmStateProtocol.DECLINED_PATH, new AlarmStateProtocol.Declined(
                AlarmAction.ARM_STAY, selected.request, AlarmStateProtocol.DeclineReason.UNAVAILABLE).encode());
        stopQueryTransport();
        assertNull(attempt());
        assertTrue(controls().noticeView.getText().toString().contains("Not sent"));

        // The status refresh that follows re-enables the button; the decline must survive it.
        advance(2_001);
        report(AlarmStateProtocol.State.DISARMED);
        SharedPreferences.OnSharedPreferenceChangeListener changed = ReflectionHelpers.getField(activity, "changed");
        changed.onSharedPreferenceChanged(stored(), "contactReceived");
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        render();
        assertTrue(controls().alarmButton.isEnabled());
        assertTrue("The decline stays readable after the refresh", controls().noticeView.getText().toString().contains("Not sent"));
        tap();
        assertEquals("The next deliberate tap clears the notice and sends a fresh request", 2, sent.size());
        assertFalse(controls().noticeView.getText().toString().contains("Not sent"));
    }

    @Test public void currentSignInInstructionsAndHistoricalRefusalRemainVisibleTogether() {
        report(AlarmStateProtocol.State.DISARMED);
        mount(new Intent(Intent.ACTION_MAIN), null, true);
        tap();
        AlarmStateProtocol.Tap selected = AlarmStateProtocol.parseTap(sent.get(0).payload);
        handle(PHONE, AlarmStateProtocol.DECLINED_PATH, new AlarmStateProtocol.Declined(
                AlarmAction.ARM_STAY, selected.request, AlarmStateProtocol.DeclineReason.UNAVAILABLE).encode());
        stopQueryTransport();
        assertTrue(controls().noticeView.getText().toString().contains("Not sent"));

        // The correlated status answer that follows says the phone's ADT sign-in has lapsed.
        advance(2_001);
        WatchAlarmStore.Query query = WatchAlarmStore.beginQuery(now());
        assertNotNull(query);
        assertTrue(WatchAlarmStore.selectSource(context, query, PHONE, now(), BOOT));
        assertTrue(WatchAlarmStore.accept(context, PHONE, new AlarmStateProtocol.Report(query.nonce,
            AlarmStateProtocol.State.UNKNOWN, AlarmStateProtocol.Availability.NO_ACCESS, "-", 0), now(), BOOT));
        SharedPreferences.OnSharedPreferenceChangeListener changed = ReflectionHelpers.getField(activity, "changed");
        changed.onSharedPreferenceChanged(stored(), "contactReceived");
        stopQueryTransport();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        render();
        assertFalse(controls().alarmButton.isEnabled());
        String text = controls().alarmButton.getText().toString();
        assertTrue("The newer recovery instruction shows", text.contains("Sign in via ADT Watch Setup"));
        assertFalse("The historical notice stays outside the circle", text.contains("Not sent"));
        assertTrue(controls().noticeView.getText().toString().contains("Not sent"));

        // Once the control is usable again, the tap that was never sent is still worth knowing about.
        advance(2_001);
        report(AlarmStateProtocol.State.DISARMED);
        changed.onSharedPreferenceChanged(stored(), "contactReceived");
        stopQueryTransport();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        render();
        assertTrue(controls().alarmButton.isEnabled());
        assertTrue(controls().noticeView.getText().toString().contains("Not sent"));
        assertEquals(1, sent.size());
    }

    @Test public void wrongSourceRequestOrActionRefusalCannotCancelTheActiveTap() {
        report(AlarmStateProtocol.State.ARMED_STAY);
        mount(new Intent(Intent.ACTION_MAIN), null, true);
        tap();
        AlarmStateProtocol.Tap selected = AlarmStateProtocol.parseTap(sent.get(0).payload);
        ArmExperimentProtocol.Attempt active = attempt();
        byte[] matching = new AlarmStateProtocol.Declined(selected.action, selected.request,
                AlarmStateProtocol.DeclineReason.UNAVAILABLE).encode();
        handle("other-phone", AlarmStateProtocol.DECLINED_PATH, matching);
        handle(PHONE, AlarmStateProtocol.DECLINED_PATH, new AlarmStateProtocol.Declined(
                selected.action, UUID.randomUUID().toString(), AlarmStateProtocol.DeclineReason.UNAVAILABLE).encode());
        handle(PHONE, AlarmStateProtocol.DECLINED_PATH, new AlarmStateProtocol.Declined(
                AlarmAction.ARM_STAY, selected.request, AlarmStateProtocol.DeclineReason.UNAVAILABLE).encode());
        assertSame(active, attempt());
        assertEquals(ArmExperimentProtocol.Phase.AWAITING_CHALLENGE, active.phase());
        assertTrue(stored().getBoolean("busy", false));
        assertFalse(stored().getBoolean("commitMayHaveBeenSent", false));
        assertEquals(selected.request, stored().getString("actionRequest", ""));
        assertEquals(1, sent.size());
        handle(PHONE, ArmExperimentProtocol.CHALLENGE_PATH,
                ArmExperimentProtocol.encodeChallenge(selected.action, selected.request, UUID.randomUUID().toString()));
        assertEquals("A correctly matched challenge still commits once", 1, commits());
    }

    @Test public void preflightRefusalExplainsTheActualProblemAndSurvivesARefresh() {
        report(AlarmStateProtocol.State.DISARMED);
        mount(new Intent(Intent.ACTION_MAIN), null, true);
        tap();
        AlarmStateProtocol.Tap selected = AlarmStateProtocol.parseTap(sent.get(0).payload);
        byte[] refusal = new AlarmStateProtocol.Declined(selected.action, selected.request,
            AlarmStateProtocol.DeclineReason.SIGN_IN_REQUIRED).encode();
        handle("other-phone", AlarmStateProtocol.DECLINED_PATH, refusal);
        assertNotNull(attempt());
        assertEquals(View.GONE, controls().noticeView.getVisibility());
        handle(PHONE, AlarmStateProtocol.DECLINED_PATH, refusal);
        stopQueryTransport();
        assertNull(attempt());
        assertTrue(controls().noticeView.getText().toString().contains("ADT needed sign-in"));
        assertEquals(0, commits());
        advance(2_001);
        report(AlarmStateProtocol.State.DISARMED);
        render();
        assertTrue("A successful status refresh must not erase the refused tap's explanation",
            controls().noticeView.getText().toString().contains("ADT needed sign-in"));
        assertFalse("Once recovered, the history must not demand another login",
            controls().noticeView.getText().toString().contains("Sign in"));
        assertTrue(controls().alarmButton.getText().toString().contains("ADT checked"));
        assertEquals(1, sent.size());
    }

    @Test public void preciseRefusalRemainsVisibleWhenTheFollowUpStatusIsUnavailable() {
        for (AlarmStateProtocol.Availability unavailable : new AlarmStateProtocol.Availability[] {
                AlarmStateProtocol.Availability.OFFLINE, AlarmStateProtocol.Availability.NO_STATE,
                AlarmStateProtocol.Availability.STALE, AlarmStateProtocol.Availability.BUSY,
                AlarmStateProtocol.Availability.SETUP}) {
            closeActivity(); resetStore(); sent.clear();
            report(AlarmStateProtocol.State.DISARMED);
            mount(new Intent(Intent.ACTION_MAIN), null, true);
            tap();
            AlarmStateProtocol.Tap selected = AlarmStateProtocol.parseTap(sent.get(0).payload);
            handle(PHONE, AlarmStateProtocol.DECLINED_PATH, new AlarmStateProtocol.Declined(selected.action,
                selected.request, AlarmStateProtocol.DeclineReason.STATUS_CHECK_FAILED).encode());
            stopQueryTransport();
            advance(2_001);
            WatchAlarmStore.Query query = WatchAlarmStore.beginQuery(now());
            assertNotNull(query);
            assertTrue(WatchAlarmStore.selectSource(context, query, PHONE, now(), BOOT));
            assertTrue(WatchAlarmStore.accept(context, PHONE, new AlarmStateProtocol.Report(query.nonce,
                AlarmStateProtocol.State.UNKNOWN, unavailable, "-", 0), now(), BOOT));
            render();
            assertFalse(controls().alarmButton.isEnabled());
            assertEquals(View.VISIBLE, controls().noticeView.getVisibility());
            assertTrue(controls().noticeView.getText().toString().contains("ADT status check failed"));
            assertTrue("Current recovery detail remains visible independently: " + unavailable,
                controls().alarmButton.getText().toString().contains(WatchAlarmStore.read(context).detail));
            assertFalse(controls().alarmButton.getText().toString().contains("Not sent"));
            assertEquals(0, commits());
            assertEquals(1, sent.size());
        }
    }

    @Test public void postChallengeReasonShowsOnlyForItsMatchedRequestAndDoesNotResend() {
        report(AlarmStateProtocol.State.DISARMED);
        mount(new Intent(Intent.ACTION_MAIN), null, true);
        tap();
        AlarmStateProtocol.Tap selected = AlarmStateProtocol.parseTap(sent.get(0).payload);
        String challenge = UUID.randomUUID().toString();
        handle(PHONE, ArmExperimentProtocol.CHALLENGE_PATH,
            ArmExperimentProtocol.encodeChallenge(selected.action, selected.request, challenge));
        ArmExperimentProtocol.Attempt active = attempt();
        byte[] refusal = ArmExperimentProtocol.encodeResult(selected.action, selected.request, challenge,
            ArmExperimentProtocol.Outcome.REJECTED, AlarmStateProtocol.DeclineReason.WIDGET_CHANGED);
        handle("other-phone", ArmExperimentProtocol.RESULT_PATH, refusal);
        handle(PHONE, ArmExperimentProtocol.RESULT_PATH, ArmExperimentProtocol.encodeResult(selected.action,
            UUID.randomUUID().toString(), challenge, ArmExperimentProtocol.Outcome.REJECTED,
            AlarmStateProtocol.DeclineReason.PHONE_UNLOCKED));
        handle(PHONE, ArmExperimentProtocol.RESULT_PATH, ArmExperimentProtocol.encodeResult(selected.action,
            selected.request, UUID.randomUUID().toString(), ArmExperimentProtocol.Outcome.REJECTED,
            AlarmStateProtocol.DeclineReason.SIGN_IN_REQUIRED));
        assertSame(active, attempt());
        assertNull(active.rejectionReason());
        assertFalse(controls().noticeView.getText().toString().contains("Not sent"));
        handle(PHONE, ArmExperimentProtocol.RESULT_PATH, refusal);
        stopQueryTransport();
        assertNull(attempt());
        assertFalse(stored().getBoolean("busy", false));
        assertFalse(stored().getBoolean("commitMayHaveBeenSent", false));
        assertTrue(controls().noticeView.getText().toString().contains("ADT control refreshed"));
        assertEquals(1, commits());
        assertEquals(2, sent.size());
        handle(PHONE, ArmExperimentProtocol.RESULT_PATH, ArmExperimentProtocol.encodeResult(selected.action,
            selected.request, challenge, ArmExperimentProtocol.Outcome.REJECTED,
            AlarmStateProtocol.DeclineReason.PHONE_UNLOCKED));
        assertTrue("A late competing result cannot replace the accepted explanation",
            controls().noticeView.getText().toString().contains("ADT control refreshed"));
        assertEquals(2, sent.size());
    }

    @Test public void expiredReasonedResultCannotReplaceUncertainStatusOrClearPossibleSend() {
        report(AlarmStateProtocol.State.DISARMED);
        mount(new Intent(Intent.ACTION_MAIN), null, true);
        tap();
        AlarmStateProtocol.Tap selected = AlarmStateProtocol.parseTap(sent.get(0).payload);
        String challenge = UUID.randomUUID().toString();
        handle(PHONE, ArmExperimentProtocol.CHALLENGE_PATH,
            ArmExperimentProtocol.encodeChallenge(selected.action, selected.request, challenge));
        advance(ArmExperimentProtocol.RESULT_TIMEOUT_MS);
        handle(PHONE, ArmExperimentProtocol.RESULT_PATH, ArmExperimentProtocol.encodeResult(selected.action,
            selected.request, challenge, ArmExperimentProtocol.Outcome.REJECTED,
            AlarmStateProtocol.DeclineReason.PHONE_UNLOCKED));
        render();
        assertTrue(stored().getBoolean("commitMayHaveBeenSent", false));
        assertFalse(controls().noticeView.getText().toString().contains("Lock your phone"));
        assertEquals(1, commits());
    }

    @Test public void earlyRefusalAfterCommitCannotEraseAnUncertainResult() {
        report(AlarmStateProtocol.State.ARMED_STAY);
        mount(new Intent(Intent.ACTION_MAIN), null, true);
        tap();
        AlarmStateProtocol.Tap selected = AlarmStateProtocol.parseTap(sent.get(0).payload);
        String challenge = UUID.randomUUID().toString();
        handle(PHONE, ArmExperimentProtocol.CHALLENGE_PATH,
                ArmExperimentProtocol.encodeChallenge(selected.action, selected.request, challenge));
        ArmExperimentProtocol.Attempt committed = attempt();
        byte[] refusal = new AlarmStateProtocol.Declined(selected.action, selected.request,
                AlarmStateProtocol.DeclineReason.STATE_CHANGED).encode();
        handle(PHONE, AlarmStateProtocol.DECLINED_PATH, refusal);
        assertSame(committed, attempt());
        assertEquals(ArmExperimentProtocol.Phase.AWAITING_RESULT, committed.phase());
        assertTrue(stored().getBoolean("busy", false));
        assertTrue(stored().getBoolean("commitMayHaveBeenSent", false));
        assertFalse(controls().alarmButton.isEnabled());

        handle(PHONE, ArmExperimentProtocol.RESULT_PATH,
                ArmExperimentProtocol.encodeResult(selected.action, selected.request, challenge,
                        ArmExperimentProtocol.Outcome.REQUESTED));
        stopQueryTransport();
        assertNull(attempt());
        handle(PHONE, AlarmStateProtocol.DECLINED_PATH, refusal);
        assertTrue(stored().getBoolean("busy", false));
        assertTrue(stored().getBoolean("awaiting", false));
        assertTrue(stored().getBoolean("commitMayHaveBeenSent", false));
        assertFalse(WatchAlarmStore.read(context).enabled);
        assertEquals(2, sent.size());
        assertEquals(1, commits());
    }

    @Test public void mismatchedDurableRequestPreventsCommitAndLeavesNoUncertainSend() {
        report(AlarmStateProtocol.State.ARMED_STAY);
        mount(new Intent(Intent.ACTION_MAIN), null, true);
        tap();
        AlarmStateProtocol.Tap selected = AlarmStateProtocol.parseTap(sent.get(0).payload);
        stored().edit().putString("actionRequest", UUID.randomUUID().toString()).commit();
        handle(PHONE, ArmExperimentProtocol.CHALLENGE_PATH,
                ArmExperimentProtocol.encodeChallenge(selected.action, selected.request, UUID.randomUUID().toString()));
        stopQueryTransport();
        assertNull(attempt());
        assertEquals(1, sent.size());
        assertEquals(0, commits());
        assertFalse(stored().getBoolean("busy", false));
        assertFalse(stored().getBoolean("awaiting", false));
        assertFalse(stored().getBoolean("commitMayHaveBeenSent", false));
        assertTrue(controls().alarmButton.getText().toString().contains("No action sent"));
    }

    private void mount(Intent intent, Bundle saved, boolean ready) {
        controller = Robolectric.buildActivity(WatchActivity.class, intent).create(saved).start().visible();
        activity = controller.get();
        ReflectionHelpers.setField(activity, "sender", (WatchActivity.Sender) (node, path, payload, failure) -> {
            if (AlarmStateProtocol.TOGGLE_PATH.equals(path)) {
                AlarmStateProtocol.Tap tap = AlarmStateProtocol.parseTap(payload);
                assertEquals("Request identity is durable before PREPARE transport", tap.request, stored().getString("actionRequest", ""));
                assertTrue(stored().getBoolean("busy", false));
                assertFalse("PREPARE must not be recorded as a possible COMMIT", stored().getBoolean("commitMayHaveBeenSent", false));
            } else if (ArmExperimentProtocol.COMMIT_PATH.equals(path)) {
                ArmExperimentProtocol.Message commit = ArmExperimentProtocol.parse(payload, ArmExperimentProtocol.Kind.COMMIT);
                assertEquals(commit.requestId, stored().getString("actionRequest", ""));
                assertTrue("Possible-send marker is durable before COMMIT transport", stored().getBoolean("commitMayHaveBeenSent", false));
                assertTrue(stored().getBoolean("busy", false));
            }
            sent.add(new Sent(node, path, payload.clone()));
        });
        if (ready) ready();
    }

    private void ready() {
        ReflectionHelpers.setField(activity, "resumed", true);
        ReflectionHelpers.setField(activity, "listenerReady", true);
        controller.windowFocusChanged(true);
        assertTrue("The positive fixture must have a focused window", activity.hasWindowFocus());
        render();
    }

    private void render() { ReflectionHelpers.callInstanceMethod(activity, "render"); }
    private AlarmToggleView controls() { return ReflectionHelpers.getField(activity, "controls"); }
    private ArmExperimentProtocol.Attempt attempt() { return ReflectionHelpers.getField(activity, "attempt"); }
    private SharedPreferences stored() { return context.getSharedPreferences(WatchAlarmStore.PREFERENCES, Context.MODE_PRIVATE); }

    private void handle(String source, String path, byte[] payload) {
        MessageEvent event = new MessageEvent() {
            @Override public int getRequestId() { return 1; }
            @Override public String getPath() { return path; }
            @Override public byte[] getData() { return payload; }
            @Override public String getSourceNodeId() { return source; }
        };
        int generation = ReflectionHelpers.getField(activity, "generation");
        ReflectionHelpers.callInstanceMethod(activity, "handle", ClassParameter.from(MessageEvent.class, event),
            ClassParameter.from(int.class, generation));
    }

    private WatchAlarmStore.ViewState report(AlarmStateProtocol.State state) {
        return report(state, "-");
    }

    private WatchAlarmStore.ViewState report(AlarmStateProtocol.State state, String completedRequest) {
        WatchAlarmStore.Query query = WatchAlarmStore.beginQuery(now());
        assertNotNull("Inert report needs a fresh production query", query);
        assertTrue(WatchAlarmStore.selectSource(context, query, PHONE, now(), BOOT));
        assertTrue(WatchAlarmStore.accept(context, PHONE, new AlarmStateProtocol.Report(query.nonce, state,
            AlarmStateProtocol.Availability.READY, UUID.randomUUID().toString(), 0, completedRequest,
            AlarmStateProtocol.Evidence.ADT_QUERY, UUID.randomUUID().toString()), now(), BOOT));
        return WatchAlarmStore.read(context);
    }

    private Intent tile(AlarmAction action, String token) {
        return new Intent(context, WatchActivity.class).putExtra(AlarmTileService.EXTRA_TILE_ACTION, action.name())
            .putExtra(AlarmTileService.EXTRA_TILE_REVISION, token);
    }

    private void tap() {
        AlarmToggleView controls = controls();
        stopQueryTransport();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        controls.measure(View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY));
        controls.layout(0, 0, 240, 240);
        Rect rect = new Rect(0, 0, controls.alarmButton.getWidth(), controls.alarmButton.getHeight());
        controls.offsetDescendantRectToMyCoords(controls.alarmButton, rect);
        rect.offset(-controls.getScrollX(), -controls.getScrollY());
        long down = SystemClock.uptimeMillis();
        MotionEvent press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, rect.centerX(), rect.centerY(), 0);
        MotionEvent release = MotionEvent.obtain(down, down + 10, MotionEvent.ACTION_UP, rect.centerX(), rect.centerY(), 0);
        controls.dispatchTouchEvent(press); controls.dispatchTouchEvent(release);
        press.recycle(); release.recycle();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private void closeActivity() {
        if (controller == null) return;
        controller.windowFocusChanged(false);
        if (realResume) { controller.pause(); realResume = false; }
        ReflectionHelpers.setField(activity, "resumed", false);
        ReflectionHelpers.setField(activity, "listenerReady", false);
        ((Handler) ReflectionHelpers.getField(activity, "handler")).removeCallbacksAndMessages(null);
        controller.stop().destroy();
        controller = null; activity = null;
        stopQueryTransport();
    }

    private void resetStore() {
        stopQueryTransport();
        context.getSharedPreferences(WatchAlarmStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit();
        ReflectionHelpers.setStaticField(WatchAlarmStore.class, "pending", null);
        ReflectionHelpers.setStaticField(WatchAlarmStore.class, "lastRefresh", -1L);
        ReflectionHelpers.setStaticField(WatchAlarmStore.class, "activeSelection", null);
        ReflectionHelpers.setStaticField(WatchAlarmStore.class, "recovery", null);
        ReflectionHelpers.setStaticField(WatchAlarmStore.class, "refreshQueued", false);
    }

    private void stopQueryTransport() {
        ((Handler) ReflectionHelpers.getStaticField(WatchAlarmStore.class, "MAIN")).removeCallbacksAndMessages(null);
    }

    private int commits() {
        int count = 0;
        for (Sent value : sent) if (ArmExperimentProtocol.COMMIT_PATH.equals(value.path)) count++;
        return count;
    }

    private long now() { return SystemClock.elapsedRealtime(); }
    private void advance(long millis) { ShadowSystemClock.advanceBy(Duration.ofMillis(millis)); }
    private static final class Sent {
        final String node, path;
        final byte[] payload;
        Sent(String node, String path, byte[] payload) { this.node = node; this.path = path; this.payload = payload; }
    }
}
