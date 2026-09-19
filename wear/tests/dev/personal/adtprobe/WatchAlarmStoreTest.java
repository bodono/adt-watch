package dev.personal.adtprobe;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import java.time.Duration;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Exercises production cache/query admission with inert reports; no GMS transport or alarm calls. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class WatchAlarmStoreTest {
    private static final int BOOT = 7;
    private static final String PHONE = "inert-phone";
    private static final String R1 = "11111111-1111-1111-1111-111111111111";
    private static final String R2 = "22222222-2222-2222-2222-222222222222";
    private static final String R3 = "33333333-3333-3333-3333-333333333333";
    private Context context;

    @Before public void setUp() {
        stopRecovery();
        context = RuntimeEnvironment.getApplication();
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, BOOT);
        context.getSharedPreferences(WatchAlarmStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit();
        ReflectionHelpers.setStaticField(WatchAlarmStore.class, "pending", null);
        ReflectionHelpers.setStaticField(WatchAlarmStore.class, "lastRefresh", -1L);
        ReflectionHelpers.setStaticField(WatchAlarmStore.class, "refreshQueued", false);
        ReflectionHelpers.setStaticField(WatchAlarmStore.class, "activeSelection", null);
    }

    @After public void stopRecovery() {
        ((Handler) ReflectionHelpers.getStaticField(WatchAlarmStore.class, "MAIN")).removeCallbacksAndMessages(null);
        ReflectionHelpers.setStaticField(WatchAlarmStore.class, "recovery", null);
    }

    @Test public void sourceAndLiveQueryNonceAreRequired() {
        assertFalse(WatchAlarmStore.read(context).enabled);
        assertFalse(push(R1, AlarmStateProtocol.State.DISARMED, 10));
        WatchAlarmStore.Query query = WatchAlarmStore.beginQuery(now());
        assertNotNull(query);
        assertTrue(WatchAlarmStore.selectSource(context, query, PHONE, now(), BOOT));
        assertFalse(WatchAlarmStore.accept(context, "other-phone", report(query.nonce, R1,
                AlarmStateProtocol.State.DISARMED, 10), now(), BOOT));
        assertFalse(WatchAlarmStore.accept(context, PHONE, report(R3, R1,
                AlarmStateProtocol.State.DISARMED, 10), now(), BOOT));
        assertFalse(WatchAlarmStore.read(context).enabled);
        assertTrue(WatchAlarmStore.accept(context, PHONE, report(query.nonce, R1,
                AlarmStateProtocol.State.DISARMED, 10), now(), BOOT));
        assertEquals(AlarmAction.ARM_STAY, WatchAlarmStore.read(context).action);
        assertFalse(WatchAlarmStore.accept(context, PHONE, report(query.nonce, R1,
                AlarmStateProtocol.State.DISARMED, 11), now(), BOOT));
    }

    @Test public void discoveryAndAnswerShareTheTenSecondDeadlineAndQueriesCoalesce() {
        WatchAlarmStore.Query query = WatchAlarmStore.beginQuery(now());
        assertNull(WatchAlarmStore.beginQuery(now()));
        assertTrue(WatchAlarmStore.selectSource(context, query, PHONE, now(), BOOT));
        advance(10_000);
        assertFalse(WatchAlarmStore.accept(context, PHONE, report(query.nonce, R1,
                AlarmStateProtocol.State.DISARMED, 0), now(), BOOT));
        assertFalse(WatchAlarmStore.selectSource(context, query, PHONE, now(), BOOT));
        assertNotNull(WatchAlarmStore.beginQuery(now()));
    }

    @Test public void duplicatePushCannotKeepTheLinkFreshAndNewContactRotatesExpiredToken() {
        WatchAlarmStore.ViewState original = seed(10);
        advance(30_000);
        assertTrue(push(R1, AlarmStateProtocol.State.DISARMED, 10));
        assertFalse(WatchAlarmStore.read(context).enabled);
        advance(30_001);
        assertFalse(WatchAlarmStore.read(context).enabled);
        WatchAlarmStore.Query query = WatchAlarmStore.beginQuery(now());
        assertTrue(WatchAlarmStore.selectSource(context, query, PHONE, now(), BOOT));
        assertTrue(WatchAlarmStore.accept(context, PHONE, report(query.nonce, R1,
                AlarmStateProtocol.State.DISARMED, 60_011), now(), BOOT));
        WatchAlarmStore.ViewState fresh = WatchAlarmStore.read(context);
        assertTrue(fresh.enabled);
        assertNotEquals(original.revision, fresh.revision);
        assertNull(WatchAlarmStore.consume(context, original.revision, AlarmAction.ARM_STAY));
    }

    @Test public void stateAgeNeverRewindsAndExpiresEvenWhileLinkIsFresh() {
        seed(AlarmStateProtocol.MAX_STATE_AGE_MS - 10);
        advance(11);
        assertFalse(WatchAlarmStore.read(context).enabled);
        assertFalse(update(R1, AlarmStateProtocol.State.DISARMED, 0));
        assertFalse(WatchAlarmStore.read(context).enabled);
    }

    @Test public void rebootOrMonotonicRollbackInvalidatesTheCache() {
        seed(0);
        assertFalse(WatchAlarmStore.readAt(context, now() - 1, BOOT).enabled);
        assertFalse(WatchAlarmStore.readAt(context, now(), BOOT + 1).enabled);
        assertFalse(WatchAlarmStore.readAt(context, now(), -1).enabled);
    }

    @Test public void tokenIsActionBoundConsumedBeforeReturnAndNoSendAllowsANewTap() {
        WatchAlarmStore.ViewState view = seed(10);
        assertNull(WatchAlarmStore.consume(context, view.revision, AlarmAction.DISARM));
        WatchAlarmStore.Selection selected = WatchAlarmStore.consume(context, view.revision, AlarmAction.ARM_STAY);
        assertNotNull(selected);
        assertEquals(PHONE, selected.phone);
        assertEquals(R1, selected.stateRevision);
        assertFalse(WatchAlarmStore.read(context).enabled);
        assertTrue(WatchAlarmStore.stillCurrent(context, selected));
        assertNull(WatchAlarmStore.consume(context, view.revision, AlarmAction.ARM_STAY));
        WatchAlarmStore.finish(context, false);
        WatchAlarmStore.ViewState retry = WatchAlarmStore.read(context);
        assertTrue(retry.enabled);
        assertNotEquals(view.revision, retry.revision);
        assertNull(WatchAlarmStore.consume(context, view.revision, AlarmAction.ARM_STAY));
    }

    @Test public void possibleSendStaysUnknownForSameRevisionBusyOrSameStateRevisionChange() {
        WatchAlarmStore.ViewState view = seed(100);
        assertNotNull(WatchAlarmStore.consume(context, view.revision, AlarmAction.ARM_STAY));
        WatchAlarmStore.finish(context, true);
        advance(100);
        assertTrue(update(R1, AlarmStateProtocol.State.DISARMED, 200));
        assertFalse(WatchAlarmStore.read(context).enabled);
        assertTrue(WatchAlarmStore.accept(context, PHONE, new AlarmStateProtocol.Report("-",
                AlarmStateProtocol.State.UNKNOWN, AlarmStateProtocol.Availability.BUSY, "-", 0), now(), BOOT));
        assertFalse(WatchAlarmStore.read(context).enabled);
        assertTrue(update(R2, AlarmStateProtocol.State.DISARMED, 0));
        assertFalse(WatchAlarmStore.read(context).enabled);
        advance(10);
        assertTrue(update(R3, AlarmStateProtocol.State.ARMED_STAY, 0));
        assertEquals(AlarmAction.DISARM, WatchAlarmStore.read(context).action);
    }

    @Test public void changedStateBeforeResultUnblocksAndLateFinishCannotReblockIt() {
        WatchAlarmStore.ViewState view = seed(100);
        WatchAlarmStore.Selection selected = WatchAlarmStore.consume(context, view.revision, AlarmAction.ARM_STAY);
        advance(10);
        assertTrue(update(R2, AlarmStateProtocol.State.ARMED_STAY, 0));
        assertFalse(WatchAlarmStore.stillCurrent(context, selected));
        assertFalse(WatchAlarmStore.read(context).enabled);
        WatchAlarmStore.finish(context, true);
        assertTrue(WatchAlarmStore.read(context).enabled);
        WatchAlarmStore.finish(context, true);
        assertTrue(WatchAlarmStore.read(context).enabled);
        assertNull(WatchAlarmStore.consume(context, view.revision, AlarmAction.ARM_STAY));
    }

    @Test public void previouslySeenAndOlderEventsCannotReverseTheDisplayedAction() {
        seed(100);
        advance(100);
        assertTrue(update(R2, AlarmStateProtocol.State.ARMED_STAY, 0));
        assertFalse(update(R1, AlarmStateProtocol.State.DISARMED, 200));
        assertFalse(update(R3, AlarmStateProtocol.State.DISARMED, 50_000));
        assertEquals(AlarmAction.DISARM, WatchAlarmStore.read(context).action);
        assertFalse(update(R2, AlarmStateProtocol.State.DISARMED, 0));
    }

    @Test public void sourceChangeInvalidatesSelectionsAndCannotResolveOldPossibleSend() {
        WatchAlarmStore.ViewState view = seed(100);
        WatchAlarmStore.Selection selected = WatchAlarmStore.consume(context, view.revision, AlarmAction.ARM_STAY);
        WatchAlarmStore.finish(context, true);
        advance(2_000);
        WatchAlarmStore.Query query = WatchAlarmStore.beginQuery(now());
        assertTrue(WatchAlarmStore.selectSource(context, query, "replacement-phone", now(), BOOT));
        assertFalse(WatchAlarmStore.stillCurrent(context, selected));
        assertFalse(push(R2, AlarmStateProtocol.State.ARMED_STAY, 0));
        assertTrue(WatchAlarmStore.accept(context, "replacement-phone", report(query.nonce, R2,
                AlarmStateProtocol.State.ARMED_STAY, 0), now(), BOOT));
        assertFalse(WatchAlarmStore.read(context).enabled);
    }

    @Test public void delayedUnseenPushOnlyInvalidatesAndCannotClearPossibleSend() {
        WatchAlarmStore.ViewState view = seed(100);
        assertNotNull(WatchAlarmStore.consume(context, view.revision, AlarmAction.ARM_STAY));
        WatchAlarmStore.finish(context, true);
        advance(70_000);
        assertTrue(push(R2, AlarmStateProtocol.State.ARMED_STAY, 0));
        assertFalse(WatchAlarmStore.read(context).enabled);
        assertEquals(R1, context.getSharedPreferences(WatchAlarmStore.PREFERENCES, Context.MODE_PRIVATE)
                .getString("revision", ""));
        // Only a new, bounded query to the established phone confirms the current changed state.
        assertTrue(update(R3, AlarmStateProtocol.State.ARMED_STAY, 100));
        assertEquals(AlarmAction.DISARM, WatchAlarmStore.read(context).action);
    }

    @Test public void recoveredProcessTreatsUnfinishedTapAsPossiblySentAndCanRecoverFromChangedReport() {
        WatchAlarmStore.ViewState view = seed(100);
        WatchAlarmStore.Selection selected = WatchAlarmStore.consume(context, view.revision, AlarmAction.ARM_STAY);
        assertNotNull(selected);
        assertTrue(WatchAlarmStore.attachRequest(context, selected, R3));
        assertTrue(WatchAlarmStore.markCommitting(context, selected, R3));
        ReflectionHelpers.setStaticField(WatchAlarmStore.class, "activeSelection", null);
        assertFalse(WatchAlarmStore.read(context).enabled);
        assertTrue(context.getSharedPreferences(WatchAlarmStore.PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean("awaiting", false));
        assertTrue(update(R1, AlarmStateProtocol.State.DISARMED, 2_100));
        assertFalse(WatchAlarmStore.read(context).enabled);
        assertTrue(update(R2, AlarmStateProtocol.State.ARMED_STAY, 0));
        assertEquals(AlarmAction.DISARM, WatchAlarmStore.read(context).action);
    }

    @Test public void preCommitProcessDeathDoesNotInventAnUncertainAlarmCommand() {
        WatchAlarmStore.ViewState view = seed(100);
        WatchAlarmStore.Selection selected = WatchAlarmStore.consume(context, view.revision, AlarmAction.ARM_STAY);
        assertTrue(WatchAlarmStore.attachRequest(context, selected, R3));
        assertFalse(WatchAlarmStore.markCommitting(context, selected, R2));
        ReflectionHelpers.setStaticField(WatchAlarmStore.class, "activeSelection", null);
        assertTrue(WatchAlarmStore.read(context).enabled);
        assertFalse(context.getSharedPreferences(WatchAlarmStore.PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean("awaiting", false));
        assertNull(WatchAlarmStore.consume(context, view.revision, AlarmAction.ARM_STAY));
    }

    @Test public void sameStateCanResolveOnlyTheMatchingCompletedRequest() {
        WatchAlarmStore.ViewState view = seed(100);
        WatchAlarmStore.Selection selected = WatchAlarmStore.consume(context, view.revision, AlarmAction.ARM_STAY);
        assertTrue(WatchAlarmStore.attachRequest(context, selected, R3));
        assertTrue(WatchAlarmStore.markCommitting(context, selected, R3));
        WatchAlarmStore.finish(context, true);
        assertTrue(update(R2, AlarmStateProtocol.State.DISARMED, 0, R1));
        assertFalse(WatchAlarmStore.read(context).enabled);
        assertTrue(update(R3, AlarmStateProtocol.State.DISARMED, 0, R3));
        assertTrue(WatchAlarmStore.read(context).enabled);
        assertEquals(AlarmAction.ARM_STAY, WatchAlarmStore.read(context).action);
    }

    @Test public void unsolicitedCompletionDoesNotClearUncertainty() {
        WatchAlarmStore.ViewState view = seed(100);
        WatchAlarmStore.Selection selected = WatchAlarmStore.consume(context, view.revision, AlarmAction.ARM_STAY);
        assertTrue(WatchAlarmStore.attachRequest(context, selected, R3));
        WatchAlarmStore.finish(context, true);
        assertTrue(WatchAlarmStore.accept(context, PHONE, new AlarmStateProtocol.Report("-",
                AlarmStateProtocol.State.DISARMED, AlarmStateProtocol.Availability.READY, R2, 0, R3), now(), BOOT));
        assertFalse(WatchAlarmStore.read(context).enabled);
        assertTrue(context.getSharedPreferences(WatchAlarmStore.PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean("awaiting", false));
    }

    @Test public void missingResultBecomesUnconfirmedWithoutInventingCompletionAndCanRecoverLater() {
        WatchAlarmStore.ViewState view = seed(100);
        assertNotNull(WatchAlarmStore.consume(context, view.revision, AlarmAction.ARM_STAY));
        WatchAlarmStore.finish(context, true);
        assertEquals("Checking alarm", WatchAlarmStore.read(context).label);
        advance(30_000);
        assertEquals("Result unconfirmed", WatchAlarmStore.read(context).label);
        advance(3_600_000);
        assertEquals("Result unconfirmed", WatchAlarmStore.read(context).label);
        assertTrue(context.getSharedPreferences(WatchAlarmStore.PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean("awaiting", false));
        assertTrue(update(R2, AlarmStateProtocol.State.ARMED_STAY, 0));
        assertEquals(AlarmAction.DISARM, WatchAlarmStore.read(context).action);
    }

    @Test public void oldInstallPendingWithoutStartTimeIsImmediatelyUnconfirmed() {
        seed(100);
        context.getSharedPreferences(WatchAlarmStore.PREFERENCES, Context.MODE_PRIVATE).edit()
                .putBoolean("busy", true).putBoolean("awaiting", false).remove("actionStarted")
                .remove("commitMayHaveBeenSent").commit();
        assertEquals("Result unconfirmed", WatchAlarmStore.read(context).label);
        assertFalse(WatchAlarmStore.read(context).enabled);
    }

    @Test public void phoneCheckedStateIsLabelledSeparatelyAndExpiresWithinFiveMinutes() {
        WatchAlarmStore.Query query = WatchAlarmStore.beginQuery(now());
        assertTrue(WatchAlarmStore.selectSource(context, query, PHONE, now(), BOOT));
        assertTrue(WatchAlarmStore.accept(context, PHONE, new AlarmStateProtocol.Report(query.nonce,
                AlarmStateProtocol.State.DISARMED, AlarmStateProtocol.Availability.READY, R1,
                AlarmStateProtocol.PHONE_CHECK_FRESH_MS - 10, "-", AlarmStateProtocol.Evidence.PHONE_CHECK), now(), BOOT));
        assertTrue(WatchAlarmStore.read(context).detail.startsWith("Checked "));
        assertEquals(10, WatchAlarmStore.remainingActionValidityMillis(context));
        advance(11);
        assertFalse(WatchAlarmStore.read(context).enabled);
    }

    @Test public void explicitPhoneCheckCanSettleLegacyPendingWithoutInventingARequestId() {
        seed(100);
        context.getSharedPreferences(WatchAlarmStore.PREFERENCES, Context.MODE_PRIVATE).edit()
                .putBoolean("busy", true).putBoolean("awaiting", true)
                .putString("actionSource", PHONE).putString("actionRevision", R1)
                .putString("actionState", "DISARMED").remove("actionStarted").remove("actionRequest").commit();
        advance(2_000);
        WatchAlarmStore.Query query = WatchAlarmStore.beginQuery(now());
        assertTrue(WatchAlarmStore.selectSource(context, query, PHONE, now(), BOOT));
        assertTrue(WatchAlarmStore.accept(context, PHONE, new AlarmStateProtocol.Report(query.nonce,
                AlarmStateProtocol.State.DISARMED, AlarmStateProtocol.Availability.READY, R2, 0, "-",
                AlarmStateProtocol.Evidence.PHONE_CHECK), now(), BOOT));
        assertEquals(AlarmAction.ARM_STAY, WatchAlarmStore.read(context).action);
        assertEquals("", context.getSharedPreferences(WatchAlarmStore.PREFERENCES, Context.MODE_PRIVATE)
                .getString("actionRequest", ""));
    }

    @Test public void laterPhoneCheckSettlesAnUncertainCommitThatNeverReachedThePhone() {
        committedAttempt();
        WatchAlarmStore.finish(context, true);
        stopRecovery();
        advance(2_000);
        WatchAlarmStore.Query query = selectedQuery();
        advance(300);
        // No completed request is reported: the phone never persisted this COMMIT.
        assertTrue(WatchAlarmStore.accept(context, PHONE, checkedReport(query.nonce, 100), now(), BOOT));
        assertTrue(WatchAlarmStore.read(context).enabled);
        assertEquals(AlarmAction.ARM_STAY, WatchAlarmStore.read(context).action);
        assertEquals(R3, stored().getString("checkedActionRequest", ""));
        assertEquals("Explicit verification must not invent a phone completion UUID", "-",
            stored().getString("completedRequest", ""));
        assertFalse(stored().getBoolean("busy", false));

        WatchAlarmStore.ViewState checked = WatchAlarmStore.read(context);
        assertNotNull(WatchAlarmStore.consume(context, checked.revision, checked.action));
        assertFalse("A new tap cannot reuse the previous verification proof", stored().contains("checkedActionRequest"));
        assertFalse(stored().contains("commitStarted"));
        assertFalse(stored().contains("commitBoot"));
    }

    @Test public void verificationBeforeTheResultCallbackCannotBeReblockedByFinish() {
        committedAttempt();
        advance(2_000);
        WatchAlarmStore.Query query = selectedQuery();
        advance(300);
        assertTrue(WatchAlarmStore.accept(context, PHONE, checkedReport(query.nonce, 100), now(), BOOT));
        assertTrue("The live attempt has not yet processed its result", stored().getBoolean("busy", false));
        assertEquals(R3, stored().getString("checkedActionRequest", ""));
        WatchAlarmStore.finish(context, true);
        stopRecovery();
        assertTrue(WatchAlarmStore.read(context).enabled);
        assertFalse(stored().getBoolean("awaiting", false));
        assertFalse(stored().getBoolean("commitMayHaveBeenSent", false));
    }

    @Test public void phoneCheckAtTheCommitBoundaryIsNotProofOfItsOutcome() {
        assertOldPhoneCheckCannotSettle(2_000);
    }

    @Test public void phoneCheckBeforeCommitCannotSettleEvenWhenItsReplyArrivesLater() {
        assertOldPhoneCheckCannotSettle(2_001);
    }

    private void assertOldPhoneCheckCannotSettle(long age) {
        committedAttempt();
        WatchAlarmStore.finish(context, true);
        stopRecovery();
        advance(2_000);
        WatchAlarmStore.Query query = selectedQuery();
        advance(500);
        // At age=2000, age alone looks newer than the 2500ms-old commit. Including
        // the entire 500ms round trip makes equality too ambiguous to clear it.
        assertTrue(WatchAlarmStore.accept(context, PHONE, checkedReport(query.nonce, age), now(), BOOT));
        assertFalse(WatchAlarmStore.read(context).enabled);
        assertTrue(stored().getBoolean("awaiting", false));
        assertFalse(stored().contains("checkedActionRequest"));
    }

    @Test public void wrongSourceWrongQueryAndPushedPhoneChecksCannotSettleACommit() {
        committedAttempt();
        WatchAlarmStore.finish(context, true);
        stopRecovery();
        advance(2_000);
        WatchAlarmStore.Query query = selectedQuery();
        advance(300);
        assertFalse(WatchAlarmStore.accept(context, "other-phone", checkedReport(query.nonce, 100), now(), BOOT));
        assertFalse(WatchAlarmStore.accept(context, PHONE, checkedReport(R1, 100), now(), BOOT));
        assertTrue(WatchAlarmStore.accept(context, PHONE, checkedReport("-", 100), now(), BOOT));
        assertFalse("A push invalidates the pending query rather than confirming it",
            WatchAlarmStore.accept(context, PHONE, checkedReport(query.nonce, 100), now(), BOOT));
        assertFalse(stored().contains("checkedActionRequest"));
        assertTrue(stored().getBoolean("awaiting", false));
        assertFalse(WatchAlarmStore.read(context).enabled);
    }

    @Test public void phoneCheckOnAnExpiredQueryCannotSettleACommit() {
        committedAttempt();
        WatchAlarmStore.finish(context, true);
        stopRecovery();
        advance(2_000);
        WatchAlarmStore.Query query = selectedQuery();
        advance(10_000);
        assertFalse(WatchAlarmStore.accept(context, PHONE, checkedReport(query.nonce, 0), now(), BOOT));
        assertFalse(stored().contains("checkedActionRequest"));
        assertTrue(stored().getBoolean("awaiting", false));
    }

    private void committedAttempt() {
        WatchAlarmStore.ViewState initial = seed(100);
        WatchAlarmStore.Selection selected = WatchAlarmStore.consume(context, initial.revision, initial.action);
        assertNotNull(selected);
        assertTrue(WatchAlarmStore.attachRequest(context, selected, R3));
        long committed = now();
        assertTrue(WatchAlarmStore.markCommitting(context, selected, R3));
        assertEquals(committed, stored().getLong("commitStarted", -1));
        assertEquals(BOOT, stored().getInt("commitBoot", -1));
    }

    private WatchAlarmStore.Query selectedQuery() {
        WatchAlarmStore.Query query = WatchAlarmStore.beginQuery(now());
        assertNotNull(query);
        assertTrue(WatchAlarmStore.selectSource(context, query, PHONE, now(), BOOT));
        return query;
    }

    private static AlarmStateProtocol.Report checkedReport(String nonce, long age) {
        return new AlarmStateProtocol.Report(nonce, AlarmStateProtocol.State.DISARMED,
            AlarmStateProtocol.Availability.READY, R2, age, "-", AlarmStateProtocol.Evidence.PHONE_CHECK);
    }

    private SharedPreferences stored() {
        return context.getSharedPreferences(WatchAlarmStore.PREFERENCES, Context.MODE_PRIVATE);
    }

    private WatchAlarmStore.ViewState seed(long age) {
        WatchAlarmStore.Query query = WatchAlarmStore.beginQuery(now());
        assertNotNull(query);
        assertTrue(WatchAlarmStore.selectSource(context, query, PHONE, now(), BOOT));
        assertTrue(WatchAlarmStore.accept(context, PHONE, report(query.nonce, R1,
                AlarmStateProtocol.State.DISARMED, age), now(), BOOT));
        return WatchAlarmStore.read(context);
    }
    private boolean update(String revision, AlarmStateProtocol.State state, long age) {
        return update(revision, state, age, "-");
    }
    private boolean update(String revision, AlarmStateProtocol.State state, long age, String completedRequest) {
        advance(2_000);
        WatchAlarmStore.Query query = WatchAlarmStore.beginQuery(now());
        if (query == null) {
            advance(10_000);
            query = WatchAlarmStore.beginQuery(now());
        }
        assertNotNull(query);
        assertTrue(WatchAlarmStore.selectSource(context, query, PHONE, now(), BOOT));
        return WatchAlarmStore.accept(context, PHONE, new AlarmStateProtocol.Report(query.nonce, state,
                AlarmStateProtocol.Availability.READY, revision, age, completedRequest), now(), BOOT);
    }
    private boolean push(String revision, AlarmStateProtocol.State state, long age) {
        return WatchAlarmStore.accept(context, PHONE, report("-", revision, state, age), now(), BOOT);
    }
    private static AlarmStateProtocol.Report report(String nonce, String revision, AlarmStateProtocol.State state, long age) {
        return new AlarmStateProtocol.Report(nonce, state, AlarmStateProtocol.Availability.READY, revision, age);
    }
    private static long now() { return SystemClock.elapsedRealtime(); }
    private static void advance(long millis) { ShadowSystemClock.advanceBy(Duration.ofMillis(millis)); }
}
