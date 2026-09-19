package dev.personal.adtprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

/** Explicit exceptional status recovery on the phone. This screen has no alarm-command path. */
public final class StateRecoveryActivity extends Activity {
    private static final long REVIEW_MS = 60_000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private RadioGroup choices;
    private Button record;
    private AlertDialog dialog;
    private AlarmStateProtocol.State selected;
    private boolean resumed;
    private long reviewUntil;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            if (dialog != null && (SystemClock.elapsedRealtime() >= reviewUntil || !unlockedVisible()))
                cancelReview("Review expired or the phone was locked. Nothing was recorded.");
            updateButton();
            handler.postDelayed(this, 500);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        // No intent extra, saved selection or lifecycle callback can record a checked status.
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(17, 22, 30));
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(22), dp(18), dp(22), dp(24)); scroll.addView(column); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets edges = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            scroll.setPadding(edges.left, edges.top, edges.right, edges.bottom); return insets;
        });
        text(column, "Recover missing status", 27);
        text(column, "Use this only when an ADT status notification is missing. Open ADT, check its current Home status "
            + "and recent Activity, and wait until any alarm request has finished. Then return here and record what you checked.", 17);
        button(column, "Open ADT to check", () -> {
            if (!available()) return;
            clearSelection();
            try { startActivity(Probe.neutralIntent()); }
            catch (RuntimeException ignored) { status.setText("ADT could not be opened. Check its app directly first."); }
        });
        text(column, "Recording a checked status sends no alarm command and cannot cancel a request already queued by ADT. "
            + "The watch will label it Checked, and it expires after 5 minutes unless a newer ADT report arrives.", 16);
        status = text(column, PhoneAlarmState.setupStatus(this), 16);
        text(column, "Which state did you just check in ADT?", 18);
        choices = new RadioGroup(this); choices.setOrientation(RadioGroup.VERTICAL);
        choices.setSaveEnabled(false);
        addChoice("Disarmed", AlarmStateProtocol.State.DISARMED);
        addChoice("Armed Stay", AlarmStateProtocol.State.ARMED_STAY);
        addChoice("Armed Away", AlarmStateProtocol.State.ARMED_AWAY);
        choices.setOnCheckedChangeListener((group, id) -> {
            View chosen = group.findViewById(id);
            selected = chosen == null ? null : (AlarmStateProtocol.State) chosen.getTag();
            updateButton();
        });
        column.addView(choices);
        record = button(column, "Record checked status…", this::beginReview);
        record.setEnabled(false);
        button(column, "Back", this::finish);
    }

    private void addChoice(String label, AlarmStateProtocol.State state) {
        RadioButton choice = new RadioButton(this); choice.setId(View.generateViewId());
        choice.setText(label); choice.setTextSize(18); choice.setTextColor(Color.WHITE);
        choice.setTag(state); choice.setMinHeight(dp(52)); choice.setSaveEnabled(false);
        choice.setFilterTouchesWhenObscured(true); choices.addView(choice);
    }

    private void beginReview() {
        if (!available() || selected == null || dialog != null || ArmExperimentService.isRunning()) return;
        final AlarmStateProtocol.State checked = selected;
        final String reviewedRevision = PhoneAlarmState.snapshot(this).revision;
        reviewUntil = SystemClock.elapsedRealtime() + REVIEW_MS;
        dialog = new AlertDialog.Builder(this).setTitle("Record " + label(checked) + "?")
            .setMessage("Confirm that you just checked ADT's current Home status and recent Activity, that the system is "
                + label(checked) + ", and that any alarm request has finished.\n\n"
                + "This records your observation for 5 minutes. It sends no alarm command and cannot cancel queued ADT requests.")
            .setNegativeButton("Cancel", (ignored, which) -> cancelReview("Nothing was recorded."))
            .setPositiveButton("I checked — record", (ignored, which) -> {
                if (!available() || SystemClock.elapsedRealtime() >= reviewUntil || selected != checked) {
                    cancelReview("Review expired or the phone was locked. Nothing was recorded."); return;
                }
                boolean saved = PhoneAlarmState.recordPhoneCheck(this, checked, reviewedRevision);
                cancelReview(saved ? "You checked " + label(checked) + ". Recorded for up to 5 minutes. No alarm command was sent."
                    : !reviewedRevision.equals(PhoneAlarmState.snapshot(this).revision)
                    ? "ADT status changed during your review. Nothing was recorded. Check ADT's current status and Activity again."
                    : "Nothing was recorded. Keep this phone unlocked, finish any active request, and check that watch setup "
                        + "and ADT status access are still enabled. A known, unambiguous alarm and a valid phone clock are required.");
                clearSelection();
            }).setOnCancelListener(ignored -> cancelReview("Nothing was recorded.")).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setFilterTouchesWhenObscured(true);
        updateButton();
    }

    private boolean unlockedVisible() { return resumed && RoutineAccess.visibleUnlocked(this); }
    private boolean available() {
        if (!unlockedVisible()) return false;
        if (dialog == null) return hasWindowFocus();
        return dialog.isShowing() && dialog.getWindow() != null
            && dialog.getWindow().getDecorView().hasWindowFocus();
    }
    private void updateButton() {
        if (record != null) record.setEnabled(dialog == null && selected != null && available() && !ArmExperimentService.isRunning());
    }
    private void clearSelection() {
        selected = null;
        if (choices != null) choices.clearCheck();
        updateButton();
    }
    private void cancelReview(String message) {
        AlertDialog old = dialog; dialog = null; reviewUntil = 0;
        if (old != null) old.dismiss();
        if (message != null && status != null) status.setText(message);
        updateButton();
    }
    @Override public void onResume() {
        super.onResume(); resumed = true;
        if (status != null) status.setText(PhoneAlarmState.setupStatus(this));
        handler.post(tick); updateButton();
    }
    @Override public void onPause() {
        resumed = false; handler.removeCallbacks(tick); cancelReview(null); clearSelection(); super.onPause();
    }
    @Override public void onDestroy() { cancelReview(null); handler.removeCallbacksAndMessages(null); super.onDestroy(); }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); updateButton(); }

    private static String label(AlarmStateProtocol.State state) {
        return state == AlarmStateProtocol.State.DISARMED ? "Disarmed"
            : state == AlarmStateProtocol.State.ARMED_STAY ? "Armed Stay" : "Armed Away";
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(LinearLayout column, String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(Color.WHITE);
        view.setPadding(0, dp(8), 0, dp(8)); column.addView(view); return view;
    }
    private Button button(LinearLayout column, String label, Runnable click) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setMinHeight(dp(54));
        button.setFilterTouchesWhenObscured(true); button.setOnClickListener(view -> click.run()); column.addView(button); return button;
    }
}
