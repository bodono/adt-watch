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

    @Test public void ownerResetClearsPendingAndConflictLatchesButCannotInventAState() {
        long now = System.currentTimeMillis();
        PhoneAlarmState.listenerConnecting(context);
        PhoneAlarmState.reconcile(context, new StatusBarNotification[]{notification("Disarmed", now - 120_000)});
        String consumed = PhoneAlarmState.snapshot(context).revision;
        assertTrue(PhoneAlarmState.beginCommand(context, consumed, AlarmAction.ARM_STAY));
        assertEquals(AlarmStateProtocol.Availability.BUSY, PhoneAlarmState.snapshot(context).availability);

        assertTrue(PhoneAlarmState.resetBookkeeping(context));
        PhoneAlarmState.Snapshot cleared = PhoneAlarmState.snapshot(context);
        assertEquals(AlarmStateProtocol.Availability.READY, cleared.availability);
        assertEquals("The last accepted state is kept", AlarmStateProtocol.State.DISARMED, cleared.state);
        assertNotEquals("The consumed revision cannot be replayed", consumed, cleared.revision);
        assertFalse(PhoneAlarmState.matches(context, consumed, AlarmAction.ARM_STAY));
        assertTrue(PhoneAlarmState.matches(context, cleared.revision, AlarmAction.ARM_STAY));

        PhoneAlarmState.posted(context, notification("Armed Stay", "Other Home", now - 60_000));
        assertEquals("A second scope locks the state", AlarmStateProtocol.Availability.NO_STATE, PhoneAlarmState.snapshot(context).availability);
        assertTrue(PhoneAlarmState.resetBookkeeping(context));
        assertEquals(AlarmStateProtocol.Availability.READY, PhoneAlarmState.snapshot(context).availability);
        assertEquals(AlarmStateProtocol.State.DISARMED, PhoneAlarmState.snapshot(context).state);

        context.getSharedPreferences(PhoneAlarmState.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit();
        assertTrue(PhoneAlarmState.resetBookkeeping(context));
        assertEquals("A reset cannot manufacture a report", AlarmStateProtocol.Availability.NO_STATE,
            PhoneAlarmState.snapshot(context).availability);
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
    private StatusBarNotification notification(String state, long millis) { return notification(state, "Inert Home", millis); }
    private StatusBarNotification notification(String state, String home, long millis) {
        Notification notification = new Notification.Builder(context, "inert")
            .setContentTitle(title(state)).setContentText(body(state, home, "123456", millis)).setWhen(millis).build();
        return new StatusBarNotification(PhoneAlarmState.ADT_PACKAGE, PhoneAlarmState.ADT_PACKAGE, 7, "inert", Process.myUid(), 0, 0,
            notification, Process.myUserHandle(), millis + 1000);
    }

    @Implements(NotificationManager.class)
    public static final class PermissionShadow {
        static boolean granted;
        @Implementation protected boolean isNotificationListenerAccessGranted(ComponentName component) { return granted; }
    }
}
