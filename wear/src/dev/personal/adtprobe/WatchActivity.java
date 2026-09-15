package dev.personal.adtprobe;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.WindowInsets;
import android.view.WindowManager;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import java.io.FileDescriptor;
import java.io.PrintWriter;

/** A user tap selects one fixed action; readiness never chooses or reverses it. */
public final class WatchActivity extends Activity {
    interface Sender { void send(String node, String path, byte[] payload, Runnable failure); }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MessageClient client;
    private MessageClient.OnMessageReceivedListener listener;
    private Sender sender;
    private AlarmToggleView controls;
    private boolean resumed, listenerReady, commandSent;
    private int generation;
    private ArmExperimentProtocol.Attempt attempt;
    private WatchAlarmStore.Selection selection;
    private String displayedToken, feedback, tileAction, tileToken;
    private long tileUntil;
    private SharedPreferences preferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener changed = (prefs, key) -> handler.post(() -> {
        if (attempt != null && !commandSent && !WatchAlarmStore.stillCurrent(this, selection))
            endAttempt("Status changed. Please check the new state.", false);
        if (attempt == null && WatchAlarmStore.read(this).enabled) feedback = null;
        render();
    });
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            if (attempt != null && (!interactive() || !attempt.isActive(SystemClock.elapsedRealtime())))
                endAttempt(commandSent ? "Check ADT for the result." : "Phone unavailable. Tap Refresh.", commandSent);
            maybeTileTap(); render(); handler.postDelayed(this, 250);
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        client = Wearable.getMessageClient(this);
        sender = (node, path, payload, failure) -> client.sendMessage(node, path, payload)
            .addOnFailureListener(error -> handler.post(failure));
        controls = new AlarmToggleView(this, this::onAlarmTap, () -> {
            if (attempt == null) { feedback = null; WatchAlarmStore.refresh(this); render(); }
        });
        setContentView(controls);
        controls.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets edge = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            controls.setPadding(edge.left, edge.top, edge.right, edge.bottom); return insets;
        });
        preferences = getSharedPreferences(WatchAlarmStore.PREFERENCES, MODE_PRIVATE);
        readTileIntent(getIntent(), saved == null); render();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); readTileIntent(intent, true); maybeTileTap(); render();
    }
    private void readTileIntent(Intent intent, boolean freshLaunch) {
        tileAction = tileToken = null;
        if (intent == null) return;
        String action = intent.getStringExtra(AlarmTileService.EXTRA_TILE_ACTION);
        String token = intent.getStringExtra(AlarmTileService.EXTRA_TILE_REVISION);
        intent.removeExtra(AlarmTileService.EXTRA_TILE_ACTION); intent.removeExtra(AlarmTileService.EXTRA_TILE_REVISION);
        if (!freshLaunch || attempt != null || (intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return;
        if (AlarmTileService.ACTION_REFRESH.equals(action)) { WatchAlarmStore.refresh(this); return; }
        if (!(AlarmAction.ARM_STAY.name().equals(action) || AlarmAction.DISARM.name().equals(action))
                || !AlarmStateProtocol.uuid(token)) return;
        tileAction = action; tileToken = token; tileUntil = SystemClock.elapsedRealtime() + 10_000;
    }
    private void maybeTileTap() {
        if (tileAction == null) return;
        if (SystemClock.elapsedRealtime() >= tileUntil || !watchUnlocked()) { tileAction = tileToken = null; return; }
        if (!interactive()) return;
        String action = tileAction, token = tileToken; tileAction = tileToken = null;
        begin(AlarmAction.valueOf(action), token);
    }
    private boolean watchUnlocked() {
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        return keyguard != null && !keyguard.isDeviceLocked() && !keyguard.isKeyguardLocked();
    }
    private boolean interactive() { return resumed && hasWindowFocus() && listenerReady && watchUnlocked(); }
    private boolean current(ArmExperimentProtocol.Attempt expected) {
        return expected != null && attempt == expected && interactive() && expected.isActive(SystemClock.elapsedRealtime());
    }
    private void onAlarmTap(AlarmAction action) { begin(action, displayedToken); }
    private void begin(AlarmAction action, String token) {
        if (!interactive() || attempt != null || action == null) return;
        WatchAlarmStore.Selection chosen = WatchAlarmStore.consume(this, token, action);
        if (chosen == null) { feedback = "Status changed. Refreshing…"; WatchAlarmStore.refresh(this); render(); return; }
        selection = chosen; commandSent = false;
        ArmExperimentProtocol.Attempt started = new ArmExperimentProtocol.Attempt(action, SystemClock.elapsedRealtime());
        attempt = started;
        if (!started.selectTarget(chosen.phone, SystemClock.elapsedRealtime()) || started.prepare(SystemClock.elapsedRealtime()) == null) {
            endAttempt("Request expired. Tap Refresh.", false); return;
        }
        feedback = action == AlarmAction.DISARM ? "Disarming…" : "Arming Stay…"; render();
        send(chosen.phone, AlarmStateProtocol.TOGGLE_PATH,
            new AlarmStateProtocol.Tap(action, started.requestId(), chosen.stateRevision).encode(),
            () -> { if (current(started) && !commandSent) endAttempt("Phone unavailable. Tap Refresh.", false); });
    }
    private void handle(MessageEvent event, int expectedGeneration) {
        if (!resumed || generation != expectedGeneration || event == null) return;
        if (AlarmStateProtocol.STATE_PATH.equals(event.getPath())) { WatchAlarmStore.receive(this, event); return; }
        ArmExperimentProtocol.Attempt started = attempt;
        if (!current(started) || selection == null) return;
        long now = SystemClock.elapsedRealtime();
        if (ArmExperimentProtocol.CHALLENGE_PATH.equals(event.getPath())
                && started.acceptChallenge(event.getData(), event.getSourceNodeId(), now)) {
            if (!WatchAlarmStore.stillCurrent(this, selection)) { endAttempt("Status changed. No action sent.", false); return; }
            // The single user tap already authorized this exact action; there is no confirmation UI.
            byte[] commit = started.commit(now);
            if (commit == null) { endAttempt("Request expired. Tap Refresh.", false); return; }
            commandSent = true;
            send(selection.phone, ArmExperimentProtocol.COMMIT_PATH, commit,
                () -> { if (attempt == started) endAttempt("Check ADT for the result.", true); });
        } else if (ArmExperimentProtocol.RESULT_PATH.equals(event.getPath())
                && started.acceptResult(event.getData(), event.getSourceNodeId(), now)) {
            boolean requested = started.outcome() == ArmExperimentProtocol.Outcome.REQUESTED;
            endAttempt(requested ? "Waiting for ADT…" : "Request declined. Refreshing…", requested);
            WatchAlarmStore.refresh(this);
        }
    }
    private void send(String node, String path, byte[] payload, Runnable failure) {
        try { sender.send(node, path, payload, failure); } catch (RuntimeException e) { failure.run(); }
    }
    private void endAttempt(String message, boolean mayHaveSent) {
        if (attempt != null) attempt.cancel();
        attempt = null; selection = null; commandSent = false;
        feedback = message; WatchAlarmStore.finish(this, mayHaveSent); render();
    }
    private void render() {
        if (controls == null) return;
        WatchAlarmStore.ViewState state = WatchAlarmStore.read(this);
        displayedToken = state.revision;
        String detail = feedback == null ? state.detail : feedback;
        if (!watchUnlocked()) detail = "Unlock your watch.";
        controls.render(attempt != null ? "Sending request" : state.label, state.action,
            interactive() && attempt == null && state.enabled, detail);
        if (interactive() && attempt != null) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    @Override public void onResume() {
        super.onResume(); resumed = true; listenerReady = false;
        preferences.registerOnSharedPreferenceChangeListener(changed);
        final int g = ++generation;
        MessageClient.OnMessageReceivedListener added = event -> handler.post(() -> handle(event, g)); listener = added;
        client.addListener(added).addOnSuccessListener(unused -> {
            if (!resumed || generation != g) { client.removeListener(added); return; }
            listenerReady = true; maybeTileTap(); WatchAlarmStore.refresh(this); render();
        }).addOnFailureListener(error -> {
            if (resumed && generation == g) { feedback = "Connection unavailable. Reopen ADT Watch."; render(); }
        });
        handler.post(tick);
    }
    @Override public void onPause() {
        resumed = false; listenerReady = false; ++generation; tileAction = tileToken = null;
        handler.removeCallbacks(tick); preferences.unregisterOnSharedPreferenceChangeListener(changed);
        if (listener != null) client.removeListener(listener); listener = null;
        if (attempt != null) endAttempt(commandSent ? "Check ADT for the result." : "Request cancelled.", commandSent);
        super.onPause();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused && attempt != null) endAttempt(commandSent ? "Check ADT for the result." : "Request cancelled.", commandSent);
        if (focused) maybeTileTap(); render();
    }
    @Override public void dump(String prefix, FileDescriptor fd, PrintWriter out, String[] args) {
        super.dump(prefix, fd, out, args);
        out.println(prefix + "ADT watch helper diagnostics:");
        out.println(prefix + "  resumed=" + resumed + ", windowFocus=" + hasWindowFocus()
            + ", watchUnlocked=" + watchUnlocked() + ", listenerReady=" + listenerReady);
        out.println(prefix + "  attemptActive=" + (attempt != null) + ", commitSent=" + commandSent);
        WatchAlarmStore.ViewState state = WatchAlarmStore.read(this);
        out.println(prefix + "  state=" + state.label + ", action=" + state.action + ", enabled=" + state.enabled);
    }
}
