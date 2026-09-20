package dev.personal.adtprobe;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
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
    private PhoneAlarmState.Publish oldPublish;
    private PhoneAlarmState.Schedule oldKeepAlive;
    private final List<ScheduledRead> keepAlives = new ArrayList<>();
    private int published;
    private AdtPortalClient.Result answer;
    private int queries;
    private final List<ScheduledRead> scheduled = new ArrayList<>();
    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(PhoneAlarmState.PREFERENCES, 0).edit().clear().commit();
        context.getSharedPreferences("adt_portal_binding", 0).edit().clear().commit();
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 3);
        ReflectionHelpers.setStaticField(PhoneAlarmState.class, "storageFailed", false);
        ReflectionHelpers.setStaticField(PhoneAlarmState.class, "hintRead", null);
        oldQuery = PhoneAlarmState.queryOperation; oldSchedule = PhoneAlarmState.scheduleOperation;
        oldPublish = PhoneAlarmState.publishOperation; PhoneAlarmState.publishOperation = app -> published++;
        oldKeepAlive = PhoneAlarmState.keepAliveOperation;
        PhoneAlarmState.keepAliveOperation = (action, delay) -> keepAlives.add(new ScheduledRead(action, delay));
        PhoneAlarmState.scheduleOperation = (action, delay) -> { };
        PhoneAlarmState.queryOperation = (app, deadline) -> { queries++; return answer; };
        answer = result(AlarmStateProtocol.State.DISARMED);
    }
    @After public void finish() {
        PhoneAlarmState.queryOperation = oldQuery; PhoneAlarmState.scheduleOperation = oldSchedule;
        PhoneAlarmState.publishOperation = oldPublish; PhoneAlarmState.keepAliveOperation = oldKeepAlive;
        ReflectionHelpers.setStaticField(PhoneAlarmState.class, "hintRead", null);
    }
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
    @Test public void loginFailureGatesButATransientReadFailureKeepsAFreshObservation() {
        bind(); PhoneAlarmState.Snapshot first = read();
        assertFalse(first.readFailed);
        answer = failure(AdtPortalClient.Status.LOGIN_REQUIRED); read();
        assertEquals(AlarmStateProtocol.Availability.NO_ACCESS, PhoneAlarmState.snapshot(context).availability);
        assertTrue(PhoneAlarmState.snapshot(context).readFailed);
        assertFalse(PhoneAlarmState.matches(context, first.revision, AlarmAction.ARM_STAY));
        answer = failure(AdtPortalClient.Status.UNAVAILABLE); read();
        PhoneAlarmState.Snapshot afterBlip = PhoneAlarmState.snapshot(context);
        assertEquals("A transient failure keeps the fresh observation usable", AlarmStateProtocol.Availability.READY, afterBlip.availability);
        assertTrue(afterBlip.readFailed);
        assertEquals(first.observationId, afterBlip.observationId);
        assertTrue(PhoneAlarmState.matches(context, first.revision, AlarmAction.ARM_STAY));
        advance(60_000);
        assertEquals("Only until the observation's own freshness runs out", AlarmStateProtocol.Availability.OFFLINE,
            PhoneAlarmState.snapshot(context).availability);
        answer = result(AlarmStateProtocol.State.DISARMED);
        PhoneAlarmState.Snapshot recovered = read();
        assertEquals(AlarmStateProtocol.Availability.READY, recovered.availability);
        assertFalse(recovered.readFailed);
    }
    @Test public void aFailedReadCannotSettleARequestWhoseOutcomeIsStillOpen() {
        bind(); PhoneAlarmState.Snapshot first = read();
        String request = UUID.randomUUID().toString();
        assertTrue(PhoneAlarmState.beginCommand(context, first.revision, AlarmAction.ARM_STAY, request));
        advance(5_000);
        PhoneAlarmState.Snapshot during = read(); // Still Disarmed inside the window: reported as BUSY.
        assertTrue(during.pending); assertEquals(AlarmStateProtocol.Availability.BUSY, during.availability);
        assertNotEquals(first.observationId, during.observationId);
        answer = failure(AdtPortalClient.Status.UNAVAILABLE); advance(10_000);
        assertEquals("A blip inside the window does not keep BUSY", AlarmStateProtocol.Availability.OFFLINE, read().availability);
        advance(15_000); // The 30-second window closes on a read that fails.
        PhoneAlarmState.Snapshot boundary = read();
        assertTrue(boundary.readFailed); assertFalse("The window has closed", boundary.pending);
        assertEquals("The observation from inside the window is not re-reported as the settled result",
            AlarmStateProtocol.Availability.OFFLINE, boundary.availability);
        assertFalse(PhoneAlarmState.matches(context, first.revision, AlarmAction.ARM_STAY));
        answer = result(AlarmStateProtocol.State.ARMED_STAY);
        PhoneAlarmState.Snapshot after = read();
        assertEquals(AlarmStateProtocol.Availability.READY, after.availability);
        assertEquals(AlarmStateProtocol.State.ARMED_STAY, after.state);
        assertEquals("-", after.completedRequest);
        answer = failure(AdtPortalClient.Status.UNAVAILABLE);
        assertEquals("An observation made after the window survives a later blip", AlarmStateProtocol.Availability.READY,
            read().availability);
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
        PhoneAlarmState.Snapshot blip = PhoneAlarmState.refresh(context, 0);
        assertTrue(blip.readFailed); assertFalse(blip.verified);
        assertEquals("The fresh observation outlives a transient failure", AlarmStateProtocol.Availability.READY, blip.availability);
        assertEquals(3, queries);
        answer = result(AlarmStateProtocol.State.DISARMED); advance(1);
        PhoneAlarmState.Snapshot retried = PhoneAlarmState.refresh(context);
        assertFalse("A failed read is not reused; the next caller retries at once", retried.readFailed);
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
        advance(999);
        PhoneAlarmState.refresh(context, PhoneAlarmState.REUSE_MS, requestStarted);
        assertEquals("A post-command read younger than the short confirmation window is reused", 2, queries);
        advance(1);
        PhoneAlarmState.refresh(context, PhoneAlarmState.REUSE_MS, requestStarted);
        assertEquals("A pending read is renewed at one second, before the steady cache expires", 3, queries);
    }
    @Test public void providerBusyUsesTheShortReuseWindowWithoutALocalCommand() {
        bind();
        answer = new AdtPortalClient.Result(AdtPortalClient.Status.BUSY, AlarmStateProtocol.State.DISARMED,
            "system-1", "partition-1", "Fixture", "Panel", 1, 0, 1, true);
        assertTrue(read().providerBusy);
        assertFalse(PhoneAlarmState.snapshot(context).pending);
        advance(999);
        PhoneAlarmState.refresh(context);
        assertEquals(1, queries);
        answer = result(AlarmStateProtocol.State.ARMED_STAY);
        advance(1);
        PhoneAlarmState.Snapshot ready = PhoneAlarmState.refresh(context);
        assertEquals("Provider progress is checked at one second", 2, queries);
        assertEquals(AlarmStateProtocol.Availability.READY, ready.availability);
        assertFalse(ready.providerBusy);
        advance(2_999);
        PhoneAlarmState.refresh(context);
        assertEquals("A settled state retains the ordinary three-second reuse window", 2, queries);
    }
    @Test public void everyConfirmationPollCanSeeACompletedCommandWithoutSkippingAnInterval() {
        bind(); PhoneAlarmState.Snapshot first = read();
        captureSchedule();
        String request = UUID.randomUUID().toString();
        assertTrue(PhoneAlarmState.beginCommand(context, first.revision, AlarmAction.ARM_STAY, request));
        assertEquals(1, scheduled.size());
        runNext(1_000);
        assertEquals(2, queries);
        assertTrue(PhoneAlarmState.snapshot(context).pending);
        assertEquals(1, scheduled.size());
        answer = result(AlarmStateProtocol.State.ARMED_STAY);
        runNext(1_000);
        assertEquals("The next poll does not reuse the still-pending result", 3, queries);
        assertEquals(request, PhoneAlarmState.snapshot(context).completedRequest);
        assertFalse(PhoneAlarmState.snapshot(context).pending);
        assertTrue("Confirmation stops the polling burst", scheduled.isEmpty());
    }
    @Test public void queuedNotificationHintsCoalesceIntoOneImmediateRead() {
        bind(); captureSchedule();
        for (int i = 0; i < 10; i++) PhoneAlarmState.hint(context);
        assertEquals("Only one read is queued before it starts", 1, scheduled.size());
        runNext(0);
        assertEquals(1, queries);
        assertTrue(scheduled.isEmpty());
        assertEquals(AlarmStateProtocol.Availability.READY, PhoneAlarmState.snapshot(context).availability);
    }
    @Test public void notificationsDuringAReadPreserveOneTrailingFreshRead() {
        bind(); captureSchedule();
        PhoneAlarmState.queryOperation = (app, deadline) -> {
            queries++;
            if (queries == 1) {
                // The first backend response was sampled before these newer events arrived.
                for (int i = 0; i < 10; i++) PhoneAlarmState.hint(app);
                return result(AlarmStateProtocol.State.DISARMED);
            }
            return result(AlarmStateProtocol.State.ARMED_STAY);
        };
        PhoneAlarmState.hint(context);
        runNext(0);
        assertEquals(1, queries);
        assertEquals(AlarmStateProtocol.State.DISARMED, PhoneAlarmState.snapshot(context).state);
        assertEquals("Many in-flight hints preserve exactly one trailing read", 1, scheduled.size());
        advance(1);
        runNext(0);
        assertEquals("The trailing hint cannot reuse the earlier notification's response", 2, queries);
        assertEquals(AlarmStateProtocol.State.ARMED_STAY, PhoneAlarmState.snapshot(context).state);
        assertTrue("No timer remains without another hint", scheduled.isEmpty());
    }
    @Test public void failedHintSchedulingDoesNotSuppressLaterNotifications() {
        bind();
        PhoneAlarmState.scheduleOperation = (action, delay) -> { throw new IllegalStateException("inert failure"); };
        PhoneAlarmState.hint(context);
        captureSchedule();
        PhoneAlarmState.hint(context);
        assertEquals(1, scheduled.size());
        runNext(0);
        assertEquals(1, queries);
    }
    @Test public void aRecoveryTaskRunsUnderTheQueryLockAndOnlySuccessIsFollowedByARead() {
        bind(); captureSchedule();
        ReentrantLock lock = ReflectionHelpers.getStaticField(PhoneAlarmState.class, "QUERY_LOCK");
        boolean[] held = {false};
        PhoneAlarmState.scheduleRecovery(context, () -> { held[0] = lock.isHeldByCurrentThread(); return false; });
        assertEquals(1, scheduled.size()); runNext(0);
        assertTrue(held[0]); assertFalse(lock.isLocked());
        assertEquals("A failed recovery reads nothing", 0, queries);
        assertEquals("...and tells the watch nothing", 0, published);
        assertNotEquals(AlarmStateProtocol.Availability.READY, PhoneAlarmState.snapshot(context).availability);
        boolean[] heldDuringRead = {false};
        PhoneAlarmState.queryOperation = (app, deadline) -> { queries++; heldDuringRead[0] = lock.isHeldByCurrentThread(); return answer; };
        PhoneAlarmState.scheduleRecovery(context, () -> true);
        runNext(0);
        assertEquals("A successful recovery is followed by one fresh read", 1, queries);
        assertTrue("...made while the task still holds the lock", heldDuringRead[0]);
        assertEquals(AlarmStateProtocol.Availability.READY, PhoneAlarmState.snapshot(context).availability);
        assertEquals("...and the watch is told", 1, published);
        PhoneAlarmState.scheduleRecovery(context, () -> { throw new IllegalStateException("inert"); });
        runNext(0);
        assertEquals("A throwing task neither reads nor leaks the lock", 1, queries); assertFalse(lock.isLocked());
        assertEquals(1, published);
    }
    @Test public void aRecoveredSessionIsAnnouncedEvenWhenTheFollowUpReadFails() {
        bind(); captureSchedule();
        answer = failure(AdtPortalClient.Status.UNAVAILABLE);
        PhoneAlarmState.scheduleRecovery(context, () -> true);
        runNext(0);
        assertEquals(1, queries);
        assertNotEquals(AlarmStateProtocol.Availability.READY, PhoneAlarmState.snapshot(context).availability);
        assertEquals("The sign-in answer stopped the watch's polling; this hint restarts it", 1, published);
    }
    @Test public void aSuccessfulReadKeepsTheSessionAliveTenMinutesLaterUnlessAnotherReadDidFirst() {
        bind(); read();
        assertEquals(1, keepAlives.size()); assertEquals(PhoneAlarmState.KEEP_ALIVE_MS, keepAlives.get(0).delay);
        ScheduledRead first = keepAlives.remove(0);
        advance(5 * 60_000); read();
        assertEquals("Each successful read moves the keep-alive after itself", 1, keepAlives.size());
        ScheduledRead second = keepAlives.remove(0);
        advance(5 * 60_000); int before = queries;
        first.action.run();
        assertEquals("A superseded keep-alive does nothing", before, queries);
        advance(5 * 60_000);
        boolean[] quiet = {false};
        PhoneAlarmState.queryOperation = (app, deadline) -> { queries++; quiet[0] = PhoneAlarmState.keepAliveRead(); return answer; };
        second.action.run();
        assertEquals("Ten idle minutes after the last read, the phone reads again", before + 1, queries);
        assertTrue("...as a keep-alive read, which never queues a login", quiet[0]);
        assertFalse(PhoneAlarmState.keepAliveRead());
        assertEquals("...and that read keeps the chain going", 1, keepAlives.size());
        assertEquals(AlarmStateProtocol.Availability.READY, PhoneAlarmState.snapshot(context).availability);
        assertEquals("An unchanged state is not worth waking the watch for", 0, published);
    }
    @Test public void theKeepAliveStopsAfterAFailedReadOrRebootUntilAReadSucceedsAgain() {
        bind(); read(); ScheduledRead keepAlive = keepAlives.remove(0);
        answer = failure(AdtPortalClient.Status.LOGIN_REQUIRED); read();
        assertTrue("A failed read schedules no keep-alive", keepAlives.isEmpty());
        answer = result(AlarmStateProtocol.State.DISARMED); int before = queries;
        advance(PhoneAlarmState.KEEP_ALIVE_MS); keepAlive.action.run();
        assertEquals("Only a session known to be alive is kept alive", before, queries);
        assertTrue(keepAlives.isEmpty());
        read(); ScheduledRead next = keepAlives.remove(0);
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 4);
        advance(PhoneAlarmState.KEEP_ALIVE_MS); next.action.run();
        assertEquals("A reboot ends the chain until a read succeeds again", before + 1, queries);
    }
    @Test public void aKeepAliveReadAnnouncesAChangedState() {
        bind(); read(); ScheduledRead keepAlive = keepAlives.remove(0);
        answer = result(AlarmStateProtocol.State.ARMED_STAY);
        advance(PhoneAlarmState.KEEP_ALIVE_MS); keepAlive.action.run();
        assertEquals(AlarmStateProtocol.State.ARMED_STAY, PhoneAlarmState.snapshot(context).state);
        assertEquals("A state that changed while nobody looked reaches the watch as a hint", 1, published);
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
    @Test public void aSignInProblemSaysWhatAutomaticLoginIsDoingAboutIt() {
        bind(); answer = failure(AdtPortalClient.Status.LOGIN_REQUIRED); read();
        assertFalse(PhoneAlarmState.setupStatus(context).contains("Automatic login"));
        String version = "11111111-1111-4111-8111-111111111111";
        AdtSessionRecovery.Credentials old = AdtSessionRecovery.credentials;
        AdtSessionRecovery.credentials = new AdtSessionRecovery.Credentials() {
            @Override public String version(Context ignored) { return version; }
            @Override public AdtCredentialStore.Credentials load(Context ignored) { return null; }
        };
        try {
            context.getSharedPreferences(AdtSessionRecovery.PREFERENCES, 0).edit().putString("version", version)
                .putBoolean("enabled", true).putBoolean("blocked", true).putString("code", "SUBMIT/HTTP/401").commit();
            assertTrue(PhoneAlarmState.setupStatus(context).contains("Automatic login is paused after SUBMIT/HTTP/401"));
        } finally {
            AdtSessionRecovery.credentials = old;
            context.getSharedPreferences(AdtSessionRecovery.PREFERENCES, 0).edit().clear().commit();
        }
    }
    @Test public void rebootAndOverdueCallbackRequireAnotherQuery() {
        bind(); read();
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 4);
        assertFalse(PhoneAlarmState.snapshot(context).availability == AlarmStateProtocol.Availability.READY);
        PhoneAlarmState.queryOperation = (app, deadline) -> { advance(8_001); return answer; };
        assertEquals(AlarmStateProtocol.Availability.OFFLINE, read().availability);
    }
    private void bind() { assertTrue(AdtPortalSession.bind(context, "system-1", "partition-1")); }
    private void captureSchedule() {
        PhoneAlarmState.scheduleOperation = (action, delay) -> scheduled.add(new ScheduledRead(action, delay));
    }
    private void runNext(long expectedDelay) {
        ScheduledRead next = scheduled.remove(0);
        assertEquals(expectedDelay, next.delay);
        advance(expectedDelay);
        next.action.run();
    }
    private static final class ScheduledRead {
        final Runnable action;
        final long delay;
        ScheduledRead(Runnable action, long delay) { this.action = action; this.delay = delay; }
    }
    private PhoneAlarmState.Snapshot read() { advance(1); return PhoneAlarmState.refresh(context, 0); }
    private static void advance(long ms) { ShadowSystemClock.advanceBy(Duration.ofMillis(ms)); }
    private static AdtPortalClient.Result result(AlarmStateProtocol.State state) {
        return new AdtPortalClient.Result(AdtPortalClient.Status.READY, state, "system-1", "partition-1", "Fixture", "Panel", 1);
    }
    private static AdtPortalClient.Result failure(AdtPortalClient.Status status) {
        return new AdtPortalClient.Result(status, AlarmStateProtocol.State.UNKNOWN, "", "", "", "", 1);
    }
}
