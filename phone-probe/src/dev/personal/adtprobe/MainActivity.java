package dev.personal.adtprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ComponentName;
import android.provider.Settings;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Setup only. This screen never authorizes or schedules an alarm command. */
public final class MainActivity extends Activity {
    private LinearLayout column;
    private TextView alarmStatus;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTick = new Runnable() {
        @Override public void run() {
            if (alarmStatus != null) alarmStatus.setText(PhoneAlarmState.setupStatus(MainActivity.this));
            handler.postDelayed(this, 1500);
        }
    };
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setPadding(0, dp(10), 0, dp(10)); column.addView(view); return view;
    }
    private Button button(String label, View.OnClickListener action) {
        Button view = new Button(this);
        view.setText(label); view.setAllCaps(false); view.setMinHeight(dp(54));
        view.setOnClickListener(action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(5), 0, dp(5)); column.addView(view, params); return view;
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(17, 22, 30));
        column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(22), dp(18), dp(22), dp(24));
        scroll.addView(column); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets edges = insets.getInsets(WindowInsets.Type.systemBars()
                | WindowInsets.Type.displayCutout());
            scroll.setPadding(edges.left, edges.top, edges.right, edges.bottom); return insets;
        });
        text("ADT Watch Setup", 28, Color.WHITE);
        text("Personal app · v0.25", 15, Color.LTGRAY);
        text("One-tap watch control", 21, Color.WHITE);
        text("Red means ADT reported Armed: tap to Disarm. Green means ADT reported Disarmed: tap to Arm Stay. "
            + "The watch also has an ADT Watch tile you can add to its swipeable tiles.", 16, Color.LTGRAY);
        text("Set up ADT live status below: sign in to the official ADT page and choose the same home as both scene widgets. "
            + "This is a separate sign-in from the ADT app. Refresh queries ADT's actual state without operating the alarm. "
            + "Optional automatic login can recover an expired session. ADT may still ask you to verify a login.", 15, Color.LTGRAY);
        alarmStatus = text(PhoneAlarmState.setupStatus(this), 15, Color.rgb(155, 212, 229));
        button("Watch control access…", view ->
            startActivity(new Intent(this, RoutineSetupActivity.class)));
        button("Set up ADT live status…", view ->
            startActivity(new Intent(this, AdtPortalSetupActivity.class)));
        button("Automatic ADT login…", view ->
            startActivity(new Intent(this, AdtAutoLoginActivity.class)));
        button("Allow ADT refresh hints…", view -> {
            Intent settings = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    new ComponentName(this, AdtStateListener.class).flattenToString());
            try { startActivity(settings); }
            catch (RuntimeException e) { startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
        });
        text("Notification access is optional. ADT notifications only prompt a live status query; "
            + "they do not supply the alarm state. Grey means fresh, usable ADT status is unavailable or a request is in progress.",
            15, Color.LTGRAY);
        button("Open ADT", view -> {
            try { startActivity(Probe.neutralIntent()); }
            catch (Exception e) {
                new AlertDialog.Builder(this).setMessage("ADT could not be opened. Check that ADT Smart Services is installed.")
                    .setPositiveButton("OK", null).show();
            }
        });
        text("Add the watch tile", 21, Color.WHITE);
        text("On the watch, swipe to a tile, touch and hold, then choose Add and ADT Watch. "
            + "The paired phone needs to be locked and connected to use the alarm button.", 16, Color.LTGRAY);
        button("Advanced setup…", view -> new AlertDialog.Builder(this).setTitle("Advanced setup")
            .setItems(new String[]{"Configure Arm Stay scene widget", "Configure Disarm scene widget",
                "Watch pairing", "Arm Stay diagnostic", "Disarm diagnostic", "Earlier diagnostic log"}, (dialog, item) -> {
                if (item < 2) startActivity(new Intent(this, WidgetSetupActivity.class)
                    .putExtra(AlarmWidgetSlot.ACTION_EXTRA, item == 0 ? "ARM_STAY" : "DISARM"));
                else if (item == 2) startActivity(new Intent(this, WatchSetupActivity.class));
                else if (item < 5) startActivity(new Intent(this, ArmExperimentActivity.class)
                    .putExtra(AlarmWidgetSlot.ACTION_EXTRA, item == 3 ? "ARM_STAY" : "DISARM"));
                else {
                    ScrollView logScroll = new ScrollView(this);
                    TextView log = new TextView(this);
                    log.setText(Probe.events(this)); log.setTextSize(13); log.setTextIsSelectable(true);
                    log.setPadding(dp(18), dp(12), dp(18), dp(12)); logScroll.addView(log);
                    new AlertDialog.Builder(this).setTitle("Earlier diagnostic log").setView(logScroll)
                        .setPositiveButton("Close", null).show();
                }
            }).setNegativeButton("Close", null).show());
    }

    @Override public void onStart() {
        super.onStart(); ArmExperimentService.cancelForNavigation(this); Probe.activityVisible = true;
    }
    @Override public void onResume() { super.onResume(); handler.post(refreshTick); }
    @Override public void onPause() { handler.removeCallbacks(refreshTick); super.onPause(); }
    @Override public void onStop() { Probe.activityVisible = false; super.onStop(); }
}
