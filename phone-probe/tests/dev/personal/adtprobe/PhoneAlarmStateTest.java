package dev.personal.adtprobe;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Process;
import android.service.notification.StatusBarNotification;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Inert notification fixtures only: no ADT code, network, watch messages or alarm actions. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = PhoneAlarmStateTest.PermissionShadow.class)
public final class PhoneAlarmStateTest {
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final long EVENT = Instant.parse("2026-09-15T08:39:27Z").toEpochMilli();
    private Context context;
    private TimeZone oldZone;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        oldZone = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        for (String name : new String[]{"connected", "reconciled", "connectionHasLatest", "storageFailed"})
            ReflectionHelpers.setStaticField(PhoneAlarmState.class, name, false);
        context.getSharedPreferences(PhoneAlarmState.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit();
        PermissionShadow.granted = true;
        PackageInfo info = new PackageInfo(); info.packageName = PhoneAlarmState.ADT_PACKAGE; info.versionCode = 2307;
        info.applicationInfo = new ApplicationInfo(); info.applicationInfo.packageName = info.packageName; info.applicationInfo.enabled = true;
        Shadows.shadowOf(context.getPackageManager()).installPackage(info);
    }

    @After public void restoreZone() { TimeZone.setDefault(oldZone); }

    @Test public void exactPanelEventsParseAndScopeIsOnlyAHash() {
        PhoneAlarmState.Event event = parse("Armed Stay", "Inert Home", "123456", EVENT);
        assertNotNull(event); assertEquals(AlarmStateProtocol.State.ARMED_STAY, event.state);
        assertEquals(EVENT, event.millis); assertTrue(event.scope.matches("[0-9a-f]{64}"));
        assertFalse(event.scope.contains("123456"));
        assertEquals(event.scope, parse("Disarmed", "Inert Home", "123456", EVENT + 1_000).scope);
        assertNotEquals(event.scope, parse("Armed Stay", "Other Home", "123456", EVENT).scope);
        assertEquals(AlarmStateProtocol.State.ARMED_AWAY, parse("Armed Away", "Inert Home", "123456", EVENT).state);
    }

    @Test public void mismatchedScopeStateTimestampAndLooseCommandTextAreRejected() {
        String body = body("Armed Stay", "Inert Home", "123456", EVENT);
        assertNull(PhoneAlarmState.parseText("SYSTEM Disarmed (123456)", body, EVENT, EVENT, UTC));
        assertNull(PhoneAlarmState.parseText("OTHER Armed Stay (123456)", body, EVENT, EVENT, UTC));
        assertNull(PhoneAlarmState.parseText("SYSTEM Armed Stay (999999)", body, EVENT, EVENT, UTC));
        assertNull(PhoneAlarmState.parseText(title("Armed Stay"), body, EVENT + 60_000, EVENT + 60_000, UTC));
        assertNull(PhoneAlarmState.parseText(title("Armed Stay"), body.replace("15/09/2026", "31/09/2026"), EVENT, EVENT, UTC));
        assertNull(PhoneAlarmState.parseText(title("Armed Stay"), "Scene WATCH ARM STAY requested", EVENT, EVENT, UTC));
        assertNull(PhoneAlarmState.parseText(title("Armed Stay"), body, EVENT, EVENT - 60_001, UTC));
    }

    @Test public void notificationPackageSummaryAndInconsistentExpandedTextAreIgnored() {
        StatusBarNotification event = notification("Armed Stay", EVENT);
        assertNotNull(PhoneAlarmState.parse(event, EVENT));
        event.getNotification().flags |= Notification.FLAG_GROUP_SUMMARY;
        assertNull(PhoneAlarmState.parse(event, EVENT));
        event.getNotification().flags = 0;
        event.getNotification().extras.putCharSequence(Notification.EXTRA_BIG_TEXT, "Some other text");
        assertNull(PhoneAlarmState.parse(event, EVENT));
        StatusBarNotification other = new StatusBarNotification("other.app", "other.app", 7, "inert", Process.myUid(), 0, 0,
            notification("Armed Stay", EVENT).getNotification(), Process.myUserHandle(), EVENT);
        assertNull(PhoneAlarmState.parse(other, EVENT));
    }

    @Test public void olderAndDuplicateEventsCannotRegressStateOrChangeRevision() {
        PhoneAlarmState.Ledger ledger = new PhoneAlarmState.Ledger();
        ledger.accept(parse("Disarmed", "Inert Home", "123456", EVENT));
        String revision = ledger.revision;
        assertFalse(ledger.accept(parse("Armed Stay", "Inert Home", "123456", EVENT - 1_000)));
        assertFalse(ledger.accept(parse("Disarmed", "Inert Home", "123456", EVENT)));
        assertEquals(revision, ledger.revision); assertEquals(AlarmStateProtocol.State.DISARMED, ledger.state);
    }

    @Test public void sameTimeContradictionRemainsUnknownUntilStrictlyNewerEvent() {
        PhoneAlarmState.Ledger ledger = new PhoneAlarmState.Ledger();
        ledger.accept(parse("Disarmed", "Inert Home", "123456", EVENT));
        ledger.accept(parse("Armed Stay", "Inert Home", "123456", EVENT));
        assertEquals(AlarmStateProtocol.Availability.NO_STATE, ledger.availability(EVENT, true));
        ledger.accept(parse("Disarmed", "Inert Home", "123456", EVENT));
        assertEquals(AlarmStateProtocol.Availability.NO_STATE, ledger.availability(EVENT, true));
        ledger.accept(parse("Disarmed", "Inert Home", "123456", EVENT + 1_000));
        assertEquals(AlarmStateProtocol.Availability.READY, ledger.availability(EVENT + 1_000, true));
    }

    @Test public void multipleScopesCannotSilentlyChooseAnotherHouse() {
        PhoneAlarmState.Ledger ledger = new PhoneAlarmState.Ledger();
        ledger.accept(parse("Disarmed", "Inert Home", "123456", EVENT));
        ledger.accept(parse("Armed Stay", "Other Home", "123456", EVENT + 1_000));
        ledger.accept(parse("Disarmed", "Inert Home", "123456", EVENT + 2_000));
        assertEquals(AlarmStateProtocol.Availability.NO_STATE, ledger.availability(EVENT + 2_000, true));
    }

    @Test public void pendingConsumesRevisionAndOnlyPostCommandSourceEventClearsIt() {
        PhoneAlarmState.Ledger ledger = new PhoneAlarmState.Ledger();
        ledger.accept(parse("Disarmed", "Inert Home", "123456", EVENT));
        String revision = ledger.revision;
        ledger.begin(EVENT + 5_000);
        assertNotEquals(revision, ledger.revision);
        ledger.accept(parse("Armed Stay", "Inert Home", "123456", EVENT + 1_000));
        assertEquals(AlarmStateProtocol.Availability.BUSY, ledger.availability(EVENT + 6_000, true));
        ledger.accept(parse("Armed Stay", "Inert Home", "123456", EVENT + 5_000));
        assertEquals(AlarmStateProtocol.Availability.BUSY, ledger.availability(EVENT + 6_000, true));
        ledger.accept(parse("Armed Stay", "Inert Home", "123456", EVENT + 6_000));
        assertEquals(AlarmStateProtocol.Availability.READY, ledger.availability(EVENT + 6_000, true));
    }

    @Test public void newerSameStateConfirmsExactRequestWithoutInventingATransition() {
        PhoneAlarmState.Ledger ledger = new PhoneAlarmState.Ledger();
        String request = UUID.randomUUID().toString();
        ledger.accept(parse("Disarmed", "Inert Home", "123456", EVENT));
        ledger.begin(EVENT + 5_000, request);
        assertEquals(request, ledger.pendingRequest);
        assertEquals("-", ledger.completedRequest);
        ledger.accept(parse("Disarmed", "Inert Home", "123456", EVENT + 6_000));
        assertEquals(AlarmStateProtocol.State.DISARMED, ledger.state);
        assertEquals(AlarmStateProtocol.Availability.READY, ledger.availability(EVENT + 6_000, true));
        assertEquals(0, ledger.pendingAfter);
        assertEquals("-", ledger.pendingRequest);
        assertEquals(request, ledger.completedRequest);
        String next = UUID.randomUUID().toString();
        ledger.begin(EVENT + 7_000, next);
        assertEquals(next, ledger.pendingRequest);
        assertEquals("-", ledger.completedRequest);
    }

    @Test public void duplicateOlderEqualAndConflictingEventsCannotConfirmPendingRequest() {
        PhoneAlarmState.Ledger ledger = new PhoneAlarmState.Ledger();
        String request = UUID.randomUUID().toString();
        ledger.accept(parse("Armed Stay", "Inert Home", "123456", EVENT));
        ledger.begin(EVENT + 5_000, request);
        ledger.accept(parse("Armed Stay", "Inert Home", "123456", EVENT));
        ledger.accept(parse("Disarmed", "Inert Home", "123456", EVENT - 1_000));
        ledger.accept(parse("Disarmed", "Inert Home", "123456", EVENT + 5_000));
        ledger.accept(parse("Armed Stay", "Inert Home", "123456", EVENT + 5_000));
        assertEquals(EVENT + 5_000, ledger.pendingAfter);
        assertEquals(request, ledger.pendingRequest);
        assertEquals("-", ledger.completedRequest);
        assertEquals(AlarmStateProtocol.Availability.NO_STATE, ledger.availability(EVENT + 6_000, true));
    }

    @Test public void confirmationDeadlineChangesMessageButNeverReleasesPendingRequest() {
        PhoneAlarmState.Ledger ledger = new PhoneAlarmState.Ledger();
        String request = UUID.randomUUID().toString();
        ledger.accept(parse("Armed Stay", "Inert Home", "123456", EVENT));
        ledger.begin(EVENT + 5_000, request);
        assertEquals(AlarmStateProtocol.Availability.BUSY,
            ledger.availability(EVENT + 5_000 + PhoneAlarmState.COMMAND_CONFIRMATION_MILLIS - 1, true));
        assertEquals(AlarmStateProtocol.Availability.UNCONFIRMED,
            ledger.availability(EVENT + 5_000 + PhoneAlarmState.COMMAND_CONFIRMATION_MILLIS, true));
        assertEquals(AlarmStateProtocol.Availability.UNCONFIRMED,
            ledger.availability(EVENT + 12 * 60 * 60 * 1000L, true));
        assertEquals(EVENT + 5_000, ledger.pendingAfter);
        assertEquals(request, ledger.pendingRequest);
        assertEquals("-", ledger.completedRequest);
        assertEquals(AlarmStateProtocol.Availability.UNCONFIRMED, ledger.availability(EVENT, true));
    }

    @Test public void invalidRequestCannotConsumeReadyRevisionOrPersistPending() {
        PhoneAlarmState.listenerConnecting(context);
        PhoneAlarmState.reconcile(context, new StatusBarNotification[]{notification("Disarmed", System.currentTimeMillis() - 120_000)});
        String revision = PhoneAlarmState.snapshot(context).revision;
        for (String request : new String[]{null, "-", "not-a-request", "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"}) {
            assertFalse(PhoneAlarmState.beginCommand(context, revision, AlarmAction.ARM_STAY, request));
            assertEquals(revision, PhoneAlarmState.snapshot(context).revision);
            assertEquals(AlarmStateProtocol.Availability.READY, PhoneAlarmState.snapshot(context).availability);
        }
        assertEquals(0, context.getSharedPreferences(PhoneAlarmState.PREFERENCES, Context.MODE_PRIVATE).getLong("pending_after_ms", 0));
    }

    @Test public void unconfirmedPendingSurvivesRestartAndNewerSameStateCompletionPersists() {
        StatusBarNotification old = notification("Armed Stay", System.currentTimeMillis() - 120_000);
        PhoneAlarmState.listenerConnecting(context);
        PhoneAlarmState.reconcile(context, new StatusBarNotification[]{old});
        String revision = PhoneAlarmState.snapshot(context).revision;
        String request = UUID.randomUUID().toString();
        assertTrue(PhoneAlarmState.beginCommand(context, revision, AlarmAction.DISARM, request));
        // The ledger uses wall-clock notification times; Robolectric's elapsed-clock advance
        // does not advance System.currentTimeMillis(). Load an aged persisted request instead.
        context.getSharedPreferences(PhoneAlarmState.PREFERENCES, Context.MODE_PRIVATE).edit()
            .putLong("pending_after_ms", System.currentTimeMillis() - PhoneAlarmState.COMMAND_CONFIRMATION_MILLIS - 1).commit();
        restartListener(old);
        assertEquals(AlarmStateProtocol.Availability.UNCONFIRMED, PhoneAlarmState.snapshot(context).availability);
        assertTrue(PhoneAlarmState.setupStatus(context).contains("Result not confirmed"));
        assertFalse(PhoneAlarmState.beginCommand(context, PhoneAlarmState.snapshot(context).revision, AlarmAction.DISARM,
            UUID.randomUUID().toString()));
        assertEquals(request, context.getSharedPreferences(PhoneAlarmState.PREFERENCES, Context.MODE_PRIVATE).getString("pending_request", "-"));
        StatusBarNotification newer = notification("Armed Stay", System.currentTimeMillis());
        PhoneAlarmState.posted(context, newer);
        assertEquals(AlarmStateProtocol.Availability.READY, PhoneAlarmState.snapshot(context).availability);
        assertEquals(request, PhoneAlarmState.snapshot(context).completedRequest);
        restartListener(newer);
        assertEquals(AlarmStateProtocol.Availability.READY, PhoneAlarmState.snapshot(context).availability);
        assertEquals(request, PhoneAlarmState.snapshot(context).completedRequest);
        assertEquals(0, context.getSharedPreferences(PhoneAlarmState.PREFERENCES, Context.MODE_PRIVATE).getLong("pending_after_ms", -1));
    }

    @Test public void legacyPendingRemainsUnconfirmedUntilNewReportWithoutInventingRequestId() {
        PhoneAlarmState.listenerConnecting(context);
        StatusBarNotification old = notification("Armed Stay", System.currentTimeMillis() - 120_000);
        PhoneAlarmState.reconcile(context, new StatusBarNotification[]{old});
        context.getSharedPreferences(PhoneAlarmState.PREFERENCES, Context.MODE_PRIVATE).edit()
            .putLong("pending_after_ms", System.currentTimeMillis() - 60_000)
            .remove("pending_request").remove("completed_request").commit();
        restartListener(old);
        assertEquals(AlarmStateProtocol.Availability.UNCONFIRMED, PhoneAlarmState.snapshot(context).availability);
        assertEquals("-", PhoneAlarmState.snapshot(context).completedRequest);
        PhoneAlarmState.posted(context, notification("Disarmed", System.currentTimeMillis()));
        assertEquals(AlarmStateProtocol.Availability.READY, PhoneAlarmState.snapshot(context).availability);
        assertEquals("-", PhoneAlarmState.snapshot(context).completedRequest);
    }

    @Test public void sameTimeConflictAfterRestartRevokesCompletionAndRestoresOriginalPending() {
        PhoneAlarmState.listenerConnecting(context);
        PhoneAlarmState.reconcile(context, new StatusBarNotification[]{notification("Armed Stay", System.currentTimeMillis() - 120_000)});
        String request = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        long started = now - 2_000;
        assertTrue(PhoneAlarmState.beginCommand(context, PhoneAlarmState.snapshot(context).revision, AlarmAction.DISARM, request));
        // Backdate only this persisted fixture so all notifications have real past wall times.
        context.getSharedPreferences(PhoneAlarmState.PREFERENCES, Context.MODE_PRIVATE).edit()
            .putLong("pending_after_ms", started).commit();
        long confirmed = now - 1_000;
        StatusBarNotification newer = notification("Disarmed", confirmed);
        PhoneAlarmState.posted(context, newer);
        assertEquals(request, PhoneAlarmState.snapshot(context).completedRequest);
        restartListener(newer);
        PhoneAlarmState.posted(context, notification("Armed Stay", confirmed));
        assertEquals(AlarmStateProtocol.Availability.NO_STATE, PhoneAlarmState.snapshot(context).availability);
        assertEquals("-", PhoneAlarmState.snapshot(context).completedRequest);
        assertEquals(started, context.getSharedPreferences(PhoneAlarmState.PREFERENCES, Context.MODE_PRIVATE).getLong("pending_after_ms", 0));
        assertEquals(request, context.getSharedPreferences(PhoneAlarmState.PREFERENCES, Context.MODE_PRIVATE).getString("pending_request", "-"));
        PhoneAlarmState.posted(context, notification("Disarmed", now));
        assertEquals(AlarmStateProtocol.Availability.READY, PhoneAlarmState.snapshot(context).availability);
        assertEquals(request, PhoneAlarmState.snapshot(context).completedRequest);
    }

    @Test public void phoneCheckHasDistinctEvidenceCompletesPendingAndExpiresAfterFiveMinutes() {
        PhoneAlarmState.Ledger ledger = new PhoneAlarmState.Ledger();
        ledger.accept(parse("Armed Stay", "Inert Home", "123456", EVENT));
        String request = UUID.randomUUID().toString();
        ledger.begin(EVENT + 1_000, request);
        assertTrue(ledger.recordPhoneCheck(AlarmStateProtocol.State.ARMED_STAY, EVENT + 2_000));
        assertEquals(AlarmStateProtocol.Evidence.PHONE_CHECK, ledger.evidence);
        assertEquals(request, ledger.completedRequest);
        assertEquals(0, ledger.pendingAfter);
        assertEquals(AlarmStateProtocol.Availability.READY,
            ledger.availability(EVENT + 2_000 + AlarmStateProtocol.PHONE_CHECK_FRESH_MS, true));
        assertEquals(AlarmStateProtocol.Availability.STALE,
            ledger.availability(EVENT + 2_000 + AlarmStateProtocol.PHONE_CHECK_FRESH_MS + 1, true));
        assertEquals(AlarmStateProtocol.Availability.NO_STATE, ledger.availability(EVENT + 2_000, false));
        assertFalse("A manual observation cannot impersonate an active ADT notification",
            ledger.isLatest(parse("Armed Stay", "Inert Home", "123456", EVENT + 2_000)));
    }

    @Test public void phoneCheckRejectsUnknownScopeAmbiguityAndNonAdvancingClock() {
        PhoneAlarmState.Ledger ledger = new PhoneAlarmState.Ledger();
        assertFalse(ledger.recordPhoneCheck(AlarmStateProtocol.State.DISARMED, EVENT));
        ledger.accept(parse("Armed Stay", "Inert Home", "123456", EVENT));
        assertFalse(ledger.recordPhoneCheck(null, EVENT + 1_000));
        assertFalse(ledger.recordPhoneCheck(AlarmStateProtocol.State.UNKNOWN, EVENT + 1_000));
        assertFalse(ledger.recordPhoneCheck(AlarmStateProtocol.State.DISARMED, EVENT));
        assertFalse(ledger.recordPhoneCheck(AlarmStateProtocol.State.DISARMED, EVENT - 1));
        ledger.begin(EVENT + 1_000, UUID.randomUUID().toString());
        assertFalse(ledger.recordPhoneCheck(AlarmStateProtocol.State.DISARMED, EVENT + 1_000));
        ledger.accept(parse("Disarmed", "Inert Home", "123456", EVENT));
        assertFalse(ledger.recordPhoneCheck(AlarmStateProtocol.State.DISARMED, EVENT + 2_000));
        ledger.accept(parse("Armed Stay", "Other Home", "123456", EVENT + 2_000));
        assertFalse(ledger.recordPhoneCheck(AlarmStateProtocol.State.DISARMED, EVENT + 3_000));
    }

    @Test public void newerNotificationReplacesCheckedEvidenceAndOlderNotificationCannotRegressIt() {
        PhoneAlarmState.Ledger ledger = new PhoneAlarmState.Ledger();
        ledger.accept(parse("Armed Stay", "Inert Home", "123456", EVENT));
        assertTrue(ledger.recordPhoneCheck(AlarmStateProtocol.State.DISARMED, EVENT + 2_000));
        assertFalse(ledger.accept(parse("Armed Stay", "Inert Home", "123456", EVENT + 1_000)));
        assertEquals(AlarmStateProtocol.Evidence.PHONE_CHECK, ledger.evidence);
        assertEquals(AlarmStateProtocol.State.DISARMED, ledger.state);
        assertTrue(ledger.accept(parse("Armed Stay", "Inert Home", "123456", EVENT + 3_000)));
        assertEquals(AlarmStateProtocol.Evidence.ADT_NOTIFICATION, ledger.evidence);
        assertTrue(ledger.isLatest(parse("Armed Stay", "Inert Home", "123456", EVENT + 3_000)));
        assertEquals(AlarmStateProtocol.Availability.READY,
            ledger.availability(EVENT + 3_000 + AlarmStateProtocol.PHONE_CHECK_FRESH_MS + 1, true));
    }

    private void restartListener(StatusBarNotification event) {
        for (String name : new String[]{"connected", "reconciled", "connectionHasLatest", "storageFailed"})
            ReflectionHelpers.setStaticField(PhoneAlarmState.class, name, false);
        PhoneAlarmState.listenerConnecting(context);
        PhoneAlarmState.reconcile(context, new StatusBarNotification[]{event});
    }

    @Test public void ttlFutureEventAndUnreconciledGapCannotBecomeReady() {
        PhoneAlarmState.Ledger ledger = new PhoneAlarmState.Ledger();
        ledger.accept(parse("Disarmed", "Inert Home", "123456", EVENT));
        assertEquals(AlarmStateProtocol.Availability.READY, ledger.availability(EVENT + PhoneAlarmState.MAX_AGE_MILLIS, true));
        assertEquals(AlarmStateProtocol.Availability.STALE, ledger.availability(EVENT + PhoneAlarmState.MAX_AGE_MILLIS + 1, true));
        assertEquals(AlarmStateProtocol.Availability.STALE, ledger.availability(EVENT - 1, true));
        assertEquals(AlarmStateProtocol.Availability.NO_STATE, ledger.availability(EVENT, false));
    }

    @Test public void readySnapshotRequiresPermissionConnectionSupportedAppAndExactRevisionAction() throws Exception {
        long now = System.currentTimeMillis(), eventTime = now - 120_000;
        StatusBarNotification event = notification("Disarmed", eventTime);
        PhoneAlarmState.listenerConnecting(context);
        PhoneAlarmState.reconcile(context, new StatusBarNotification[]{event});
        PhoneAlarmState.Snapshot ready = PhoneAlarmState.snapshot(context);
        assertEquals(AlarmStateProtocol.Availability.READY, ready.availability);
        assertTrue(PhoneAlarmState.matches(context, ready.revision, AlarmAction.ARM_STAY));
        assertFalse(PhoneAlarmState.matches(context, ready.revision, AlarmAction.DISARM));
        assertFalse(PhoneAlarmState.matches(context, "wrong", AlarmAction.ARM_STAY));
        PermissionShadow.granted = false;
        assertEquals(AlarmStateProtocol.Availability.NO_ACCESS, PhoneAlarmState.snapshot(context).availability);
        assertFalse(PhoneAlarmState.beginCommand(context, ready.revision, AlarmAction.ARM_STAY));
        PermissionShadow.granted = true;
        PhoneAlarmState.listenerDisconnected(context);
        assertEquals(AlarmStateProtocol.Availability.OFFLINE, PhoneAlarmState.snapshot(context).availability);
        PhoneAlarmState.listenerConnecting(context);
        PhoneAlarmState.reconcile(context, new StatusBarNotification[0]);
        assertEquals(AlarmStateProtocol.Availability.NO_STATE, PhoneAlarmState.snapshot(context).availability);
        PhoneAlarmState.posted(context, event);
        assertEquals(AlarmStateProtocol.Availability.READY, PhoneAlarmState.snapshot(context).availability);
        PackageInfo installed = context.getPackageManager().getPackageInfo(PhoneAlarmState.ADT_PACKAGE, 0);
        installed.versionCode = 2308; Shadows.shadowOf(context.getPackageManager()).installPackage(installed);
        assertEquals(AlarmStateProtocol.Availability.SETUP, PhoneAlarmState.snapshot(context).availability);
    }

    @Test public void pendingAndPrivacySurviveReconnectWithoutRestoringConsumedAction() {
        long eventTime = System.currentTimeMillis() - 120_000;
        StatusBarNotification event = notification("Disarmed", eventTime);
        PhoneAlarmState.listenerConnecting(context);
        PhoneAlarmState.reconcile(context, new StatusBarNotification[]{event});
        String revision = PhoneAlarmState.snapshot(context).revision;
        assertTrue(PhoneAlarmState.beginCommand(context, revision, AlarmAction.ARM_STAY));
        assertFalse(PhoneAlarmState.beginCommand(context, revision, AlarmAction.ARM_STAY));
        assertEquals(AlarmStateProtocol.State.UNKNOWN, PhoneAlarmState.snapshot(context).state);
        PhoneAlarmState.listenerDisconnected(context);
        PhoneAlarmState.listenerConnecting(context);
        PhoneAlarmState.reconcile(context, new StatusBarNotification[]{event});
        assertEquals(AlarmStateProtocol.Availability.BUSY, PhoneAlarmState.snapshot(context).availability);
        String persisted = context.getSharedPreferences(PhoneAlarmState.PREFERENCES, Context.MODE_PRIVATE).getAll().toString();
        assertFalse(persisted.contains("123456")); assertFalse(persisted.contains("Inert Home"));
        assertFalse(persisted.contains("SYSTEM")); assertFalse(persisted.contains("was Disarmed"));
    }

    private static String title(String state) { return "SYSTEM " + state + " (123456)"; }
    private static String body(String state, String home, String account, long millis) {
        DateTimeFormatter date = DateTimeFormatter.ofPattern("HH:mm 'on' dd/MM/uuuu", Locale.UK).withZone(UTC);
        return home + ": SYSTEM was " + state + " at " + date.format(Instant.ofEpochMilli(millis)) + ". (" + account + ")";
    }
    private static PhoneAlarmState.Event parse(String state, String home, String account, long millis) {
        return PhoneAlarmState.parseText("SYSTEM " + state + " (" + account + ")", body(state, home, account, millis), millis, millis, UTC);
    }
    private StatusBarNotification notification(String state, long millis) {
        Notification notification = new Notification.Builder(context, "inert")
            .setContentTitle(title(state)).setContentText(body(state, "Inert Home", "123456", millis)).setWhen(millis).build();
        return new StatusBarNotification(PhoneAlarmState.ADT_PACKAGE, PhoneAlarmState.ADT_PACKAGE, 7, "inert", Process.myUid(), 0, 0,
            notification, Process.myUserHandle(), millis + 1000);
    }

    @Implements(NotificationManager.class)
    public static final class PermissionShadow {
        static boolean granted;
        @Implementation protected boolean isNotificationListenerAccessGranted(ComponentName component) { return granted; }
    }
}
