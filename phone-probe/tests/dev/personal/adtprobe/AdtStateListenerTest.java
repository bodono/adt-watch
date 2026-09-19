package dev.personal.adtprobe;

import android.app.Notification;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Looper;
import android.os.Process;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.TimeZone;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowService;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** The real listener service against a shadowed active-notification read. Inert fixtures only. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {AdtStateListenerTest.ActiveShadow.class, PhoneAlarmStateTest.PermissionShadow.class})
@LooperMode(LooperMode.Mode.PAUSED)
public final class AdtStateListenerTest {
    private Context context;
    private TimeZone oldZone;
    private ServiceController<AdtStateListener> controller;
    private AdtStateListener listener;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        oldZone = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        for (String name : new String[]{"connected", "reconciled", "connectionHasLatest", "storageFailed"})
            ReflectionHelpers.setStaticField(PhoneAlarmState.class, name, false);
        context.getSharedPreferences(PhoneAlarmState.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit();
        PhoneAlarmStateTest.PermissionShadow.granted = true;
        PackageInfo info = new PackageInfo(); info.packageName = PhoneAlarmState.ADT_PACKAGE; info.versionCode = 2307;
        info.applicationInfo = new ApplicationInfo(); info.applicationInfo.packageName = info.packageName; info.applicationInfo.enabled = true;
        Shadows.shadowOf(context.getPackageManager()).installPackage(info);
        ActiveShadow.active = null; ActiveShadow.reads = 0;
        controller = Robolectric.buildService(AdtStateListener.class).create();
        listener = controller.get();
    }

    @After public void close() {
        if (controller != null) controller.destroy();
        TimeZone.setDefault(oldZone);
    }

    @Test public void nullActiveListAtConnectIsRetriedAndANewPostIsNotDropped() {
        listener.onListenerConnected();
        assertTrue(PhoneAlarmState.awaitingReconcile());
        assertEquals(AlarmStateProtocol.Availability.OFFLINE, PhoneAlarmState.snapshot(context).availability);
        assertEquals(1, ActiveShadow.reads);
        advance(1_999);
        assertEquals(1, ActiveShadow.reads);
        advance(1);
        assertEquals("A null read is retried after two seconds", 2, ActiveShadow.reads);
        assertTrue(PhoneAlarmState.awaitingReconcile());

        StatusBarNotification event = notification("Disarmed", System.currentTimeMillis() - 120_000);
        ActiveShadow.active = new StatusBarNotification[]{event};
        listener.onNotificationPosted(event);
        assertFalse("A posted notification reconciles the connection before it is processed", PhoneAlarmState.awaitingReconcile());
        assertEquals(AlarmStateProtocol.Availability.READY, PhoneAlarmState.snapshot(context).availability);
        assertEquals(AlarmStateProtocol.State.DISARMED, PhoneAlarmState.snapshot(context).state);
        int reads = ActiveShadow.reads;
        advance(30_000);
        assertEquals("No retries remain once reconciled", reads, ActiveShadow.reads);
    }

    @Test public void retriesAreBoundedAndAConnectedReadSucceedsAtOnce() {
        listener.onListenerConnected();
        advance(60_000);
        assertEquals("One initial read plus five bounded retries", 6, ActiveShadow.reads);
        assertTrue(PhoneAlarmState.awaitingReconcile());
        listener.onListenerDisconnected();
        assertFalse(PhoneAlarmState.awaitingReconcile());

        ActiveShadow.active = new StatusBarNotification[0];
        listener.onListenerConnected();
        assertFalse("A successful empty read reconciles immediately", PhoneAlarmState.awaitingReconcile());
        assertEquals(AlarmStateProtocol.Availability.NO_STATE, PhoneAlarmState.snapshot(context).availability);
        int reads = ActiveShadow.reads;
        advance(30_000);
        assertEquals(reads, ActiveShadow.reads);
    }

    private static void advance(long millis) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis)); }

    private StatusBarNotification notification(String state, long millis) {
        String date = DateTimeFormatter.ofPattern("HH:mm 'on' dd/MM/uuuu", Locale.UK).withZone(ZoneId.of("UTC"))
            .format(Instant.ofEpochMilli(millis));
        Notification notification = new Notification.Builder(context, "inert").setWhen(millis)
            .setContentTitle("SYSTEM " + state + " (123456)")
            .setContentText("Inert Home: SYSTEM was " + state + " at " + date + ". (123456)").build();
        return new StatusBarNotification(PhoneAlarmState.ADT_PACKAGE, PhoneAlarmState.ADT_PACKAGE, 7, "inert", Process.myUid(), 0, 0,
            notification, Process.myUserHandle(), millis + 1000);
    }

    @Implements(NotificationListenerService.class)
    public static final class ActiveShadow extends ShadowService {
        static StatusBarNotification[] active;
        static int reads;
        @Implementation protected StatusBarNotification[] getActiveNotifications() { reads++; return active; }
    }
}
