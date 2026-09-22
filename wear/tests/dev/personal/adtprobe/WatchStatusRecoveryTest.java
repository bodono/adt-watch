package dev.personal.adtprobe;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
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
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

/** Production recovery scheduling and report admission, with no GMS or alarm transport. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public final class WatchStatusRecoveryTest {
    private static final int BOOT = 23;
    private static final String PHONE = "inert-status-phone";
    private static final String R1 = "11111111-1111-1111-1111-111111111111";
    private static final String R2 = "22222222-2222-2222-2222-222222222222";
    private Context context;
    private WatchAlarmStore.StatusTransport original;
    private FakeTransport transport;

    @Before public void prepare() {
        context = RuntimeEnvironment.getApplication();
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, BOOT);
        reset();
        context.getSharedPreferences(WatchAlarmStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit();
        original = ReflectionHelpers.getStaticField(WatchAlarmStore.class, "transport");
        transport = new FakeTransport();
        ReflectionHelpers.setStaticField(WatchAlarmStore.class, "transport", transport);
    }

    @After public void close() {
        reset();
        ReflectionHelpers.setStaticField(WatchAlarmStore.class, "transport", original);
    }

    private void reset() {
        ((Handler) ReflectionHelpers.getStaticField(WatchAlarmStore.class, "MAIN")).removeCallbacksAndMessages(null);
        ReflectionHelpers.setStaticField(WatchAlarmStore.class, "pending", null);
        ReflectionHelpers.setStaticField(WatchAlarmStore.class, "recovery", null);
        ReflectionHelpers.setStaticField(WatchAlarmStore.class, "refreshQueued", false);
        ReflectionHelpers.setStaticField(WatchAlarmStore.class, "scheduledRefresh", null);
        ReflectionHelpers.setStaticField(WatchAlarmStore.class, "lastRefresh", -1L);
        ReflectionHelpers.setStaticField(WatchAlarmStore.class, "activeSelection", null);
    }

    @Test public void failedDiscoveryRecoversWithoutAnotherTapAndStopsAfterReady() {
        refresh();
        transport.discoveries.get(0).failed.run(); idle();
        assertFalse(WatchAlarmStore.read(context).enabled);
        assertEquals("Connecting to phone…", WatchAlarmStore.read(context).detail);
        for (int i = 0; i < 5; i++) refresh();
        advance(1_999);
        assertEquals(1, transport.discoveries.size());
        advance(1);
        assertEquals(2, transport.discoveries.size());
        connect(1);
        assertTrue(answer(0, AlarmStateProtocol.Availability.READY, AlarmStateProtocol.State.DISARMED, R1, 0));
        assertEquals(AlarmAction.ARM_STAY, WatchAlarmStore.read(context).action);
        assertNull(ReflectionHelpers.getStaticField(WatchAlarmStore.class, "activeSelection"));
        advance(30_000);
        assertEquals("Successful status recovery does not keep polling", 2, transport.discoveries.size());
        assertEquals(1, transport.queries.size());
    }

    @Test public void legacyPendingAutomaticallyGetsOneQualifyingQueryAtThirtySeconds() {
        seed(AlarmStateProtocol.State.DISARMED);
        android.content.SharedPreferences stored = context.getSharedPreferences(WatchAlarmStore.PREFERENCES, Context.MODE_PRIVATE);
        stored.edit().putBoolean("busy", true).putBoolean("awaiting", true).putString("actionSource", PHONE)
            .putString("actionRevision", R1).putString("actionState", "DISARMED")
            .remove("commitStarted").remove("commitBoot").remove("actionRequest").remove("commitMayHaveBeenSent").commit();
        transport.autoConnect = true; transport.autoAnswerReady = true;
        refresh();
        long boundary = stored.getLong("legacyWaitStarted", -1) + 30_000;
        advance(29_999);
        assertFalse(WatchAlarmStore.read(context).enabled);
        int previous = transport.queries.size();
        advance(1);
        assertEquals(previous + 1, transport.queries.size());
        assertTrue(transport.queries.get(previous).started >= boundary);
        assertEquals(AlarmAction.ARM_STAY, WatchAlarmStore.read(context).action);
        assertFalse(stored.getBoolean("busy", false));
        assertEquals("-", stored.getString("completedRequest", ""));
        assertNull(ReflectionHelpers.getStaticField(WatchAlarmStore.class, "activeSelection"));
        advance(60_000);
        assertEquals(previous + 1, transport.queries.size());
    }

    @Test public void lostCommitNeedsNoPhoneHintToRecoverUnchangedStateAtThirtySeconds() {
        WatchAlarmStore.ViewState before = seed(AlarmStateProtocol.State.DISARMED);
        commit(before, AlarmAction.ARM_STAY);
        transport.autoConnect = true; transport.autoAnswerReady = true;
        WatchAlarmStore.finish(context, true); idle();
        advance(29_999);
        assertFalse(WatchAlarmStore.read(context).enabled);
        int previous = transport.queries.size();
        advance(1);
        assertEquals(previous + 1, transport.queries.size());
        assertEquals("The unchanged actual state is current; command success is not inferred",
            AlarmAction.ARM_STAY, WatchAlarmStore.read(context).action);
        assertEquals("-", context.getSharedPreferences(WatchAlarmStore.PREFERENCES, Context.MODE_PRIVATE)
            .getString("completedRequest", ""));
        assertNull(ReflectionHelpers.getStaticField(WatchAlarmStore.class, "activeSelection"));
    }

    @Test public void finalBoundaryReadHasOnlyOneTenSecondAttemptAndCannotRestartItself() {
        WatchAlarmStore.ViewState before = seed(AlarmStateProtocol.State.DISARMED);
        commit(before, AlarmAction.ARM_STAY);
        transport.autoConnect = true; transport.autoAnswerReady = true;
        WatchAlarmStore.finish(context, true); idle(); advance(28_000);
        transport.autoAnswerReady = false;
        int previous = transport.queries.size();
        advance(2_000);
        assertEquals(previous + 1, transport.queries.size());
        assertTrue(WatchAlarmStore.isRefreshing());
        advance(10_000);
        assertFalse(WatchAlarmStore.isRefreshing());
        assertFalse(WatchAlarmStore.read(context).enabled);
        advance(60_000);
        assertEquals(previous + 1, transport.queries.size());
        assertEquals(previous + 1, transport.discoveries.size());
    }

    @Test public void aFinalBoundaryCallbackAfterLongSleepCanReadButCannotRequestLogin() {
        WatchAlarmStore.ViewState before = seed(AlarmStateProtocol.State.DISARMED);
        commit(before, AlarmAction.ARM_STAY);
        transport.autoConnect = true;
        WatchAlarmStore.finish(context, true); idle(); advance(2_000);
        assertEquals(AlarmStateProtocol.QueryIntent.USER, transport.queries.get(0).intent);
        int beforeSleep = transport.queries.size();
        ShadowSystemClock.advanceBy(Duration.ofMinutes(5)); // Advance elapsed time without dispatching the main Handler.
        idle();
        assertTrue("The delayed boundary still queries ground truth", transport.queries.size() > beforeSleep);
        for (int i = beforeSleep; i < transport.queries.size(); i++)
            assertEquals("A stale timer cannot revive user login authority", AlarmStateProtocol.QueryIntent.PASSIVE,
                transport.queries.get(i).intent);
        advance(10_000);
        assertFalse(WatchAlarmStore.isRefreshing());
    }

    @Test public void aFreshUserInteractionReplacesAnExpiredFinalReadOnceWithoutStarvingItsReply() {
        WatchAlarmStore.ViewState before = seed(AlarmStateProtocol.State.DISARMED);
        commit(before, AlarmAction.ARM_STAY); transport.autoConnect = true;
        WatchAlarmStore.finish(context, true); idle(); advance(2_000);
        ShadowSystemClock.advanceBy(Duration.ofMinutes(5)); idle();
        Object passive = WatchAlarmStore.refreshIdentity();
        int passiveQuery = transport.queries.size() - 1;
        assertEquals(AlarmStateProtocol.QueryIntent.PASSIVE, transport.queries.get(passiveQuery).intent);
        assertTrue(WatchAlarmStore.refreshIfNeeded(context, AlarmStateProtocol.QueryIntent.USER)); idle();
        Object user = WatchAlarmStore.refreshIdentity();
        assertNotSame(passive, user);
        int userQuery = transport.queries.size() - 1;
        assertEquals(passiveQuery + 1, userQuery);
        assertEquals(AlarmStateProtocol.QueryIntent.USER, transport.queries.get(userQuery).intent);
        for (int i = 0; i < 4; i++) {
            advance(250);
            assertTrue(WatchAlarmStore.refreshIfNeeded(context, AlarmStateProtocol.QueryIntent.USER)); idle();
            assertSame(user, WatchAlarmStore.refreshIdentity());
            assertEquals("UI ticks share the new query", userQuery + 1, transport.queries.size());
        }
        assertFalse(answer(passiveQuery, AlarmStateProtocol.Availability.NO_ACCESS, AlarmStateProtocol.State.UNKNOWN, "-", 0));
        assertTrue(answer(userQuery, AlarmStateProtocol.Availability.READY, AlarmStateProtocol.State.DISARMED, R1, 0));
        assertTrue(WatchAlarmStore.read(context).enabled);
    }

    @Test public void anExplicitRefreshQueuedBeforeLongSleepRemainsPassiveWhenTheHandlerResumes() {
        transport.autoConnect = true;
        WatchAlarmStore.refresh(context);
        ShadowSystemClock.advanceBy(Duration.ofMinutes(5)); idle();
        assertEquals(1, transport.queries.size());
        assertEquals(AlarmStateProtocol.QueryIntent.PASSIVE, transport.queries.get(0).intent);
    }

    @Test public void delayedActionCompletionDoesNotManufactureANewUserInteraction() {
        WatchAlarmStore.ViewState before = seed(AlarmStateProtocol.State.DISARMED);
        commit(before, AlarmAction.ARM_STAY);
        transport.autoConnect = true;
        ShadowSystemClock.advanceBy(Duration.ofMinutes(5));
        WatchAlarmStore.finish(context, true); idle();
        assertEquals(1, transport.queries.size());
        assertEquals(AlarmStateProtocol.QueryIntent.PASSIVE, transport.queries.get(0).intent);
    }

    @Test public void disarmWaitingForAdtBecomesArmableAfterLaterStatusReply() {
        WatchAlarmStore.ViewState armed = seed(AlarmStateProtocol.State.ARMED_STAY);
        String request = commit(armed, AlarmAction.DISARM);
        WatchAlarmStore.finish(context, true); idle();
        advance(2_000); connect(0);
        assertEquals(AlarmStateProtocol.QueryIntent.USER, transport.queries.get(0).intent);
        assertTrue(answer(0, AlarmStateProtocol.Availability.BUSY, AlarmStateProtocol.State.UNKNOWN, "-", 0));
        assertFalse(WatchAlarmStore.read(context).enabled);
        assertTrue(WatchAlarmStore.read(context).detail.contains("waiting for ADT"));
        advance(2_000); connect(1);
        assertTrue(answer(1, AlarmStateProtocol.Availability.READY, AlarmStateProtocol.State.DISARMED, R2, 0, request));
        assertTrue(WatchAlarmStore.read(context).enabled);
        assertEquals(AlarmAction.ARM_STAY, WatchAlarmStore.read(context).action);
        assertNull("A recovered state cannot authorize an action", ReflectionHelpers.getStaticField(WatchAlarmStore.class, "activeSelection"));
        assertNull(WatchAlarmStore.consume(context, armed.revision, AlarmAction.ARM_STAY));
        advance(30_000);
        assertEquals(2, transport.queries.size());
    }

    @Test public void repeatedTriggersCoalesceAndCannotExtendTheThirtySecondWindow() {
        transport.failDiscovery = true;
        refresh();
        advance(10_000);
        for (int i = 0; i < 10; i++) refresh();
        advance(20_000);
        assertEquals(15, transport.discoveries.size());
        assertEquals("No phone reply. Tap Refresh", WatchAlarmStore.read(context).detail);
        advance(60_000);
        assertEquals(15, transport.discoveries.size());
        assertTrue(transport.queries.isEmpty());
    }

    @Test public void staleFailureAndReplyCannotUndoARecoveredState() {
        refresh(); connect(0);
        advance(12_000); connect(1);
        assertTrue(answer(1, AlarmStateProtocol.Availability.READY, AlarmStateProtocol.State.ARMED_STAY, R2, 0));
        transport.queries.get(0).failed.run();
        transport.discoveries.get(0).failed.run(); idle();
        assertFalse(answer(0, AlarmStateProtocol.Availability.READY, AlarmStateProtocol.State.DISARMED, R1, 0));
        advance(18_000);
        assertTrue(WatchAlarmStore.read(context).enabled);
        assertEquals(AlarmAction.DISARM, WatchAlarmStore.read(context).action);
        assertEquals(2, transport.queries.size());
    }

    @Test public void windowDeadlineRejectsTheLastInFlightReply() {
        refresh(); connect(0);
        advance(12_000); connect(1);
        advance(12_000); connect(2);
        advance(6_000);
        assertFalse(answer(2, AlarmStateProtocol.Availability.READY, AlarmStateProtocol.State.DISARMED, R1, 0));
        assertFalse(WatchAlarmStore.read(context).enabled);
        assertEquals("No phone reply. Tap Refresh", WatchAlarmStore.read(context).detail);
        advance(30_000);
        assertEquals(3, transport.queries.size());
    }

    @Test public void deadlineWithAnInFlightQueryKeepsTheLastAnswerWhenThePhoneWasReplying() {
        refresh();
        for (int i = 0; i < 14; i++) {
            if (i > 0) advance(2_000);
            connect(i);
            assertTrue(answer(i, AlarmStateProtocol.Availability.BUSY, AlarmStateProtocol.State.UNKNOWN, "-", 0));
        }
        advance(2_000); connect(14);
        assertEquals(15, transport.queries.size());
        assertTrue(WatchAlarmStore.isRefreshing());
        advance(2_000);
        assertFalse(WatchAlarmStore.isRefreshing());
        assertFalse(answer(14, AlarmStateProtocol.Availability.READY, AlarmStateProtocol.State.DISARMED, R1, 0));
        assertFalse(WatchAlarmStore.read(context).enabled);
        assertEquals("A phone that answered four seconds ago is not reported as unreachable",
                "Phone reached; waiting for ADT", WatchAlarmStore.read(context).detail);
        advance(30_000);
        assertEquals(15, transport.queries.size());
    }

    @Test public void consumingActionCancelsRecoveryUntilFinishWithoutReplayingTheTap() {
        WatchAlarmStore.ViewState armed = seed(AlarmStateProtocol.State.ARMED_STAY);
        advance(2_000); refresh();
        assertEquals(1, transport.discoveries.size());
        WatchAlarmStore.Selection selected = WatchAlarmStore.consume(context, armed.revision, AlarmAction.DISARM);
        assertNotNull(selected);
        connect(0);
        refresh(); advance(30_000);
        assertTrue("A stale status timeout cannot cancel action readiness", WatchAlarmStore.stillCurrent(context, selected));
        assertEquals(1, transport.discoveries.size());
        assertTrue("Discovery completed after consume must not send a query", transport.queries.isEmpty());
        WatchAlarmStore.finish(context, false); idle();
        connect(1);
        assertTrue(answer(0, AlarmStateProtocol.Availability.READY, AlarmStateProtocol.State.ARMED_STAY, R1, 32_000));
        assertEquals(AlarmAction.DISARM, WatchAlarmStore.read(context).action);
        assertNull(WatchAlarmStore.consume(context, armed.revision, AlarmAction.DISARM));
        assertNull(ReflectionHelpers.getStaticField(WatchAlarmStore.class, "activeSelection"));
    }

    @Test public void connectedPhoneWithOfflineListenerHasDifferentDetailFromTransportFailure() {
        refresh(); connect(0);
        assertTrue(answer(0, AlarmStateProtocol.Availability.OFFLINE, AlarmStateProtocol.State.UNKNOWN, "-", 0));
        assertEquals("Phone reached; ADT status unavailable", WatchAlarmStore.read(context).detail);
        advance(2_000);
        transport.discoveries.get(1).failed.run(); idle();
        assertEquals("Connecting to phone…", WatchAlarmStore.read(context).detail);
        assertFalse(WatchAlarmStore.read(context).enabled);
    }

    @Test public void terminalSetupProblemStopsRetriesButKeepsItsActionableExplanation() {
        refresh(); connect(0);
        assertTrue(answer(0, AlarmStateProtocol.Availability.NO_ACCESS, AlarmStateProtocol.State.UNKNOWN, "-", 0));
        assertEquals("Sign in via ADT Watch Setup on phone", WatchAlarmStore.read(context).detail);
        advance(30_000);
        assertEquals(1, transport.discoveries.size());
        assertFalse(WatchAlarmStore.read(context).enabled);
    }

    @Test public void aPhoneHintAfterTheSignInAnswerRestartsOneBoundedQuery() {
        refresh(); connect(0);
        assertTrue(answer(0, AlarmStateProtocol.Availability.NO_ACCESS, AlarmStateProtocol.State.UNKNOWN, "-", 0));
        advance(30_000);
        assertEquals("The sign-in answer stopped polling", 1, transport.discoveries.size());
        assertEquals(1, transport.queries.size());
        // The phone logged in again by itself and says so with a hint, whatever that hint's own availability.
        transport.autoConnect = true;
        byte[] payload = new AlarmStateProtocol.Report("-", AlarmStateProtocol.State.UNKNOWN,
            AlarmStateProtocol.Availability.OFFLINE, "-", 0).encode();
        WatchAlarmStore.receive(context, new MessageEvent() {
            @Override public int getRequestId() { return 0; }
            @Override public String getPath() { return AlarmStateProtocol.STATE_PATH; }
            @Override public byte[] getData() { return payload; }
            @Override public String getSourceNodeId() { return PHONE; }
        });
        advance(1_000);
        assertTrue("The hint restarted a bounded query", transport.queries.size() >= 2);
        int last = transport.queries.size() - 1;
        assertTrue(answer(last, AlarmStateProtocol.Availability.READY, AlarmStateProtocol.State.DISARMED, R1, 0));
        assertEquals(AlarmAction.ARM_STAY, WatchAlarmStore.read(context).action);
        int settled = transport.queries.size();
        advance(30_000);
        assertEquals("A READY answer ends the recovery again", settled, transport.queries.size());
    }

    @Test public void passiveRendererQueriesAndTheirRetriesNeverRequestPasswordLogin() {
        transport.autoConnect = true;
        assertTrue(WatchAlarmStore.refreshIfNeeded(context)); idle();
        assertEquals(AlarmStateProtocol.QueryIntent.PASSIVE, transport.queries.get(0).intent);
        assertTrue(answer(0, AlarmStateProtocol.Availability.OFFLINE, AlarmStateProtocol.State.UNKNOWN, "-", 0));
        advance(2_000);
        assertEquals(AlarmStateProtocol.QueryIntent.PASSIVE, transport.queries.get(1).intent);
        assertTrue(answer(1, AlarmStateProtocol.Availability.NO_ACCESS, AlarmStateProtocol.State.UNKNOWN, "-", 0));
        advance(30_000);
        assertEquals(2, transport.queries.size());
    }

    @Test public void unsolicitedHintsRemainPassiveAcrossRetriesAndLaterHints() {
        seed(AlarmStateProtocol.State.DISARMED); advance(2_000); transport.autoConnect = true;
        push(PHONE, AlarmStateProtocol.State.ARMED_STAY, R2);
        assertEquals(AlarmStateProtocol.QueryIntent.PASSIVE, transport.queries.get(0).intent);
        assertTrue(answer(0, AlarmStateProtocol.Availability.NO_ACCESS, AlarmStateProtocol.State.UNKNOWN, "-", 0));
        advance(1_000);
        push(PHONE, AlarmStateProtocol.State.ARMED_STAY, R2);
        assertEquals(AlarmStateProtocol.QueryIntent.PASSIVE, transport.queries.get(1).intent);
        assertNull(ReflectionHelpers.getStaticField(WatchAlarmStore.class, "activeSelection"));
    }

    @Test public void userRefreshPromotesAnInFlightPassiveQueryWithoutExtendingItsDeadline() {
        transport.autoConnect = true;
        assertTrue(WatchAlarmStore.refreshIfNeeded(context)); idle();
        Object original = WatchAlarmStore.refreshIdentity();
        long started = now();
        advance(1_000); refresh();
        assertSame("Promotion retains the bounded recovery", original, WatchAlarmStore.refreshIdentity());
        assertEquals(2, transport.queries.size());
        assertEquals(AlarmStateProtocol.QueryIntent.PASSIVE, transport.queries.get(0).intent);
        assertEquals(AlarmStateProtocol.QueryIntent.USER, transport.queries.get(1).intent);
        assertNotEquals(transport.queries.get(0).nonce, transport.queries.get(1).nonce);
        assertFalse("Retired passive sign-in answer cannot cancel the user check",
            answer(0, AlarmStateProtocol.Availability.NO_ACCESS, AlarmStateProtocol.State.UNKNOWN, "-", 0));
        transport.queries.get(0).failed.run(); idle();
        assertSame(original, WatchAlarmStore.refreshIdentity());
        advance(30_000 - (now() - started));
        assertFalse("Promotion does not extend thirty seconds", WatchAlarmStore.isRefreshing());
        assertFalse(answer(1, AlarmStateProtocol.Availability.READY, AlarmStateProtocol.State.DISARMED, R1, 0));
    }

    @Test public void aUserEntryCanRecoverAfterAPassiveSignInAnswerWithoutWaitingForRenderCooldown() {
        transport.autoConnect = true;
        assertTrue(WatchAlarmStore.refreshIfNeeded(context)); idle();
        assertTrue(answer(0, AlarmStateProtocol.Availability.NO_ACCESS, AlarmStateProtocol.State.UNKNOWN, "-", 0));
        assertFalse(WatchAlarmStore.refreshIfNeeded(context));
        assertTrue(WatchAlarmStore.refreshIfNeeded(context, AlarmStateProtocol.QueryIntent.USER));
        advance(2_000);
        assertEquals(AlarmStateProtocol.QueryIntent.USER, transport.queries.get(1).intent);
        assertTrue(answer(1, AlarmStateProtocol.Availability.NO_ACCESS, AlarmStateProtocol.State.UNKNOWN, "-", 0));
        for (int i = 0; i < 10; i++)
            assertFalse("Interactive UI ticks cannot loop after a failed user check",
                WatchAlarmStore.refreshIfNeeded(context, AlarmStateProtocol.QueryIntent.USER));
    }

    @Test public void hintsDoNotChangeTheIntentOrDeadlineOfAnExistingUserCheck() {
        seed(AlarmStateProtocol.State.DISARMED); advance(2_000); transport.autoConnect = true;
        refresh(); Object original = WatchAlarmStore.refreshIdentity();
        assertEquals(AlarmStateProtocol.QueryIntent.USER, transport.queries.get(0).intent);
        assertTrue(answer(0, AlarmStateProtocol.Availability.BUSY, AlarmStateProtocol.State.UNKNOWN, "-", 0));
        push(PHONE, AlarmStateProtocol.State.ARMED_STAY, R2); advance(250);
        assertSame(original, WatchAlarmStore.refreshIdentity());
        assertEquals(AlarmStateProtocol.QueryIntent.USER, transport.queries.get(1).intent);
    }

    @Test public void passiveRenderingRefreshesNearExpiryAndCoalescesWhileItWaits() {
        seed(AlarmStateProtocol.State.DISARMED);
        assertFalse(WatchAlarmStore.refreshIfNeeded(context));
        advance(44_999);
        assertFalse(WatchAlarmStore.refreshIfNeeded(context));
        assertTrue(transport.discoveries.isEmpty());
        advance(1);
        assertTrue(WatchAlarmStore.refreshIfNeeded(context));
        assertTrue(WatchAlarmStore.isRefreshing());
        for (int i = 0; i < 10; i++) assertTrue(WatchAlarmStore.refreshIfNeeded(context));
        assertEquals(1, transport.discoveries.size());
        connect(0);
        assertTrue(answer(0, AlarmStateProtocol.Availability.READY, AlarmStateProtocol.State.DISARMED, R1, 0));
        assertFalse(WatchAlarmStore.isRefreshing());
        assertFalse(WatchAlarmStore.refreshIfNeeded(context));
        advance(45_000);
        assertTrue("Confirmed status permits the next near-expiry refresh", WatchAlarmStore.refreshIfNeeded(context));
        assertEquals(2, transport.discoveries.size());
    }

    @Test public void passiveFailureCannotLoopAndItsCooldownSurvivesProcessReset() {
        transport.failDiscovery = true;
        assertTrue(WatchAlarmStore.refreshIfNeeded(context)); idle();
        advance(30_000);
        assertFalse(WatchAlarmStore.isRefreshing());
        assertEquals(15, transport.discoveries.size());
        for (int i = 0; i < 20; i++) assertFalse(WatchAlarmStore.refreshIfNeeded(context));
        reset(); // The persisted boot/time guard must still prevent renderer-driven restart.
        advance(29_999);
        assertFalse(WatchAlarmStore.refreshIfNeeded(context));
        assertEquals(15, transport.discoveries.size());
        advance(1);
        assertTrue("A later external render may retry after the cooldown", WatchAlarmStore.refreshIfNeeded(context)); idle();
        assertEquals(16, transport.discoveries.size());
    }

    @Test public void explicitRefreshCanBypassPassiveCooldownButNotActiveSelection() {
        transport.failDiscovery = true;
        assertTrue(WatchAlarmStore.refreshIfNeeded(context)); idle();
        advance(30_000);
        assertFalse(WatchAlarmStore.refreshIfNeeded(context));
        refresh();
        assertTrue(WatchAlarmStore.isRefreshing());
        assertEquals(16, transport.discoveries.size());

        reset();
        context.getSharedPreferences(WatchAlarmStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit();
        WatchAlarmStore.ViewState armed = seed(AlarmStateProtocol.State.ARMED_STAY);
        assertNotNull(WatchAlarmStore.consume(context, armed.revision, AlarmAction.DISARM));
        assertFalse(WatchAlarmStore.refreshIfNeeded(context));
        refresh();
        assertFalse(WatchAlarmStore.isRefreshing());
        assertEquals(16, transport.discoveries.size());
    }

    @Test public void trustedPushExpeditesTheQueuedBusyRetryWithAFreshNonce() {
        WatchAlarmStore.ViewState armed = seed(AlarmStateProtocol.State.ARMED_STAY);
        String request = commit(armed, AlarmAction.DISARM);
        WatchAlarmStore.finish(context, true); idle();
        advance(2_000); connect(0);
        assertTrue(answer(0, AlarmStateProtocol.Availability.BUSY, AlarmStateProtocol.State.UNKNOWN, "-", 0));
        advance(100);
        push(PHONE, AlarmStateProtocol.State.DISARMED, R2);
        for (int i = 0; i < 10; i++) push(PHONE, AlarmStateProtocol.State.DISARMED, R2);
        advance(149);
        assertEquals("Hints are rate limited to 250ms between query starts", 1, transport.discoveries.size());
        advance(1); connect(1);
        assertNotEquals(transport.queries.get(0).nonce, transport.queries.get(1).nonce);
        assertTrue(answer(1, AlarmStateProtocol.Availability.READY, AlarmStateProtocol.State.DISARMED, R2, 0, request));
        assertEquals(AlarmAction.ARM_STAY, WatchAlarmStore.read(context).action);
        assertNull("A hint cannot revive the consumed action", ReflectionHelpers.getStaticField(WatchAlarmStore.class, "activeSelection"));
        assertNull(WatchAlarmStore.consume(context, armed.revision, AlarmAction.ARM_STAY));
        advance(30_000);
        assertEquals("Superseded ordinary retries cannot resume", 2, transport.discoveries.size());
    }

    @Test public void expeditedFailureStillUsesOrdinaryBackoffAndIgnoresTheOldSchedule() {
        refresh(); connect(0);
        assertTrue(answer(0, AlarmStateProtocol.Availability.BUSY, AlarmStateProtocol.State.UNKNOWN, "-", 0));
        advance(100); push(PHONE, AlarmStateProtocol.State.DISARMED, R1);
        advance(150);
        assertEquals(2, transport.discoveries.size());
        transport.discoveries.get(1).failed.run(); idle();
        advance(1_750);
        assertEquals("The replaced 2s callback must not run or clear the new retry", 2, transport.discoveries.size());
        advance(249);
        assertEquals(2, transport.discoveries.size());
        advance(1);
        assertEquals("An expedited query failure still waits a full 2s", 3, transport.discoveries.size());
    }

    @Test public void repeatedTrustedHintsPreserveTheLiveNonceAndCannotStarveItsAnswer() {
        refresh(); connect(0);
        advance(100); push("untrusted-phone", AlarmStateProtocol.State.DISARMED, R1);
        advance(200);
        assertEquals(1, transport.discoveries.size());
        for (int i = 0; i < 5; i++) {
            advance(1_000); push(PHONE, AlarmStateProtocol.State.DISARMED, R1);
            assertFalse("A hint itself grants no authority", WatchAlarmStore.read(context).enabled);
        }
        assertEquals("Repeated hints coalesce with the existing query", 1, transport.discoveries.size());
        assertEquals(1, transport.queries.size());
        assertTrue(answer(0, AlarmStateProtocol.Availability.READY, AlarmStateProtocol.State.DISARMED, R1, 5_300));
        assertTrue(WatchAlarmStore.read(context).enabled);
    }

    @Test public void expeditedHintCannotExtendTheActiveRecoveryDeadline() {
        refresh(); connect(0);
        advance(29_000);
        assertEquals(3, transport.discoveries.size());
        push(PHONE, AlarmStateProtocol.State.DISARMED, R1);
        assertEquals(3, transport.discoveries.size());
        connect(2);
        advance(1_000);
        assertFalse(WatchAlarmStore.isRefreshing());
        assertFalse(answer(1, AlarmStateProtocol.Availability.READY, AlarmStateProtocol.State.DISARMED, R1, 0));
        advance(30_000);
        assertEquals(3, transport.discoveries.size());
    }

    private WatchAlarmStore.ViewState seed(AlarmStateProtocol.State state) {
        WatchAlarmStore.Query query = WatchAlarmStore.beginQuery(now());
        assertNotNull(query);
        assertTrue(WatchAlarmStore.selectSource(context, query, PHONE, now(), BOOT));
        assertTrue(WatchAlarmStore.accept(context, PHONE, new AlarmStateProtocol.Report(query.nonce,
                state, AlarmStateProtocol.Availability.READY, R1, 0, "-",
                AlarmStateProtocol.Evidence.ADT_QUERY, UUID.randomUUID().toString()), now(), BOOT));
        return WatchAlarmStore.read(context);
    }

    private String commit(WatchAlarmStore.ViewState state, AlarmAction action) {
        WatchAlarmStore.Selection selection = WatchAlarmStore.consume(context, state.revision, action);
        assertNotNull(selection);
        String request = UUID.randomUUID().toString();
        assertTrue(WatchAlarmStore.attachRequest(context, selection, request));
        assertTrue(WatchAlarmStore.markCommitting(context, selection, request));
        return request;
    }

    private boolean answer(int queryIndex, AlarmStateProtocol.Availability availability,
            AlarmStateProtocol.State state, String revision, long age) {
        return answer(queryIndex, availability, state, revision, age, "-");
    }

    private boolean answer(int queryIndex, AlarmStateProtocol.Availability availability,
            AlarmStateProtocol.State state, String revision, long age, String completedRequest) {
        return WatchAlarmStore.accept(context, PHONE, new AlarmStateProtocol.Report(
                transport.queries.get(queryIndex).nonce, state, availability, revision, age, completedRequest,
                AlarmStateProtocol.Evidence.ADT_QUERY, UUID.randomUUID().toString()), now(), BOOT);
    }
    private void connect(int discovery) { transport.discoveries.get(discovery).found.accept(PHONE); idle(); }
    private void push(String source, AlarmStateProtocol.State state, String revision) {
        byte[] payload = new AlarmStateProtocol.Report("-", state, AlarmStateProtocol.Availability.READY, revision, 0).encode();
        WatchAlarmStore.receive(context, new MessageEvent() {
            @Override public int getRequestId() { return 0; }
            @Override public String getPath() { return AlarmStateProtocol.STATE_PATH; }
            @Override public byte[] getData() { return payload; }
            @Override public String getSourceNodeId() { return source; }
        });
        idle();
    }
    private void refresh() { WatchAlarmStore.refresh(context); idle(); }
    private void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private void advance(long millis) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis)); }
    private long now() { return SystemClock.elapsedRealtime(); }

    private static final class Discovery {
        final Consumer<String> found;
        final Runnable failed;
        Discovery(Consumer<String> found, Runnable failed) { this.found = found; this.failed = failed; }
    }
    private static final class StatusQuery {
        final String nonce;
        final Runnable failed;
        final AlarmStateProtocol.QueryIntent intent;
        final long started = SystemClock.elapsedRealtime();
        StatusQuery(String nonce, AlarmStateProtocol.QueryIntent intent, Runnable failed) {
            this.nonce = nonce; this.intent = intent; this.failed = failed;
        }
    }
    private static final class FakeTransport implements WatchAlarmStore.StatusTransport {
        final List<Discovery> discoveries = new ArrayList<>();
        final List<StatusQuery> queries = new ArrayList<>();
        boolean failDiscovery, autoConnect, autoAnswerReady;
        @Override public void discover(Context context, Consumer<String> found, Runnable failed) {
            discoveries.add(new Discovery(found, failed));
            if (failDiscovery) failed.run();
            else if (autoConnect) found.accept(PHONE);
        }
        @Override public void query(Context context, String phone, String nonce, AlarmStateProtocol.QueryIntent intent, Runnable failed) {
            assertEquals(PHONE, phone);
            assertTrue(AlarmStateProtocol.uuid(nonce));
            queries.add(new StatusQuery(nonce, intent, failed));
            if (autoAnswerReady) assertTrue(WatchAlarmStore.accept(context, PHONE, new AlarmStateProtocol.Report(nonce,
                AlarmStateProtocol.State.DISARMED, AlarmStateProtocol.Availability.READY, R1, 0, "-",
                AlarmStateProtocol.Evidence.ADT_QUERY, UUID.randomUUID().toString()), SystemClock.elapsedRealtime(), BOOT));
        }
    }
}
