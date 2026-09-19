package dev.personal.adtprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** A visible, explicit preparation screen; opening it never authorizes an alarm action. */
public final class ArmExperimentActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private AlarmAction action;
    private Button probe;
    private Button prepare;
    private boolean resumed;
    private boolean leftScreen;
    private AlertDialog confirmation;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            cancelOnUnlockedReturn();
            String value = ArmExperimentService.status(ArmExperimentActivity.this);
            if (!value.contentEquals(status.getText())) status.setText(value);
            boolean available = unlocked() && !ArmExperimentService.isRunning();
            probe.setEnabled(available);
            prepare.setEnabled(available);
            handler.postDelayed(this, 500);
        }
    };

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private void text(LinearLayout column, String value, int size) {
        TextView v = new TextView(this);
        v.setText(value); v.setTextSize(size); v.setTextColor(Color.WHITE);
        v.setPadding(0, dp(8), 0, dp(8)); column.addView(v);
    }
    private Button button(LinearLayout column, String label, Runnable click) {
        Button b = new Button(this); b.setAllCaps(false); b.setText(label);
        b.setMinHeight(dp(52)); b.setOnClickListener(v -> click.run());
        column.addView(b, new LinearLayout.LayoutParams(-1, -2)); return b;
    }
    private boolean unlocked() {
        KeyguardManager k = getSystemService(KeyguardManager.class);
        return k != null && !k.isKeyguardLocked() && !k.isDeviceLocked();
    }

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        ArmExperimentService.cancelForNavigation(this);
        action = AlarmWidgetSlot.fromIntent(getIntent());
        if (action == null) { finish(); return; }
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(17, 22, 30));
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(22), dp(18), dp(22), dp(24)); scroll.addView(column);
        setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets e = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            scroll.setPadding(e.left, e.top, e.right, e.bottom); return insets;
        });
        text(column, action.label() + " experiment", 27);
        text(column, "One watch test using your " + action.widgetLabel() + " scene. Check ADT afterward for the actual alarm state.", 17);
        text(column, "Check the widget first", 21);
        text(column, "This checks whether the configured widget can stay ready in the background. It cannot activate the scene.", 16);
        probe = button(column, "Check background widget", () -> start(false));
        status = new TextView(this); status.setTextColor(Color.rgb(155, 212, 229));
        status.setTextSize(17); status.setPadding(0, dp(16), 0, dp(16)); column.addView(status);
        text(column, "Try one watch request", 21);
        text(column, "Prepare only when you intend to " + (action == AlarmAction.DISARM ? "disarm" : "arm")
            + " the house. Then lock this phone and tap the watch's alarm button while it offers " + action.label()
            + "; that single tap is the confirmation. The watch needs a current ADT state, so watch controls must be "
            + "enabled for it to receive status. Preparation expires after two minutes.", 16);
        prepare = button(column, "Prepare one " + action.label() + " test…", () -> {
            if (!resumed || !unlocked() || ArmExperimentService.isRunning()) return;
            confirmation = new AlertDialog.Builder(this).setTitle("Prepare one " + action.label() + " test?")
                .setMessage("Confirm that " + action.widgetLabel() + " contains only the intended SYSTEM / "
                    + (action == AlarmAction.DISARM ? "DISARM" : "ARM STAY") + " action. "
                    + "A single tap on your unlocked watch may then run that complete scene once while this phone is locked. "
                    + "Check ADT afterward to confirm the result. A request already sent to ADT cannot be recalled here.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Prepare test", (dialog, which) -> start(true)).create();
            confirmation.show();
        });
        button(column, "Cancel preparation", () -> ArmExperimentService.cancelForNavigation(this));
        button(column, "Done", this::finish);
    }

    private void start(boolean authorised) {
        if (!resumed || !unlocked() || ArmExperimentService.isRunning()) return;
        ArmExperimentService.startFromVisibleActivity(this, action, authorised);
        handler.removeCallbacks(tick); handler.post(tick);
    }
    @Override public void onResume() {
        super.onResume();
        if (action == null) return;
        resumed = true;
        cancelOnUnlockedReturn();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }
    private void cancelOnUnlockedReturn() {
        // Android may briefly resume us behind keyguard while entering its dock screen.
        // Preserve preparation through that transition, but cancel when the user returns unlocked.
        if (leftScreen && unlocked()) {
            leftScreen = false;
            ArmExperimentService.cancelForNavigation(this);
        }
    }
    @Override public void onPause() {
        resumed = false; leftScreen = true; handler.removeCallbacks(tick);
        if (confirmation != null) confirmation.dismiss();
        super.onPause();
    }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (isFinishing()) ArmExperimentService.cancelForNavigation(this);
        super.onDestroy();
    }
}
