package dev.personal.adtprobe;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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

/** Real view measurement and input; callbacks use only the local protocol, never a transport. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w240dp-h240dp-round-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class WatchExperimentViewTest {
    private static String[] feedback(AlarmAction action) {
        return new String[] {
            "Choose Disarm or Arm Stay.",
            "Checking phone readiness…",
            "Phone ready. Confirm " + action.label() + ".",
            action.label() + " requested. Check ADT to confirm.",
            "Phone not ready or test expired. No command sent.",
            "Result unconfirmed. Check ADT before retrying.",
            "Phone not ready. Check phone setup."
        };
    }
    private ActivityController<Activity> controller;
    private Activity activity;
    private WatchExperimentView view;
    private ArmExperimentProtocol.Attempt attempt;
    private AlarmAction committedAction;
    private int starts;
    private int confirmations;
    private int commits;
    private int connections;

    @Before public void prepare() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        resetView(activity);
    }

    @After public void close() { controller.pause().stop().destroy(); }

    @Test public void bothActionsAndFeedbackFitRoundViewportWithoutMovingConfirmation() throws IOException {
        assertTrue(activity.getResources().getConfiguration().isScreenRound());
        assertEquals(1f, activity.getResources().getDisplayMetrics().density, 0.001f);
        assertEquals("ADT Watch", view.titleView.getText().toString());
        assertEquals("Arm Stay", view.armStayButton.getText().toString());
        assertEquals("Disarm", view.disarmButton.getText().toString());
        assertEquals("Confirm action", view.confirmButton.getText().toString());
        Rect arm = bounds(view.armStayButton);
        Rect disarm = bounds(view.disarmButton);
        Rect confirm = bounds(view.confirmButton);
        assertTrue("Action targets are distinct", arm.right < disarm.left);
        assertTrue("Confirmation is a distinct lower target", Math.max(arm.bottom, disarm.bottom) < confirm.top);
        assertTrue(arm.height() >= 48 && arm.width() >= 48);
        assertTrue(disarm.height() >= 48 && disarm.width() >= 48);
        assertTrue(confirm.height() >= 48);
        for (AlarmAction action : AlarmAction.values()) {
            String[] messages = feedback(action);
            for (int i = 0; i < messages.length; i++) {
                view.render(i == 1 || i == 2 ? action : null, i == 0, i == 2, messages[i]);
                layout();
                assertEquals(arm, bounds(view.armStayButton));
                assertEquals(disarm, bounds(view.disarmButton));
                assertEquals(confirm, bounds(view.confirmButton));
                if (i == 2) assertEquals("Confirm " + action.label(), view.confirmButton.getText().toString());
                for (View child : new View[] {view.titleView, view.feedbackView, view.armStayButton,
                        view.disarmButton, view.confirmButton, view.connectionButton}) {
                    assertEquals(View.VISIBLE, child.getVisibility());
                    assertInsideCircle(child);
                }
                assertTextFits(view.feedbackView, 3);
                assertTextFits(view.armStayButton, 2);
                assertTextFits(view.disarmButton, 2);
                assertTextFits(view.confirmButton, 2);
                assertTextFits(view.connectionButton, 2);
                assertFalse("Default font needs no scrolling", view.canScrollVertically(1));
                String prefix = action == AlarmAction.ARM_STAY ? "" : "disarm-";
                if (i == 0) exportPreview(prefix + "idle");
                if (i == 2) exportPreview(prefix + "ready");
                if (i == 3) exportPreview(prefix + "result");
            }
        }
        assertEquals("Rendering cannot cause an action", 0, starts + confirmations + connections);
    }

    @Test public void neitherDoubleTapNorOtherActionTapCanReplaceSeparateConfirmation() {
        for (AlarmAction action : AlarmAction.values()) {
            resetView(activity);
            view.render(null, true, false, feedback(action)[0]);
            layout();
            Rect start = bounds(actionButton(action));
            Rect other = bounds(actionButton(other(action)));
            tap(start);
            assertEquals("Positive control: touch really reaches the selected action", 1, starts);
            assertEquals(action, attempt.action());
            assertEquals(ArmExperimentProtocol.Phase.AWAITING_CHALLENGE, attempt.phase());
            tap(other);
            assertEquals("Discovery cannot switch to the other action", 1, starts);
            challenge();
            layout();
            tap(start); // Same location after the asynchronous readiness reply.
            tap(other); // The second action remains disabled while this attempt owns confirmation.
            assertEquals(1, starts);
            assertEquals(action, attempt.action());
            assertEquals(0, confirmations);
            assertEquals(0, commits);
            assertEquals(ArmExperimentProtocol.Phase.CONFIRMABLE, attempt.phase());

            Rect confirm = bounds(view.confirmButton);
            tap(confirm);
            assertEquals("Positive control: only the separate target confirms", 1, confirmations);
            assertEquals(1, commits);
            assertEquals(action, committedAction);
            assertEquals(ArmExperimentProtocol.Phase.AWAITING_RESULT, attempt.phase());
            tap(confirm);
            tap(other);
            assertEquals("A second tap cannot submit or switch actions", 1, confirmations);
            assertEquals(1, starts);
            assertEquals(1, commits);
        }
    }

    @Test public void wrongActionChallengeNeverEnablesConfirmation() {
        for (AlarmAction action : AlarmAction.values()) {
            resetView(activity);
            view.render(null, true, false, feedback(action)[0]);
            tap(bounds(actionButton(action)));
            byte[] wrong = ArmExperimentProtocol.encodeChallenge(other(action), attempt.requestId(),
                "00000000-0000-4000-8000-000000000001");
            assertFalse(attempt.acceptChallenge(wrong, "inert-test-phone", now()));
            view.render(attempt.action(), false, attempt.canConfirm(now()), feedback(action)[1]);
            tap(bounds(view.confirmButton));
            assertEquals(0, confirmations);
            assertEquals(0, commits);
            assertEquals(action, attempt.action());
            challenge();
            tap(bounds(view.confirmButton));
            assertEquals("Only the matching action's challenge permits confirmation", 1, commits);
            assertEquals(action, committedAction);
        }
    }

    @Test public void disabledAndExpiredConfirmationCannotCommitForEitherAction() {
        for (AlarmAction action : AlarmAction.values()) {
            resetView(activity);
            tap(bounds(view.confirmButton));
            assertEquals(0, confirmations);
            view.render(null, true, false, feedback(action)[0]);
            tap(bounds(actionButton(action)));
            tap(bounds(view.confirmButton));
            assertEquals(0, confirmations);
            challenge();
            assertFalse(attempt.canConfirm(now() + ArmExperimentProtocol.CONFIRM_TIMEOUT_MS));
            view.render(attempt.action(), false, false, feedback(action)[4]);
            layout();
            tap(bounds(view.confirmButton));
            assertEquals(0, confirmations);
            assertEquals(0, commits);
            assertEquals(ArmExperimentProtocol.Phase.EXPIRED, attempt.phase());
        }
    }

    @Test public void connectionTargetOnlyNavigates() {
        tap(bounds(view.connectionButton));
        assertEquals(1, connections);
        assertEquals(0, starts + confirmations + commits);
        assertNull(attempt);
    }

    @Test public void largeFontsKeepBothActionsAndConfirmationStableWithScrolling() {
        Configuration configuration = new Configuration(activity.getResources().getConfiguration());
        configuration.fontScale = 2f;
        resetView(activity.createConfigurationContext(configuration));
        view.render(AlarmAction.ARM_STAY, false, false, feedback(AlarmAction.ARM_STAY)[0]);
        layout();
        view.scrollTo(0, 0);
        assertTrue(view.feedbackView.getTextSize() > 12f);
        Rect arm = bounds(view.armStayButton);
        Rect disarm = bounds(view.disarmButton);
        Rect confirm = bounds(view.confirmButton);
        assertTrue("Larger type gets full-width separate action rows", arm.bottom < disarm.top);
        assertTrue(disarm.bottom < confirm.top);
        assertEquals(View.VISIBLE, view.confirmButton.getVisibility());
        for (AlarmAction action : AlarmAction.values()) {
            for (String message : feedback(action)) {
                view.render(action, false, false, message);
                layout();
                view.scrollTo(0, 0);
                assertEquals("Arm Stay stays fixed across feedback", arm, bounds(view.armStayButton));
                assertEquals("Disarm stays fixed across feedback", disarm, bounds(view.disarmButton));
                assertEquals("Confirm stays fixed across actions and feedback", confirm, bounds(view.confirmButton));
                assertTextFits(view.feedbackView, view.feedbackView.getMaxLines());
                assertTextFits(view.armStayButton, 2);
                assertTextFits(view.disarmButton, 2);
                assertTextFits(view.confirmButton, 2);
                assertTextFits(view.connectionButton, 2);
                assertTrue("Overflow remains reachable instead of being clipped", view.canScrollVertically(1));
            }
        }
        view.scrollTo(0, view.content.getHeight());
        Rect connection = bounds(view.connectionButton);
        assertTrue("Scrolled connection target must fit viewport: " + connection
            + ", scrollY=" + view.getScrollY(), connection.top >= 0 && connection.bottom <= view.getHeight());
    }

    private void resetView(Context context) {
        attempt = null;
        committedAction = null;
        starts = confirmations = commits = connections = 0;
        view = new WatchExperimentView(context, action -> {
            starts++;
            assertNull("A selection must never replace an active attempt", attempt);
            attempt = new ArmExperimentProtocol.Attempt(action, now());
            assertTrue(attempt.selectTarget("inert-test-phone", now()));
            ArmExperimentProtocol.Message prepare = ArmExperimentProtocol.parse(attempt.prepare(now()));
            assertNotNull(prepare);
            assertEquals(action, prepare.action);
            view.render(action, false, false, feedback(action)[1]);
        }, () -> {
            confirmations++;
            byte[] commit = attempt == null ? null : attempt.commit(now());
            if (commit != null) {
                ArmExperimentProtocol.Message message = ArmExperimentProtocol.parse(commit, ArmExperimentProtocol.Kind.COMMIT);
                assertNotNull(message);
                assertEquals(attempt.action(), message.action);
                committedAction = message.action;
                commits++;
            }
            view.render(attempt == null ? null : attempt.action(), false, false,
                feedback(attempt == null ? AlarmAction.ARM_STAY : attempt.action())[3]);
        }, () -> connections++);
        activity.setContentView(view);
        layout();
    }

    private View actionButton(AlarmAction action) {
        return action == AlarmAction.ARM_STAY ? view.armStayButton : view.disarmButton;
    }

    private AlarmAction other(AlarmAction action) {
        return action == AlarmAction.ARM_STAY ? AlarmAction.DISARM : AlarmAction.ARM_STAY;
    }

    private void challenge() {
        byte[] challenge = ArmExperimentProtocol.encodeChallenge(attempt.action(), attempt.requestId(),
            "00000000-0000-4000-8000-000000000001");
        assertTrue(attempt.acceptChallenge(challenge, "inert-test-phone", now()));
        view.render(attempt.action(), false, attempt.canConfirm(now()), feedback(attempt.action())[2]);
    }

    private void layout() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        view.measure(View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 240, 240);
    }

    private Rect bounds(View child) {
        Rect rect = new Rect(0, 0, child.getWidth(), child.getHeight());
        view.offsetDescendantRectToMyCoords(child, rect);
        // The framework method returns this ScrollView's content coordinates.
        rect.offset(-view.getScrollX(), -view.getScrollY());
        return rect;
    }

    private void assertInsideCircle(View child) {
        Rect rect = bounds(child);
        assertTrue(child + " must have visible dimensions", rect.width() > 0 && rect.height() > 0);
        for (int x : new int[] {rect.left, rect.right}) {
            for (int y : new int[] {rect.top, rect.bottom}) {
                double distance = Math.hypot(x - 120, y - 120);
                assertTrue(child + " corner outside round screen: " + rect, distance <= 120.5);
            }
        }
    }

    private void assertTextFits(TextView text, int maximumLines) {
        Layout lines = text.getLayout();
        String detail = "Text '" + text.getText() + "', view=" + text.getWidth() + "x" + text.getHeight()
            + ", padding=" + text.getCompoundPaddingTop() + "/" + text.getCompoundPaddingBottom();
        assertNotNull(detail, lines);
        assertTrue(detail + ", lines=" + lines.getLineCount(), lines.getLineCount() <= maximumLines);
        int last = lines.getLineCount() - 1;
        assertEquals(detail + ": all text must be laid out", text.length(), lines.getLineEnd(last));
        assertTrue(detail + ", last line bottom=" + lines.getLineBottom(last), lines.getLineBottom(last) <= text.getHeight()
            - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom());
        for (int i = 0; i < lines.getLineCount(); i++) {
            assertEquals(detail + ", line=" + i + " cannot be ellipsized", 0, lines.getEllipsisCount(i));
            // getLineWidth includes trailing wrap spaces, which have no visible ink.
            String line = detail + ", line=" + i + ", visible=" + lines.getLineLeft(i)
                + ".." + lines.getLineRight(i) + ", layout width=" + lines.getWidth()
                + ", advance including spaces=" + lines.getLineWidth(i);
            assertTrue(line, lines.getLineLeft(i) >= -0.5f && lines.getLineRight(i) <= lines.getWidth() + 0.5f);
        }
    }

    private void exportPreview(String name) throws IOException {
        File directory = new File("build/reports/watch-previews");
        assertTrue("Preview directory must be writable", directory.isDirectory() || directory.mkdirs());
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Path screen = new Path();
        screen.addCircle(view.getWidth() / 2f, view.getHeight() / 2f,
            Math.min(view.getWidth(), view.getHeight()) / 2f, Path.Direction.CW);
        canvas.clipPath(screen);
        view.draw(canvas);
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            bitmap.recycle();
        }
    }

    private void tap(Rect target) {
        long time = now();
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN,
            target.exactCenterX(), target.exactCenterY(), 0);
        MotionEvent up = MotionEvent.obtain(time, time + 10, MotionEvent.ACTION_UP,
            target.exactCenterX(), target.exactCenterY(), 0);
        view.dispatchTouchEvent(down);
        view.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private long now() { return SystemClock.uptimeMillis(); }
}
