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
 * Query transport is never drained: tests supply its nonce-matched answers directly instead.
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
        report(AlarmStateProtocol.State.DISARMED);
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
        assertTrue(controls().alarmButton.getText().toString().contains("Allow ADT status access"));
        assertFalse(controls().alarmButton.isEnabled());
        assertEquals(1, sent.size());
        assertEquals(0, commits());
    }

    private void mount(Intent intent, Bundle saved, boolean ready) {
        controller = Robolectric.buildActivity(WatchActivity.class, intent).create(saved).start().visible();
        activity = controller.get();
        ReflectionHelpers.setField(activity, "sender", (WatchActivity.Sender) (node, path, payload, failure) ->
            sent.add(new Sent(node, path, payload.clone())));
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
        WatchAlarmStore.Query query = WatchAlarmStore.beginQuery(now());
        assertNotNull("Inert report needs a fresh production query", query);
        assertTrue(WatchAlarmStore.selectSource(context, query, PHONE, now(), BOOT));
        assertTrue(WatchAlarmStore.accept(context, PHONE, new AlarmStateProtocol.Report(query.nonce, state,
            AlarmStateProtocol.Availability.READY, UUID.randomUUID().toString(), 0), now(), BOOT));
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
