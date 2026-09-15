package dev.personal.adtprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import java.util.List;

/** One-time, user-reviewed routine access. Opening or enabling this screen executes no scene. */
public final class RoutineSetupActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private Button enable;
    private AlertDialog dialog;
    private boolean resumed, busy;
    private int request;
    private long deadline;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            if (busy && (!available() || SystemClock.elapsedRealtime() >= deadline))
                finishFlow("Review canceled or expired. Watch controls were not changed.");
            enable.setEnabled(!busy && available() && Build.VERSION.SDK_INT >= 35);
            handler.postDelayed(this, 500);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        ArmExperimentService.cancelForNavigation(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(17, 22, 30));
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(22), dp(18), dp(22), dp(24)); scroll.addView(column); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets edges = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            scroll.setPadding(edges.left, edges.top, edges.right, edges.bottom); return insets;
        });
        text(column, "Watch controls", 27);
        text(column, "Enable your own watch once so each future request can start from the watch, without preparing this phone. "
            + "One deliberate tap on your unlocked watch sends the action shown on its alarm button.", 17);
        text(column, "First review both complete scenes in ADT: WATCH ARM STAY must contain only SYSTEM / ARM STAY, "
            + "and WATCH DISARM only SYSTEM / DISARM. Verify the options you intend. Configure both native widgets "
            + "in this app before enabling. A scene name alone does not prove its contents.", 16);
        text(column, "This is a personal prototype. A request result does not confirm the alarm's state. "
            + "A request already handed to ADT cannot be recalled here. Disable controls before changing either scene.", 16);
        status = text(column, "Checking saved access…", 17);
        enable = button(column, "Enable watch controls once…", this::beginReview);
        button(column, "Disable watch controls", () -> {
            finishFlow(null);
            ArmExperimentService.cancelForNavigation(this);
            boolean savedChange = RoutineAccess.disable(this);
            status.setText(savedChange ? "Watch controls disabled. New requests will be rejected."
                : "Controls are blocked in this process, but the change could not be saved. Retry Disable before closing this app.");
        });
        button(column, "Watch association setup…", () -> {
            if (!available()) return;
            finishFlow(null);
            ArmExperimentService.cancelForNavigation(this);
            startActivity(new Intent(this, WatchSetupActivity.class));
        });
        button(column, "Done", this::finish);
    }

    private void beginReview() {
        if (!available() || busy) return;
        ArmExperimentService.cancelForNavigation(this);
        RoutineAccess.Review review = RoutineAccess.prepareReview(this);
        if (review == null) {
            status.setText("Set a secure phone screen lock and finish both scene widgets first. "
                + "Routine access also requires Android 15 or later and the reviewed ADT version.");
            return;
        }
        List<RoutineAccess.AssociationChoice> associations = RoutineAccess.associations(this);
        if (associations.isEmpty()) {
            status.setText("No identifiable native watch association is available. Use Watch association setup, "
                + "grant Bluetooth access if requested, and confirm your own watch there first.");
            return;
        }
        busy = true;
        int serial = ++request;
        deadline = SystemClock.elapsedRealtime() + 60_000;
        String[] labels = new String[associations.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = associations.get(i).label
            + (associations.size() > 1 ? " — association " + (i + 1) : "");
        dialog = new AlertDialog.Builder(this).setTitle("Choose your associated watch")
            .setItems(labels, (ignored, which) -> {
                dialog = null;
                if (!current(serial) || which < 0 || which >= associations.size()) return;
                RoutineAccess.AssociationChoice selected = associations.get(which);
                readPeer(serial, (node, name) -> showReview(serial, review, selected, node, name));
            })
            .setNegativeButton("Cancel", (ignored, which) -> finishFlow("Review canceled. Controls were not changed."))
            .setOnCancelListener(ignored -> finishFlow("Review canceled. Controls were not changed.")).create();
        dialog.show();
    }

    private void showReview(int serial, RoutineAccess.Review review, RoutineAccess.AssociationChoice association,
            String node, String nodeName) {
        if (!current(serial)) return;
        deadline = SystemClock.elapsedRealtime() + 60_000;
        dialog = new AlertDialog.Builder(this).setTitle("Enable these watch controls?")
            .setMessage("Associated device: " + association.label + "\nConnected watch: " + nodeName
                + "\n\nConfirm both refer to your own watch and that you reviewed the complete WATCH ARM STAY "
                + "and WATCH DISARM scenes and their options.\n\nFuture single-tap watch requests may run those "
                + "scenes while this phone is locked, without preparing the phone each time. This setup sends no alarm request.")
            .setNegativeButton("Cancel", (ignored, which) -> finishFlow("Review canceled. Controls were not changed."))
            .setPositiveButton("I reviewed them — enable", (ignored, which) -> {
                dialog = null;
                if (!current(serial)) return;
                // Re-query after consent: a late/disconnected/different peer never gets saved automatically.
                readPeer(serial, (freshNode, freshName) -> {
                    if (!current(serial)) return;
                    if (!node.equals(freshNode) || !nodeName.equals(freshName)) {
                        finishFlow("The connected watch changed. Nothing was enabled; review again.");
                        return;
                    }
                    ArmExperimentService.cancelForNavigation(this);
                    boolean saved = RoutineAccess.enableReviewed(this, review, freshNode, association.id);
                    finishFlow(saved ? "Watch controls enabled. Tap the watch alarm button once to send the displayed action."
                        : "Setup changed or could not be saved. Controls were not enabled; review again.");
                });
            }).setOnCancelListener(ignored -> finishFlow("Review canceled. Controls were not changed.")).create();
        dialog.show();
    }

    private interface PeerResult { void received(String node, String name); }

    private void readPeer(int serial, PeerResult result) {
        if (!current(serial)) return;
        deadline = SystemClock.elapsedRealtime() + 10_000;
        status.setText("Checking the single connected watch…");
        try {
            Wearable.getNodeClient(this).getConnectedNodes().addOnCompleteListener(getMainExecutor(), task -> {
                if (!current(serial)) return;
                List<Node> nodes = task.isSuccessful() ? task.getResult() : null;
                if (nodes == null || nodes.size() != 1 || nodes.get(0) == null
                        || !WatchProtocol.validNodeId(nodes.get(0).getId())) {
                    finishFlow("Exactly one connected watch is required. Nothing was enabled.");
                    return;
                }
                String name = RoutineAccess.safeName(nodes.get(0).getDisplayName());
                if (name == null) {
                    finishFlow("The connected watch could not be identified. Nothing was enabled.");
                    return;
                }
                result.received(nodes.get(0).getId(), name);
            });
        } catch (RuntimeException ignored) {
            finishFlow("Watch discovery was unavailable. Nothing was enabled.");
        }
    }

    private boolean current(int serial) {
        return busy && serial == request && available() && SystemClock.elapsedRealtime() < deadline;
    }
    private boolean available() { return resumed && RoutineAccess.visibleUnlocked(this); }
    private void finishFlow(String message) {
        request++; busy = false; deadline = 0;
        AlertDialog old = dialog; dialog = null;
        if (old != null) old.dismiss();
        if (message != null && status != null) status.setText(message);
    }

    @Override public void onStart() {
        super.onStart(); ArmExperimentService.cancelForNavigation(this);
    }
    @Override public void onResume() {
        super.onResume(); resumed = true;
        status.setText(Build.VERSION.SDK_INT < 35 ? "Routine access requires Android 15 or later. Manual setup remains available."
            : RoutineAccess.snapshot(this) == null ? "Watch controls are disabled or their reviewed setup is no longer valid."
            : "Watch controls are enabled for the reviewed watch.");
        handler.removeCallbacks(tick); handler.post(tick);
    }
    @Override public void onPause() {
        resumed = false; finishFlow(null); handler.removeCallbacks(tick); super.onPause();
    }
    @Override public void onDestroy() {
        finishFlow(null); handler.removeCallbacksAndMessages(null); super.onDestroy();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(LinearLayout column, String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(Color.WHITE);
        view.setPadding(0, dp(8), 0, dp(8)); column.addView(view); return view;
    }
    private Button button(LinearLayout column, String label, Runnable click) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setMinHeight(dp(54));
        button.setOnClickListener(view -> click.run()); column.addView(button); return button;
    }
}
