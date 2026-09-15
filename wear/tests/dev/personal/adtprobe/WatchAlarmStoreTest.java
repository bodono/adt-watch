package dev.personal.adtprobe;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import java.time.Duration;
import org.junit.Before;
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
        context = RuntimeEnvironment.getApplication();
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, BOOT);
        context.getSharedPreferences(WatchAlarmStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit();
        ReflectionHelpers.setStaticField(WatchAlarmStore.class, "pending", null);
        ReflectionHelpers.setStaticField(WatchAlarmStore.class, "lastRefresh", -1L);
        ReflectionHelpers.setStaticField(WatchAlarmStore.class, "refreshQueued", false);
        ReflectionHelpers.setStaticField(WatchAlarmStore.class, "activeSelection", null);
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
        assertNotNull(WatchAlarmStore.consume(context, view.revision, AlarmAction.ARM_STAY));
        ReflectionHelpers.setStaticField(WatchAlarmStore.class, "activeSelection", null);
        assertFalse(WatchAlarmStore.read(context).enabled);
        assertTrue(context.getSharedPreferences(WatchAlarmStore.PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean("awaiting", false));
        assertTrue(update(R1, AlarmStateProtocol.State.DISARMED, 2_100));
        assertFalse(WatchAlarmStore.read(context).enabled);
        assertTrue(update(R2, AlarmStateProtocol.State.ARMED_STAY, 0));
        assertEquals(AlarmAction.DISARM, WatchAlarmStore.read(context).action);
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
        advance(2_000);
        WatchAlarmStore.Query query = WatchAlarmStore.beginQuery(now());
        if (query == null) {
            advance(10_000);
            query = WatchAlarmStore.beginQuery(now());
        }
        assertNotNull(query);
        assertTrue(WatchAlarmStore.selectSource(context, query, PHONE, now(), BOOT));
        return WatchAlarmStore.accept(context, PHONE, report(query.nonce, revision, state, age), now(), BOOT);
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
