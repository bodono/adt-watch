package dev.personal.adtprobe;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import java.time.Duration;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Inert backend fixtures: no session, notification, network or alarm widget. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public final class PhoneAlarmStateTest {
    private Context context;
    private PhoneAlarmState.Query oldQuery;
    private PhoneAlarmState.Schedule oldSchedule;
    private AdtPortalClient.Result answer;
    private int queries;
    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(PhoneAlarmState.PREFERENCES, 0).edit().clear().commit();
        context.getSharedPreferences("adt_portal_binding", 0).edit().clear().commit();
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 3);
        ReflectionHelpers.setStaticField(PhoneAlarmState.class, "storageFailed", false);
        oldQuery = PhoneAlarmState.queryOperation; oldSchedule = PhoneAlarmState.scheduleOperation;
        PhoneAlarmState.scheduleOperation = (action, delay) -> { };
        PhoneAlarmState.queryOperation = (app, deadline) -> { queries++; return answer; };
        answer = result(AlarmStateProtocol.State.DISARMED);
    }
    @After public void finish() { PhoneAlarmState.queryOperation = oldQuery; PhoneAlarmState.scheduleOperation = oldSchedule; }
    @Test public void legacyManualOrNotificationCacheCannotSupplyState() {
        context.getSharedPreferences("reported_alarm_state", 0).edit().putString("state", "ARMED_STAY")
            .putString("evidence", "PHONE_CHECK").putLong("event_ms", System.currentTimeMillis()).commit();
        assertEquals(AlarmStateProtocol.Availability.SETUP, PhoneAlarmState.snapshot(context).availability);
        assertEquals(0, queries);
        bind();
        assertEquals(AlarmStateProtocol.Availability.NO_STATE, PhoneAlarmState.snapshot(context).availability);
        assertEquals(AlarmStateProtocol.State.DISARMED, read().state);
        assertEquals(AlarmStateProtocol.Evidence.ADT_QUERY, PhoneAlarmState.snapshot(context).evidence);
    }
    @Test public void unchangedReadRenewsObservationButPreservesStateRevision() {
        bind(); PhoneAlarmState.Snapshot first = read();
        advance(55_000); PhoneAlarmState.Snapshot second = read();
        assertEquals(first.revision, second.revision); assertNotEquals(first.observationId, second.observationId);
        assertEquals(2, queries); assertEquals(AlarmStateProtocol.Availability.READY, second.availability);
        advance(60_000); assertEquals(AlarmStateProtocol.Availability.STALE, PhoneAlarmState.snapshot(context).availability);
        assertFalse(PhoneAlarmState.matches(context, first.revision, AlarmAction.ARM_STAY));
    }
    @Test public void failedReadImmediatelyGatesEarlierFreshObservation() {
        bind(); PhoneAlarmState.Snapshot first = read();
        answer = failure(AdtPortalClient.Status.LOGIN_REQUIRED); read();
        assertEquals(AlarmStateProtocol.Availability.NO_ACCESS, PhoneAlarmState.snapshot(context).availability);
        assertFalse(PhoneAlarmState.matches(context, first.revision, AlarmAction.ARM_STAY));
        answer = failure(AdtPortalClient.Status.UNAVAILABLE); read();
        assertEquals(AlarmStateProtocol.Availability.OFFLINE, PhoneAlarmState.snapshot(context).availability);
        answer = result(AlarmStateProtocol.State.DISARMED);
        assertEquals(AlarmStateProtocol.Availability.READY, read().availability);
    }
    @Test public void explicitBindingAndMidReadChangeRejectWrongHome() {
        bind(); read();
        answer = new AdtPortalClient.Result(AdtPortalClient.Status.READY, AlarmStateProtocol.State.ARMED_STAY,
            "other", "partition-1", "Other", "Panel", 1);
        assertEquals(AlarmStateProtocol.Availability.NO_STATE, read().availability);
        answer = result(AlarmStateProtocol.State.DISARMED);
        PhoneAlarmState.queryOperation = (app, deadline) -> {
            AdtPortalSession.bind(app, "other", "other-partition"); return answer;
        };
        assertEquals(AlarmStateProtocol.Availability.NO_STATE, read().availability);
        assertFalse(PhoneAlarmState.snapshot(context).state == AlarmStateProtocol.State.DISARMED);
    }
    @Test public void reselectingSameHomeThenFailingDoesNotResurrectOldLedger() {
        bind(); PhoneAlarmState.Snapshot first = read();
        bind(); answer = failure(AdtPortalClient.Status.UNAVAILABLE); read();
        assertEquals("-", PhoneAlarmState.snapshot(context).revision);
        answer = result(AlarmStateProtocol.State.DISARMED);
        assertNotEquals(first.revision, read().revision);
    }
    @Test public void durablePendingCompletesOnlyFromFreshActualTarget() {
        bind(); PhoneAlarmState.Snapshot first = read();
        String request = UUID.randomUUID().toString();
        assertTrue(PhoneAlarmState.beginCommand(context, first.revision, AlarmAction.ARM_STAY, request));
        assertEquals("PENDING", context.getSharedPreferences(PhoneAlarmState.PREFERENCES, 0).getString("outcome", ""));
        assertTrue(read().pending);
        assertFalse(PhoneAlarmState.beginCommand(context, first.revision, AlarmAction.ARM_STAY, request));
        answer = result(AlarmStateProtocol.State.ARMED_STAY);
        PhoneAlarmState.Snapshot completed = read();
        assertFalse(completed.pending); assertEquals(request, completed.completedRequest);
        assertEquals(AlarmStateProtocol.Availability.READY, completed.availability);
    }
    @Test public void timeoutExposesFreshActualStateWithoutInventingCommandSuccess() {
        bind(); PhoneAlarmState.Snapshot first = read();
        assertTrue(PhoneAlarmState.beginCommand(context, first.revision, AlarmAction.ARM_STAY, UUID.randomUUID().toString()));
        advance(30_000); PhoneAlarmState.Snapshot current = read();
        assertEquals(AlarmStateProtocol.State.DISARMED, current.state);
        assertEquals(AlarmStateProtocol.Availability.READY, current.availability);
        assertFalse(current.pending); assertEquals("-", current.completedRequest);
        assertEquals("UNCONFIRMED", context.getSharedPreferences(PhoneAlarmState.PREFERENCES, 0).getString("outcome", ""));
    }
    @Test public void readsInsideTheReuseWindowShareOneQueryAndAFailedReadIsNeverReused() {
        bind(); PhoneAlarmState.Snapshot first = read();
        advance(1_000);
        PhoneAlarmState.Snapshot reused = PhoneAlarmState.refresh(context);
        assertEquals("A read one second old is answered from the ledger", 1, queries);
        assertEquals(first.observationId, reused.observationId);
        assertEquals(AlarmStateProtocol.Availability.READY, reused.availability);
        advance(PhoneAlarmState.REUSE_MS);
        PhoneAlarmState.Snapshot renewed = PhoneAlarmState.refresh(context);
        assertEquals("Past the window the phone reads ADT again", 2, queries);
        assertNotEquals(first.observationId, renewed.observationId);
        answer = failure(AdtPortalClient.Status.UNAVAILABLE); advance(1);
        assertEquals(AlarmStateProtocol.Availability.OFFLINE, PhoneAlarmState.refresh(context, 0).availability);
        assertEquals(3, queries);
        answer = result(AlarmStateProtocol.State.DISARMED); advance(1);
        assertEquals("A failed read is not reused; the next caller retries at once",
            AlarmStateProtocol.Availability.READY, PhoneAlarmState.refresh(context).availability);
        assertEquals(4, queries);
    }
    @Test public void onlyACompletedOrSharedReadIsVerified() {
        assertFalse("Without a binding nothing is read", PhoneAlarmState.refresh(context).verified);
        bind();
        assertTrue(read().verified);
        assertFalse("A passive snapshot never claims a read", PhoneAlarmState.snapshot(context).verified);
        advance(1_000);
        assertTrue("A read shared inside the window counts", PhoneAlarmState.refresh(context).verified);
        assertEquals(1, queries);
        answer = failure(AdtPortalClient.Status.UNAVAILABLE);
        assertFalse("A failed read does not", read().verified);
        answer = result(AlarmStateProtocol.State.DISARMED);
        PhoneAlarmState.queryOperation = (app, deadline) -> { queries++; advance(8_001); return answer; };
        assertFalse("Nor does one that outran its deadline", read().verified);
        assertEquals(3, queries);
    }
    @Test public void aConfirmationReadMustHaveStartedAfterTheCommand() {
        bind(); read();
        long requestStarted = SystemClock.elapsedRealtime();
        assertTrue(PhoneAlarmState.beginCommand(context, PhoneAlarmState.snapshot(context).revision, AlarmAction.ARM_STAY,
            UUID.randomUUID().toString()));
        advance(1_000);
        PhoneAlarmState.refresh(context, PhoneAlarmState.REUSE_MS, requestStarted);
        assertEquals("The preflight read predates the command, so the poll reads again", 2, queries);
        advance(1_000);
        PhoneAlarmState.refresh(context, PhoneAlarmState.REUSE_MS, requestStarted);
        assertEquals("A post-command read younger than the window is reused", 2, queries);
    }
    @Test public void aPollPublishesOnlyWhenSomethingChanged() {
        bind(); PhoneAlarmState.Snapshot first = read();
        assertFalse(PhoneAlarmState.changed(first, PhoneAlarmState.snapshot(context)));
        advance(1);
        assertFalse("A renewed observation of the same state is not a change", PhoneAlarmState.changed(first, read()));
        answer = result(AlarmStateProtocol.State.ARMED_STAY); advance(1);
        assertTrue(PhoneAlarmState.changed(first, read()));
        PhoneAlarmState.Snapshot armed = PhoneAlarmState.snapshot(context);
        assertTrue(PhoneAlarmState.beginCommand(context, armed.revision, AlarmAction.DISARM, UUID.randomUUID().toString()));
        assertTrue("Going pending is a change", PhoneAlarmState.changed(armed, PhoneAlarmState.snapshot(context)));
    }
    @Test public void rebootAndOverdueCallbackRequireAnotherQuery() {
        bind(); read();
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 4);
        assertFalse(PhoneAlarmState.snapshot(context).availability == AlarmStateProtocol.Availability.READY);
        PhoneAlarmState.queryOperation = (app, deadline) -> { advance(8_001); return answer; };
        assertEquals(AlarmStateProtocol.Availability.OFFLINE, read().availability);
    }
    private void bind() { assertTrue(AdtPortalSession.bind(context, "system-1", "partition-1")); }
    private PhoneAlarmState.Snapshot read() { advance(1); return PhoneAlarmState.refresh(context, 0); }
    private static void advance(long ms) { ShadowSystemClock.advanceBy(Duration.ofMillis(ms)); }
    private static AdtPortalClient.Result result(AlarmStateProtocol.State state) {
        return new AdtPortalClient.Result(AdtPortalClient.Status.READY, state, "system-1", "partition-1", "Fixture", "Panel", 1);
    }
    private static AdtPortalClient.Result failure(AdtPortalClient.Status status) {
        return new AdtPortalClient.Result(status, AlarmStateProtocol.State.UNKNOWN, "", "", "", "", 1);
    }
}
