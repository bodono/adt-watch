package dev.personal.adtprobe;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Actual native view layout/input only. Callbacks collect enum values; no transport is present. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w240dp-h240dp-round-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class AlarmToggleViewTest {
    private ActivityController<Activity> controller;
    private Activity activity;
    private AlarmToggleView view;
    private final List<AlarmAction> calls = new ArrayList<>();
    private int refreshes;

    @Before public void prepare() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        mount(activity);
    }

    @After public void close() { controller.pause().stop().destroy(); }

    @Test public void knownAndUnknownStatesHaveStableRoundBoundsAndTextualMeaning() throws IOException {
        Rect target = bounds(view.alarmButton);
        assertTrue("The single alarm target is large", target.width() >= 128);
        assertEquals(target.width(), target.height());
        assertState("Armed", AlarmAction.DISARM, "Tap once to disarm.", AlarmToggleView.ARMED_RED, "Disarm");
        assertEquals(target, bounds(view.alarmButton));
        export("toggle-armed");
        assertState("Disarmed", AlarmAction.ARM_STAY, "Tap once to arm in Stay mode.",
            AlarmToggleView.DISARMED_GREEN, "Arm Stay");
        assertEquals(target, bounds(view.alarmButton));
        export("toggle-disarmed");
        assertState("Status unknown", null, "Refresh to check the alarm.", AlarmToggleView.UNKNOWN_GREY, "Unknown");
        assertEquals(target, bounds(view.alarmButton));
        export("toggle-unknown");
        assertState("Status stale", null, "State is out of date. Refresh first.", AlarmToggleView.UNKNOWN_GREY, "Unknown");
        assertEquals(target, bounds(view.alarmButton));
        assertEquals("Rendering alone cannot operate anything", 0, calls.size() + refreshes);
    }

    @Test public void oneTapEmitsItsRenderedActionOnceWithoutAConfirmationStep() {
        view.render("Armed", AlarmAction.DISARM, true, "Tap once to disarm.");
        tap(view.alarmButton);
        assertEquals(Arrays.asList(AlarmAction.DISARM), calls);
        tap(view.alarmButton);
        assertEquals("The rendered permission is consumed before the callback", 1, calls.size());
        view.render("Disarmed", AlarmAction.ARM_STAY, true, "Tap once to arm in Stay mode.");
        tap(view.alarmButton);
        assertEquals(Arrays.asList(AlarmAction.DISARM, AlarmAction.ARM_STAY), calls);
        assertEquals(0, refreshes);
    }

    @Test public void stateChangeDuringAContactCannotRetargetThePendingTap() {
        for (AlarmAction first : AlarmAction.values()) {
            AlarmAction second = first == AlarmAction.DISARM ? AlarmAction.ARM_STAY : AlarmAction.DISARM;
            view.render("Known state", first, true, "Tap the action.");
            layout();
            Rect target = bounds(view.alarmButton);
            long down = SystemClock.uptimeMillis();
            dispatch(MotionEvent.ACTION_DOWN, target, down);
            view.render("Changed state", second, true, "Tap the new action.");
            dispatch(MotionEvent.ACTION_UP, target, down);
            idle();
            assertTrue("A finger already down cannot approve a newly rendered action", calls.isEmpty());
            tap(view.alarmButton);
            assertEquals(Arrays.asList(second), calls);
            calls.clear();
        }

        view.render("Armed", AlarmAction.DISARM, true, "Tap once to disarm.");
        Rect target = bounds(view.alarmButton);
        long down = SystemClock.uptimeMillis();
        dispatch(MotionEvent.ACTION_DOWN, target, down);
        view.render("Status stale", null, true, "Refresh before continuing.");
        dispatch(MotionEvent.ACTION_UP, target, down);
        idle();
        assertTrue(calls.isEmpty());
    }

    @Test public void unknownAndDisabledControlsCannotActButRefreshIsIndependent() {
        view.render("Status unknown", null, true, "Refresh to check the alarm.");
        assertFalse("Even enabled=true cannot turn unknown state into an alarm action", view.alarmButton.isEnabled());
        tap(view.alarmButton);
        view.alarmButton.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null);
        assertTrue(calls.isEmpty());
        tap(view.refreshButton);
        assertEquals(1, refreshes);
        assertTrue(calls.isEmpty());

        view.render("Armed", AlarmAction.DISARM, false, "Waiting for the phone.");
        tap(view.alarmButton);
        assertFalse(view.refreshButton.isEnabled());
        view.refreshButton.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null);
        assertTrue(calls.isEmpty());
        assertEquals(1, refreshes);

        view.render("Armed", AlarmAction.DISARM, true, "Tap once to disarm.");
        assertTrue("Positive control: accessibility can activate a fresh known action",
            view.alarmButton.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        assertEquals(Arrays.asList(AlarmAction.DISARM), calls);
        view.render("Disarmed", AlarmAction.ARM_STAY, true, "Tap once to arm.");
        view.alarmButton.setFocusableInTouchMode(true);
        assertTrue(view.alarmButton.requestFocus());
        view.alarmButton.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
        view.alarmButton.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER));
        idle();
        assertEquals(Arrays.asList(AlarmAction.DISARM, AlarmAction.ARM_STAY), calls);
    }

    @Test public void largeTypeKeepsTheSingleActionReadableWithScrollFallback() {
        Configuration config = new Configuration(activity.getResources().getConfiguration());
        config.fontScale = 2f;
        mount(activity.createConfigurationContext(config));
        for (AlarmAction action : AlarmAction.values()) {
            view.render(action == AlarmAction.DISARM ? "Armed" : "Disarmed", action, true, "Fresh status from ADT.");
            layout();
            view.scrollTo(0, 0);
            assertTextFits(view.alarmButton);
            assertTrue(view.canScrollVertically(1));
        }
        view.render("Status unknown", null, false, "Refresh to check the alarm.");
        layout();
        assertTextFits(view.alarmButton);
        view.scrollTo(0, view.content.getHeight());
        Rect refresh = bounds(view.refreshButton);
        assertTrue("The refresh control remains reachable", refresh.top >= 0 && refresh.bottom <= view.getHeight());
    }

    private void assertState(String state, AlarmAction action, String detail, int color, String label) {
        view.render(state, action, true, detail);
        layout();
        assertEquals(state, view.statusView.getText().toString());
        assertTrue(view.alarmButton.getText().toString().startsWith(label));
        assertTrue(view.alarmButton.getContentDescription().toString().contains(state));
        assertTrue(view.alarmButton.getContentDescription().toString().contains(action == null ? "unavailable" : action.label()));
        assertEquals(state, view.alarmButton.getStateDescription().toString());
        GradientDrawable circle = (GradientDrawable) view.alarmButton.getBackground();
        assertEquals(GradientDrawable.OVAL, circle.getShape());
        assertEquals(color, circle.getColor().getDefaultColor());
        assertEquals(action != null, view.alarmButton.isEnabled());
        assertEquals(action == null ? View.VISIBLE : View.INVISIBLE, view.refreshButton.getVisibility());
        for (View child : new View[] {view.titleView, view.statusView, view.alarmButton, view.refreshButton}) {
            Rect rect = bounds(child);
            for (int x : new int[] {rect.left, rect.right}) for (int y : new int[] {rect.top, rect.bottom})
                assertTrue("Control outside the 240dp round viewport: " + rect, Math.hypot(x - 120, y - 120) <= 120.5);
        }
        assertTextFits(view.alarmButton);
        assertTextFits(view.statusView);
        assertFalse(view.canScrollVertically(1));
    }

    private void assertTextFits(TextView text) {
        Layout lines = text.getLayout();
        String detail = text.getText() + " in " + text.getWidth() + "x" + text.getHeight();
        assertNotNull(detail, lines);
        int last = lines.getLineCount() - 1;
        assertEquals(detail, text.length(), lines.getLineEnd(last));
        assertTrue(detail, lines.getLineBottom(last) <= text.getHeight()
            - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom());
        for (int i = 0; i < lines.getLineCount(); i++) {
            assertEquals(detail, 0, lines.getEllipsisCount(i));
            float left = lines.getLineLeft(i) - text.getScrollX();
            float right = lines.getLineRight(i) - text.getScrollX();
            int visibleWidth = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
            assertTrue(detail + ", visible line=" + left + ".." + right + ", content width=" + visibleWidth,
                left >= -0.5f && right <= visibleWidth + 0.5f);
        }
    }

    private void mount(Context context) {
        view = new AlarmToggleView(context, calls::add, () -> refreshes++);
        activity.setContentView(view);
        layout();
    }

    private void layout() {
        idle();
        view.measure(View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 240, 240);
    }

    private Rect bounds(View child) {
        // The framework conversion accepts content coordinates and subtracts this child's
        // internal scrolling too. Start with its viewport, especially for single-line text
        // centered inside Android's deliberately very wide internal text layout.
        Rect rect = new Rect(child.getScrollX(), child.getScrollY(),
            child.getScrollX() + child.getWidth(), child.getScrollY() + child.getHeight());
        view.offsetDescendantRectToMyCoords(child, rect);
        rect.offset(-view.getScrollX(), -view.getScrollY());
        return rect;
    }

    private void tap(View target) {
        Rect rect = bounds(target);
        long down = SystemClock.uptimeMillis();
        dispatch(MotionEvent.ACTION_DOWN, rect, down);
        dispatch(MotionEvent.ACTION_UP, rect, down);
        idle();
    }

    private void dispatch(int action, Rect rect, long down) {
        MotionEvent event = MotionEvent.obtain(down, down + (action == MotionEvent.ACTION_UP ? 10 : 0),
            action, rect.exactCenterX(), rect.exactCenterY(), 0);
        view.dispatchTouchEvent(event);
        event.recycle();
    }

    private void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }

    private void export(String name) throws IOException {
        File directory = new File("build/reports/watch-previews");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Path circle = new Path();
        circle.addCircle(120, 120, 120, Path.Direction.CW);
        canvas.clipPath(circle);
        view.draw(canvas);
        try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        } finally { bitmap.recycle(); }
    }
}
