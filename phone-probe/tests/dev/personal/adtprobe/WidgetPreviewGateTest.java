package dev.personal.adtprobe;

import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetHostView;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Actual Android view dispatch with a test-only local broadcast, never an ADT PendingIntent. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public final class WidgetPreviewGateTest {
    private static final String ACTION = "dev.personal.adtprobe.TEST_ONLY_INERT_WIDGET_CLICK";
    private ActivityController<WidgetSetupActivity> controller;
    private WidgetSetupActivity activity;
    private FrameLayout gate;
    private AppWidgetHostView widget;
    private BroadcastReceiver receiver;
    private int received;

    @Before public void prepare() throws Exception {
        controller = Robolectric.buildActivity(WidgetSetupActivity.class).setup();
        activity = controller.get();
        gate = (FrameLayout) field("preview");
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { received++; }
        };
        activity.registerReceiver(receiver, new IntentFilter(ACTION), Context.RECEIVER_NOT_EXPORTED);
        Constructor<?> constructor = Class.forName(WidgetSetupActivity.class.getName() + "$GuardedHostView")
            .getDeclaredConstructor(WidgetSetupActivity.class, Context.class);
        constructor.setAccessible(true);
        widget = (AppWidgetHostView) constructor.newInstance(activity, activity);
        gate.removeAllViews();
        gate.addView(widget, new FrameLayout.LayoutParams(480, 120));
        set("failed", false);
        set("resumed", true);
        set("previewReady", true);
        Probe.activityVisible = true;
        Shadows.shadowOf(activity.getSystemService(KeyguardManager.class)).setKeyguardLocked(false);
        Shadows.shadowOf(activity.getSystemService(KeyguardManager.class)).setIsDeviceLocked(false);
        update(android.R.layout.simple_list_item_1);
    }

    @After public void close() {
        if (activity != null && receiver != null) activity.unregisterReceiver(receiver);
        if (controller != null) controller.pause().stop().destroy();
        Probe.activityVisible = false;
    }

    @Test public void blockedPreviewRejectsTouchKeysAndCachedAccessibilityActions() throws Exception {
        View target = target();
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, gate.getImportantForAccessibility());
        assertFalse(target.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        tap();
        gate.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
        gate.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, received);

        enableForGateTest();
        tap();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Positive control: a touch reaches the synthetic widget when enabled", 1, received);
        target.setFocusableInTouchMode(true);
        assertTrue(target.requestFocus());
        gate.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
        gate.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Positive control: keyboard activation also reaches the widget", 2, received);
        assertTrue("Positive control: the synthetic widget action really works when enabled",
            target.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(3, received);
    }

    @Test public void replacementRemoteViewsRemainBlockedAfterPreviouslyEnabledPreview() throws Exception {
        enableForGateTest();
        assertTrue(target().performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, received);

        invoke("disableTaps");
        update(android.R.layout.simple_list_item_2); // Different layout forces child replacement.
        assertFalse(target().performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        tap();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("New RemoteViews must not resurrect prior permission to interact", 1, received);
    }

    @Test public void keyguardBlocksImmediatelyWithoutWaitingForPollingCallback() throws Exception {
        enableForGateTest();
        Shadows.shadowOf(activity.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
        // No looper advance: event-time checks must close the 250 ms polling interval.
        assertFalse(target().performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        tap();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, received);
    }

    @Test public void changedBookkeepingRevisionImmediatelyBlocksCachedViewActions() throws Exception {
        enableForGateTest();
        SharedPreferences prefs = (SharedPreferences) field("prefs");
        prefs.edit().putLong("revision", prefs.getLong("revision", 0) + 1).commit();
        assertFalse(target().performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        tap();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, received);
    }

    @Test public void leavingAndReturningNeverRestoresEnabledActions() throws Exception {
        enableForGateTest();
        activity.onPause();
        assertFalse(target().performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        activity.onResume();
        assertFalse(target().performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        assertEquals(Boolean.FALSE, field("tapsEnabled"));
        tap();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, received);
    }

    private void update(int layout) throws Exception {
        PendingIntent click = PendingIntent.getBroadcast(activity, 991,
            new Intent(ACTION).setPackage(activity.getPackageName()),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        RemoteViews views = new RemoteViews("android", layout);
        views.setTextViewText(android.R.id.text1, "Inert local test widget");
        views.setOnClickPendingIntent(android.R.id.text1, click);
        widget.updateAppWidget(views);
        gate.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY));
        gate.layout(0, 0, 480, 120);
        assertNotNull("The framework must inflate the actual RemoteViews", target());
    }

    private View target() { return widget.findViewById(android.R.id.text1); }

    private void enableForGateTest() throws Exception {
        // Set only the gate's approval state. Native provider/configuration is outside these input tests.
        set("tapsEnabled", true);
        Method guard = gate.getClass().getDeclaredMethod("guardTree");
        guard.setAccessible(true); guard.invoke(gate);
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, gate.getImportantForAccessibility());
        assertEquals(Boolean.TRUE, invoke("canInteract"));
    }

    private void tap() {
        long time = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 20, 20, 0);
        MotionEvent up = MotionEvent.obtain(time, time + 10, MotionEvent.ACTION_UP, 20, 20, 0);
        gate.dispatchTouchEvent(down); gate.dispatchTouchEvent(up);
        down.recycle(); up.recycle();
    }

    private Object field(String name) throws Exception {
        Field field = WidgetSetupActivity.class.getDeclaredField(name);
        field.setAccessible(true); return field.get(activity);
    }
    private void set(String name, Object value) throws Exception {
        Field field = WidgetSetupActivity.class.getDeclaredField(name);
        field.setAccessible(true); field.set(activity, value);
    }
    private Object invoke(String name) throws Exception {
        Method method = WidgetSetupActivity.class.getDeclaredMethod(name);
        method.setAccessible(true); return method.invoke(activity);
    }
}
