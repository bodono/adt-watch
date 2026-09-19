package dev.personal.adtprobe;

import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Looper;
import android.os.Process;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.RadioButton;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
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
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Explicit local confirmation with inert notifications and widgets; no native alarm action exists. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {RoutineAccessTest.HostShadow.class, PhoneAlarmStateTest.PermissionShadow.class})
@LooperMode(LooperMode.Mode.PAUSED)
public final class StateRecoveryActivityTest {
    private Context context;
    private ActivityController<StateRecoveryActivity> controller;
    private StateRecoveryActivity activity;
    private TimeZone oldZone;
    private String request;
    private StatusBarNotification old;

    @Before public void prepare() {
        context = RuntimeEnvironment.getApplication();
        oldZone = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        ArmExperimentService.cancelForNavigation(context);
        RoutineAccessTest.installValidConfiguration(context);
        for (String name : new String[]{"connected", "reconciled", "connectionHasLatest", "storageFailed"})
            ReflectionHelpers.setStaticField(PhoneAlarmState.class, name, false);
        stored().edit().clear().commit();
        PhoneAlarmStateTest.PermissionShadow.granted = true;
        old = notification("Armed Stay", System.currentTimeMillis() - 120_000);
        PhoneAlarmState.listenerConnecting(context); PhoneAlarmState.reconcile(context, new StatusBarNotification[]{old});
        request = UUID.randomUUID().toString();
        assertTrue(PhoneAlarmState.beginCommand(context, PhoneAlarmState.snapshot(context).revision, AlarmAction.DISARM, request));
        stored().edit().putLong("pending_after_ms", System.currentTimeMillis() - 60_000).commit();
        Shadows.shadowOf(context.getSystemService(KeyguardManager.class)).setKeyguardLocked(false);
        Shadows.shadowOf(context.getSystemService(KeyguardManager.class)).setIsDeviceLocked(false);
        Intent intent = new Intent(context, StateRecoveryActivity.class)
            .putExtra("state", "DISARMED").putExtra("confirm", true);
        controller = Robolectric.buildActivity(StateRecoveryActivity.class, intent).setup();
        activity = controller.get();
        visible(activity.getWindow().getDecorView());
        controller.windowFocusChanged(true);
        assertTrue(RoutineAccess.visibleUnlocked(activity));
        assertTrue(activity.hasWindowFocus());
    }

    @After public void close() {
        if (controller != null) controller.pause().stop().destroy();
        ArmExperimentService.cancelForNavigation(context);
        RoutineAccess.disable(context);
        TimeZone.setDefault(oldZone);
    }

    @Test public void openingChoosingAndCancelingNeverRecordStatus() {
        Map<String, ?> before = new HashMap<>(stored().getAll());
        assertFalse(button("Record checked status…").isEnabled());
        button("Record checked status…").performClick();
        assertEquals(before, stored().getAll());
        AlertDialog dialog = chooseAndReview();
        assertEquals(before, stored().getAll());
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle();
        assertEquals(before, stored().getAll());
        assertFalse(ArmExperimentService.isRunning());
    }

    @Test public void explicitConfirmationRecordsDistinctShortLivedObservationAndSettlesRequest() {
        AlertDialog dialog = chooseAndReview();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle();
        PhoneAlarmState.Snapshot checked = PhoneAlarmState.snapshot(context);
        assertEquals(AlarmStateProtocol.Availability.READY, checked.availability);
        assertEquals(AlarmStateProtocol.State.DISARMED, checked.state);
        assertEquals(AlarmStateProtocol.Evidence.PHONE_CHECK, checked.evidence);
        assertEquals(request, checked.completedRequest);
        assertEquals(0, stored().getLong("pending_after_ms", -1));
        assertTrue(PhoneAlarmState.setupStatus(context).startsWith("You checked Disarmed"));
        assertFalse(ArmExperimentService.isRunning());
        PhoneAlarmState.listenerDisconnected(context);
        PhoneAlarmState.listenerConnecting(context);
        PhoneAlarmState.reconcile(context, new StatusBarNotification[]{old});
        assertEquals("A restart must not reinterpret a checked observation as an active notification",
            AlarmStateProtocol.Availability.NO_STATE, PhoneAlarmState.snapshot(context).availability);
        assertEquals(AlarmStateProtocol.Evidence.PHONE_CHECK, PhoneAlarmState.snapshot(context).evidence);
    }

    @Test public void lockingPhoneBeforeConfirmationPreservesPendingAndOldStatus() {
        AlertDialog dialog = chooseAndReview();
        Map<String, ?> before = new HashMap<>(stored().getAll());
        Shadows.shadowOf(context.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
        assertFalse(PhoneAlarmState.recordPhoneCheck(activity, AlarmStateProtocol.State.DISARMED, PhoneAlarmState.snapshot(context).revision));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle();
        assertEquals(before, stored().getAll());
    }

    @Test public void backgroundedOrHiddenPhoneCannotSubmitTheOldConfirmation() {
        AlertDialog dialog = chooseAndReview();
        Map<String, ?> before = new HashMap<>(stored().getAll());
        controller.pause();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle();
        assertEquals(before, stored().getAll());
        View decor = activity.getWindow().getDecorView();
        ReflectionHelpers.setField(ReflectionHelpers.getField(decor, "mAttachInfo"), "mWindowVisibility", View.GONE);
        assertFalse(PhoneAlarmState.recordPhoneCheck(activity, AlarmStateProtocol.State.DISARMED, PhoneAlarmState.snapshot(context).revision));
        assertEquals(before, stored().getAll());
        controller.resume();
    }

    @Test public void revokedSetupListenerLossAndInvalidClockCannotRecordStatus() {
        Map<String, ?> before = new HashMap<>(stored().getAll());
        RoutineAccess.disable(context);
        assertFalse(PhoneAlarmState.recordPhoneCheck(activity, AlarmStateProtocol.State.DISARMED, PhoneAlarmState.snapshot(context).revision));
        assertEquals(before, stored().getAll());
        RoutineAccessTest.writeEnabledAccess(context, 3);
        PhoneAlarmState.listenerDisconnected(context);
        assertFalse(PhoneAlarmState.recordPhoneCheck(activity, AlarmStateProtocol.State.DISARMED, PhoneAlarmState.snapshot(context).revision));
        PhoneAlarmState.listenerConnecting(context); PhoneAlarmState.reconcile(context, new StatusBarNotification[]{old});
        stored().edit().putLong("pending_after_ms", System.currentTimeMillis() + 60_000).commit();
        before = new HashMap<>(stored().getAll());
        assertFalse(PhoneAlarmState.recordPhoneCheck(activity, AlarmStateProtocol.State.DISARMED, PhoneAlarmState.snapshot(context).revision));
        assertEquals(before, stored().getAll());
    }

    @Test public void activeNativeServiceBlocksConfirmationWithoutCancelingOrClearingPending() {
        AlertDialog dialog = chooseAndReview();
        Map<String, ?> before = new HashMap<>(stored().getAll());
        ServiceController<ArmExperimentService> service = Robolectric.buildService(ArmExperimentService.class).create();
        try {
            assertTrue(ArmExperimentService.isRunning());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle();
            assertEquals(before, stored().getAll());
            assertTrue("Recovery must not claim to cancel an active native request", ArmExperimentService.isRunning());
        } finally { service.destroy(); }
    }

    @Test public void newerAdtReportDuringReviewCannotBeOverwrittenByOldCheckedChoice() {
        AlertDialog dialog = chooseAndReview();
        String reviewedRevision = PhoneAlarmState.snapshot(context).revision;
        PhoneAlarmState.posted(context, notification("Armed Away", System.currentTimeMillis() - 1_000));
        assertNotEquals(reviewedRevision, PhoneAlarmState.snapshot(context).revision);
        Map<String, ?> afterAdt = new HashMap<>(stored().getAll());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle();
        assertEquals(afterAdt, stored().getAll());
        assertEquals(AlarmStateProtocol.State.ARMED_AWAY, PhoneAlarmState.snapshot(context).state);
        assertEquals(AlarmStateProtocol.Evidence.ADT_NOTIFICATION, PhoneAlarmState.snapshot(context).evidence);
        android.widget.TextView status = ReflectionHelpers.getField(activity, "status");
        assertTrue(status.getText().toString().contains("status changed"));
        assertFalse(ArmExperimentService.isRunning());
    }

    private AlertDialog chooseAndReview() {
        RadioButton disarmed = find(activity.getWindow().getDecorView(), RadioButton.class, "Disarmed");
        assertNotNull(disarmed); disarmed.performClick();
        assertTrue(button("Record checked status…").isEnabled());
        button("Record checked status…").performClick();
        // Dialog.show queues its window attachment in the paused-looper fixture.
        idle();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog); assertTrue(dialog.isShowing());
        View decor = dialog.getWindow().getDecorView(); visible(decor);
        ReflectionHelpers.setField(ReflectionHelpers.getField(decor, "mAttachInfo"), "mHasWindowFocus", true);
        assertTrue(decor.hasWindowFocus());
        return dialog;
    }
    private static void visible(View decor) {
        Object attach = ReflectionHelpers.getField(decor, "mAttachInfo"); assertNotNull(attach);
        ReflectionHelpers.setField(attach, "mWindowVisibility", View.VISIBLE);
    }
    private Button button(String label) {
        Button found = find(activity.getWindow().getDecorView(), Button.class, label); assertNotNull(found); return found;
    }
    private static <T extends Button> T find(View view, Class<T> type, String text) {
        if (type.isInstance(view) && text.contentEquals(((Button) view).getText())) return type.cast(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            T found = find(((ViewGroup) view).getChildAt(i), type, text); if (found != null) return found;
        }
        return null;
    }
    private SharedPreferences stored() { return context.getSharedPreferences(PhoneAlarmState.PREFERENCES, Context.MODE_PRIVATE); }
    private void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private StatusBarNotification notification(String state, long millis) {
        String date = DateTimeFormatter.ofPattern("HH:mm 'on' dd/MM/uuuu", Locale.UK).withZone(ZoneId.of("UTC"))
            .format(Instant.ofEpochMilli(millis));
        Notification notification = new Notification.Builder(context, "inert").setWhen(millis)
            .setContentTitle("SYSTEM " + state + " (123456)")
            .setContentText("Inert Home: SYSTEM was " + state + " at " + date + ". (123456)").build();
        return new StatusBarNotification(PhoneAlarmState.ADT_PACKAGE, PhoneAlarmState.ADT_PACKAGE, 7, "inert", Process.myUid(), 0, 0,
            notification, Process.myUserHandle(), millis + 1000);
    }
}
