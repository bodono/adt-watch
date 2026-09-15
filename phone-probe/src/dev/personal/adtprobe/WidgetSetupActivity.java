package dev.personal.adtprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Normal, visible ADT widget setup. Contains no scene-execution or alarm-intent path. */
public final class WidgetSetupActivity extends Activity {
    // A namespace belonging only to this screen. Never delete a different app's/host's widgets.
    private AlarmAction action;
    // Main-thread bookkeeping prevents a temporary experiment from replacing this host's listener.
    private static int listeningHostCount;
    static boolean hasListeningHost() { return listeningHostCount > 0; }
    private static final ComponentName PROVIDER = new ComponentName("com.adtuk.adtukalarm",
        "com.alarm.alarmmobile.android.WidgetProvider");
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WidgetSetupState state;
    private SharedPreferences prefs;
    private AppWidgetManager manager;
    private AppWidgetHost host;
    private LinearLayout column;
    private TextView outcome;
    private TextView tapStatus;
    private Gate preview;
    private Button setup;
    private Button cancel;
    private Button enable;
    private Button remove;
    private AlertDialog enableDialog;
    private boolean resumed;
    private boolean listening;
    private boolean tapsEnabled;
    private boolean previewReady;
    private boolean continueConfiguration;
    private boolean failed;
    private long revision;
    private final Runnable lockCheck = new Runnable() {
        @Override public void run() {
            if (!tapsEnabled) return;
            if (!canInteract()) disableTaps();
            else handler.postDelayed(this, 250);
        }
    };

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(Color.WHITE);
        view.setPadding(0, dp(10), 0, dp(10)); column.addView(view); return view;
    }
    private Button button(String label, View.OnClickListener listener) {
        Button view = new Button(this);
        view.setText(label); view.setAllCaps(false); view.setMinHeight(dp(54));
        view.setOnClickListener(listener); column.addView(view); return view;
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        ArmExperimentService.cancelForNavigation(this);
        action = AlarmWidgetSlot.fromIntent(getIntent());
        if (action == null) { finish(); return; }
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
        text(action.label() + " widget setup", 28);
        text("A separate widget for " + action.label() + ". First review the " + action.widgetLabel() + " scene in ADT. "
            + "Review every scene action and option. A scene's name alone does not prove what it controls.", 17);
        text("Android will ask to host ADT's widget, then ADT will show its normal scene selection. "
            + "Setup and this app's preview do not request scene execution. Widget actions stay blocked until "
            + "you explicitly enable taps below.", 15);
        outcome = text("Checking the installed ADT widget…", 17);
        setup = button("Choose ADT scene widget…", view -> beginSetup());
        cancel = button("Cancel pending setup", view -> cancelSetup("Widget setup canceled. The previous widget is unchanged."));
        tapStatus = text("Widget actions are blocked.", 16);
        preview = new Gate(this);
        preview.setMinimumHeight(dp(120));
        preview.setBackgroundColor(Color.rgb(31, 39, 50));
        column.addView(preview, new LinearLayout.LayoutParams(-1, dp(150)));
        enable = button("Enable widget taps…", view -> confirmEnable());
        button("Block widget actions", view -> disableTaps());
        remove = button("Remove this hosted widget…", view -> confirmRemove());
        button("Done", view -> finish());
        try {
            prefs = getSharedPreferences(AlarmWidgetSlot.preferences(action), MODE_PRIVATE);
            revision = prefs.getLong("revision", 0);
            state = load();
            manager = AppWidgetManager.getInstance(this);
            host = new AppWidgetHost(this, AlarmWidgetSlot.hostId(action)) {
                @Override protected AppWidgetHostView onCreateView(Context context, int id, AppWidgetProviderInfo info) {
                    return new GuardedHostView(context);
                }
                @Override public void onAppWidgetRemoved(int id) {
                    super.onAppWidgetRemoved(id);
                    if (state == null || !state.owned.contains(id)) return;
                    disableTaps();
                    if (state.pending == id) state.cancel();
                    if (state.active == id) state.active = -1;
                    state.owned.remove(id);
                    if (save()) {
                        show("Android removed a widget owned by this helper.", "Widget host: owned widget removed by Android.");
                        render();
                    }
                }
            };
            // Recover allocations interrupted between Android's allocation and our preference write.
            // This dedicated host namespace contains only this helper screen's allocations.
            for (int id : host.getAppWidgetIds()) if (id > 0) state.owned.add(id);
            if (!save()) return;
            if (state.pending > 0 && (saved == null
                    || !state.token.equals(saved.getString("widget_flow", "")) || !fresh())) {
                cancelSetup("An interrupted or expired setup was canceled. The previous widget is unchanged.");
            }
            cleanUnused();
            render();
            if (installedProvider() == null)
                show("The expected ADT widget is unavailable. Install or enable ADT, then reopen this screen.",
                    "Widget host: expected provider unavailable.");
        } catch (Exception e) {
            fail("Widget setup could not initialize", e);
        }
        controls();
    }

    private WidgetSetupState load() {
        WidgetSetupState value = new WidgetSetupState();
        for (String id : prefs.getStringSet("owned", new HashSet<>())) {
            try { int parsed = Integer.parseInt(id); if (parsed > 0) value.owned.add(parsed); }
            catch (NumberFormatException ignored) { }
        }
        value.active = prefs.getInt("active", -1); value.pending = prefs.getInt("pending", -1);
        value.stage = prefs.getInt("stage", WidgetSetupState.NONE); value.request = prefs.getInt("request", 0);
        value.nextRequest = prefs.getInt("next_request", 1000); value.token = prefs.getString("token", "");
        value.elapsed = prefs.getLong("elapsed", 0); value.wall = prefs.getLong("wall", 0);
        if (!value.owned.contains(value.active)) value.active = -1;
        if (!value.owned.contains(value.pending) || value.pending == value.active) value.cancel();
        return value;
    }

    private boolean save() {
        if (!currentRevision()) {
            failed = true; disableTaps();
            show("Another setup screen changed the widget. Close this screen and reopen setup; actions remain blocked.",
                "Widget host: stale bookkeeping instance rejected.");
            controls();
            return false;
        }
        Set<String> ids = new HashSet<>();
        for (int id : state.owned) ids.add(Integer.toString(id));
        boolean committed = prefs.edit().putStringSet("owned", ids).putInt("active", state.active)
            .putInt("pending", state.pending).putInt("stage", state.stage).putInt("request", state.request)
            .putInt("next_request", state.nextRequest).putString("token", state.token)
            .putLong("elapsed", state.elapsed).putLong("wall", state.wall).putLong("revision", revision + 1).commit();
        if (!committed) {
            failed = true; disableTaps();
            show("Widget bookkeeping could not be saved. No further setup will start; close this screen.",
                "Widget host: private bookkeeping write failed.");
        }
        if (committed) revision++;
        return committed;
    }

    private boolean currentRevision() {
        return prefs != null && prefs.getLong("revision", 0) == revision;
    }

    private boolean fresh() { return state.fresh(SystemClock.elapsedRealtime(), System.currentTimeMillis()); }
    private boolean foregroundUnlocked() {
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        // Another helper Activity may receive onStop after this one resumes. Its shared
        // visibility flag is not evidence about this Activity's own foreground lifecycle.
        return resumed && !isFinishing() && !isDestroyed()
            && keyguard != null && !keyguard.isKeyguardLocked() && !keyguard.isDeviceLocked();
    }
    private boolean canInteract() {
        return tapsEnabled && previewReady && !failed && currentRevision()
            && state != null && state.pending <= 0 && foregroundUnlocked();
    }

    private AppWidgetProviderInfo installedProvider() {
        for (AppWidgetProviderInfo info : manager.getInstalledProvidersForProfile(Process.myUserHandle()))
            if (allowed(info)) return info;
        return null;
    }
    private boolean allowed(AppWidgetProviderInfo info) {
        return info != null && PROVIDER.equals(info.provider) && Process.myUserHandle().equals(info.getProfile());
    }
    private boolean hostOwns(int id) {
        if (state == null || !state.owned.contains(id)) return false;
        for (int owned : host.getAppWidgetIds()) if (owned == id) return true;
        return false;
    }
    private AppWidgetProviderInfo verified(int id) {
        try {
            if (id <= 0 || installedProvider() == null || !hostOwns(id)) return null;
            AppWidgetProviderInfo info = manager.getAppWidgetInfo(id);
            return allowed(info) ? info : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void beginSetup() {
        if (failed || !foregroundUnlocked() || state == null || state.pending > 0) return;
        disableTaps();
        int allocated = -1;
        try {
            AppWidgetProviderInfo provider = installedProvider();
            if (provider == null) throw new IllegalStateException("Expected widget provider unavailable");
            allocated = host.allocateAppWidgetId();
            state.begin(allocated, UUID.randomUUID().toString(), SystemClock.elapsedRealtime(), System.currentTimeMillis());
            if (!save()) {
                // This allocation came directly from this host; never use a returned foreign ID here.
                host.deleteAppWidgetId(allocated);
                return;
            }
            Bundle options = new Bundle();
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 240);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 320);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 100);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 150);
            Probe.event(this, "Widget host: user began native widget setup; previous widget retained.");
            if (manager.bindAppWidgetIdIfAllowed(allocated, provider.getProfile(), PROVIDER, options)) {
                if (verified(allocated) == null || !fresh()) throw new IllegalStateException("Widget bind verification failed");
                state.boundDirectly();
                if (save()) configure();
            } else {
                int request = state.expect(WidgetSetupState.BIND);
                if (!save()) return;
                Intent consent = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, allocated)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, PROVIDER)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, provider.getProfile())
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, options);
                startActivityForResult(consent, request);
                outcome.setText("Complete Android's widget permission, then choose the reviewed scene in ADT.");
            }
        } catch (Exception e) {
            cancelSetup("Widget setup could not start. The previous widget is unchanged.");
            Probe.event(this, "Widget host: setup failed (" + e.getClass().getSimpleName() + ").");
        }
        controls();
    }

    private void configure() {
        if (failed || !foregroundUnlocked() || state.pending <= 0 || state.stage != WidgetSetupState.READY) return;
        disableTaps();
        try {
            AppWidgetProviderInfo info = verified(state.pending);
            if (!fresh() || info == null || info.configure == null
                    || !PROVIDER.getPackageName().equals(info.configure.getPackageName()))
                throw new IllegalStateException("Expected widget configuration unavailable");
            int request = state.expect(WidgetSetupState.CONFIGURE);
            if (!save()) return;
            host.startAppWidgetConfigureActivityForResult(this, state.pending, 0, request, null);
            outcome.setText("Choose only the scene whose complete contents you reviewed in ADT. Cancel if unsure.");
        } catch (Exception e) {
            cancelSetup("ADT widget configuration could not open. The previous widget is unchanged.");
            Probe.event(this, "Widget host: configuration failed (" + e.getClass().getSimpleName() + ").");
        }
        controls();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (failed || state == null || state.pending <= 0) {
            Probe.event(this, "Widget host: ignored a result without a pending setup.");
            return;
        }
        disableTaps();
        try {
            int returned = data == null ? -1 : data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
            if (result != RESULT_OK || !state.accept(request, returned, SystemClock.elapsedRealtime(), System.currentTimeMillis())
                    || verified(state.pending) == null) {
                cancelSetup("Widget setup canceled or its result could not be verified. The previous widget is unchanged.");
                return;
            }
            if (!save()) return;
            if (state.stage == WidgetSetupState.READY) {
                continueConfiguration = true;
                outcome.setText("Widget permission verified. Continue to ADT's scene selection.");
            } else if (state.stage == WidgetSetupState.CONFIGURED) {
                int old = state.commit(SystemClock.elapsedRealtime(), System.currentTimeMillis());
                if (!save()) return; // Never remove the old allocation before the replacement is durable.
                deleteUnused(old);
                show("ADT widget configuration verified. Scene contents and alarm state remain unverified; actions are blocked.",
                    "Widget host: configuration result verified; active widget count=1. Actions remain blocked.");
                render();
            }
        } catch (Exception e) {
            cancelSetup("Widget result could not be verified. The previous widget is unchanged.");
            Probe.event(this, "Widget host: result rejected (" + e.getClass().getSimpleName() + ").");
        }
        controls();
    }

    private void cancelSetup(String message) {
        disableTaps(); continueConfiguration = false;
        if (state == null || prefs == null || host == null || failed) return;
        int id = state.cancel();
        if (save()) deleteUnused(id);
        show(message, "Widget host: pending setup canceled; no scene execution requested.");
        controls();
    }

    private void cleanUnused() {
        if (failed) return;
        for (int id : new ArrayList<>(state.owned)) deleteUnused(id);
    }
    private void deleteUnused(int id) {
        if (failed || !currentRevision() || !state.mayDelete(id)) return;
        try {
            if (hostOwns(id)) host.deleteAppWidgetId(id);
            state.owned.remove(id);
            save();
        } catch (Exception e) {
            Probe.event(this, "Widget host: own unused allocation cleanup deferred (" + e.getClass().getSimpleName() + ").");
        }
    }

    private void confirmRemove() {
        if (!foregroundUnlocked() || failed || state == null || state.active <= 0) return;
        disableTaps();
        final int selected = state.active;
        new AlertDialog.Builder(this).setTitle("Remove this helper's widget?")
            .setMessage("This removes only the selected widget hosted by ADT Watch Setup. It does not edit or delete the scene in ADT, "
                + "change the alarm, or remove widgets on your Home screen.")
            .setNegativeButton("Keep widget", null)
            .setPositiveButton("Remove widget", (dialog, which) -> {
                if (!foregroundUnlocked() || failed || state.active != selected) return;
                state.active = -1;
                if (save()) {
                    deleteUnused(selected);
                    show("Hosted widget removed. The scene in ADT is unchanged.", "Widget host: user removed own active widget.");
                    render();
                }
            }).show();
    }

    private void confirmEnable() {
        if (!foregroundUnlocked() || failed || state == null || state.pending > 0 || verified(state.active) == null) return;
        disableTaps();
        enableDialog = new AlertDialog.Builder(this).setTitle("Enable actual widget actions?")
            .setMessage("A physical tap on ADT's widget can execute the WHOLE selected scene, including any alarm, locks, "
                + "doors, lights or other actions in it. A scene name is not proof of its contents.\n\nOnly continue after "
                + "reviewing every action and arming option in ADT, and only when you intend those changes. This helper "
                + "cannot verify the scene contents or final alarm state. Nothing runs merely by enabling taps. "
                + "Leaving this screen or locking the phone blocks further taps; it cannot cancel a scene already started.")
            .setNegativeButton("Keep actions blocked", null)
            .setPositiveButton("I reviewed the scene — enable taps", (dialog, which) -> {
                if (!foregroundUnlocked() || failed || state.pending > 0 || verified(state.active) == null) return;
                tapsEnabled = true;
                preview.guardTree();
                tapStatus.setText("Widget actions enabled while this screen stays open. A tap can run the whole scene.");
                tapStatus.setTextColor(Color.rgb(136, 218, 176));
                handler.post(lockCheck);
                Probe.event(this, "Widget host: user enabled foreground widget interaction; no action invoked by helper.");
            }).create();
        enableDialog.setOnDismissListener(dialog -> enableDialog = null);
        enableDialog.show();
    }

    private void disableTaps() {
        tapsEnabled = false;
        handler.removeCallbacks(lockCheck);
        if (preview != null) preview.guardTree();
        if (tapStatus != null) {
            tapStatus.setText("Widget actions are blocked.");
            tapStatus.setTextColor(Color.WHITE);
        }
    }

    private void render() {
        disableTaps();
        previewReady = false;
        if (preview == null || state == null || host == null || failed) return;
        preview.removeAllViews();
        try {
            AppWidgetProviderInfo info = verified(state.active);
            if (info == null) {
                TextView empty = new TextView(this); empty.setTextColor(Color.LTGRAY); empty.setPadding(dp(12), dp(12), dp(12), dp(12));
                empty.setText(state.active > 0 ? "The saved widget is unavailable. You can replace or remove it." : "No scene widget configured in this helper.");
                preview.addView(empty);
            } else {
                AppWidgetHostView view = host.createView(this, state.active, info);
                preview.addView(view, new FrameLayout.LayoutParams(-1, -1));
                // ADT configuration resets its own layout to compact. An explicit options update
                // is necessary even when these dimensions already appeared in the bind request.
                Bundle wide = new Bundle();
                wide.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 240);
                wide.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 320);
                wide.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 100);
                wide.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 150);
                manager.updateAppWidgetOptions(state.active, wide);
                previewReady = true;
                if (state.pending <= 0) outcome.setText("ADT widget present. Scene contents and alarm state are unverified; actions are blocked.");
            }
            preview.guardTree();
        } catch (Exception e) {
            previewReady = false;
            show("The hosted widget could not be displayed. Its actions remain blocked.",
                "Widget host: preview unavailable (" + e.getClass().getSimpleName() + ").");
        }
        controls();
    }

    private void controls() {
        if (setup == null) return;
        boolean available = !failed && state != null && host != null && manager != null;
        setup.setEnabled(available && (state.pending <= 0 || state.stage == WidgetSetupState.READY));
        setup.setText(available && state.stage == WidgetSetupState.READY ? "Continue ADT widget setup…"
            : available && state.active > 0 ? "Replace ADT scene widget…" : "Choose ADT scene widget…");
        setup.setOnClickListener(view -> { if (state != null && state.stage == WidgetSetupState.READY) configure(); else beginSetup(); });
        cancel.setEnabled(available && state.pending > 0);
        enable.setEnabled(available && state.active > 0 && state.pending <= 0 && previewReady);
        remove.setEnabled(available && state.active > 0 && state.pending <= 0);
    }
    private void show(String message, String event) { if (outcome != null) outcome.setText(message); Probe.event(this, event); }
    private void fail(String message, Exception e) {
        failed = true; disableTaps();
        show(message + ". Close and reopen this screen; widget actions remain blocked.",
            "Widget host: unavailable (" + e.getClass().getSimpleName() + ").");
        controls();
    }

    @Override public void onStart() {
        super.onStart(); Probe.activityVisible = true;
        ArmExperimentService.cancelForNavigation(this);
        if (host != null && !failed) {
            try { host.startListening(); if (!listening) listeningHostCount++; listening = true; }
            catch (Exception e) { fail("Widget updates could not start", e); }
        }
    }
    @Override public void onResume() {
        super.onResume(); resumed = true; disableTaps();
        if (!failed && state != null && state.pending > 0 && !fresh())
            cancelSetup("Widget setup expired. The previous widget is unchanged.");
    }
    @Override public void onPostResume() {
        super.onPostResume();
        if (continueConfiguration) { continueConfiguration = false; configure(); }
    }
    @Override public void onPause() {
        resumed = false; disableTaps();
        if (enableDialog != null) enableDialog.dismiss();
        super.onPause();
    }
    @Override public void onStop() {
        disableTaps(); Probe.activityVisible = false;
        if (listening) {
            try { host.stopListening(); }
            catch (Exception e) { Probe.event(this, "Widget host: stopping updates reported an error."); }
            listening = false;
            listeningHostCount--;
        }
        super.onStop();
    }
    @Override public void onSaveInstanceState(Bundle saved) {
        if (state != null) saved.putString("widget_flow", state.token);
        super.onSaveInstanceState(saved);
    }
    @Override public void onDestroy() {
        disableTaps();
        if (isFinishing() && state != null && state.pending > 0 && !failed)
            cancelSetup("Widget setup canceled when this screen closed. The previous widget is unchanged.");
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** Blocks input before it reaches RemoteViews, including keyboard/mouse/accessibility paths. */
    private final class Gate extends FrameLayout {
        private final View.AccessibilityDelegate delegate = new View.AccessibilityDelegate() {
            @Override public boolean performAccessibilityAction(View view, int action, Bundle arguments) {
                return canInteract() && super.performAccessibilityAction(view, action, arguments);
            }
        };
        Gate(Context context) { super(context); guardTree(); }
        void guardTree() {
            boolean allowed = canInteract();
            setDescendantFocusability(allowed ? FOCUS_AFTER_DESCENDANTS : FOCUS_BLOCK_DESCENDANTS);
            setImportantForAccessibility(allowed ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            if (!allowed) { clearFocus(); cancelPendingInputEvents(); }
            for (int i = 0; i < getChildCount(); i++) guard(getChildAt(i));
        }
        private void guard(View child) {
            child.setAccessibilityDelegate(delegate);
            if (!canInteract()) {
                child.clearFocus(); child.setPressed(false); child.cancelPendingInputEvents();
            }
            if (child instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) child;
                for (int i = 0; i < group.getChildCount(); i++) guard(group.getChildAt(i));
            }
        }
        @Override public boolean dispatchTouchEvent(MotionEvent event) { return !canInteract() || super.dispatchTouchEvent(event); }
        @Override public boolean dispatchKeyEvent(KeyEvent event) { return !canInteract() || super.dispatchKeyEvent(event); }
        @Override public boolean dispatchKeyShortcutEvent(KeyEvent event) { return !canInteract() || super.dispatchKeyShortcutEvent(event); }
        @Override public boolean dispatchGenericMotionEvent(MotionEvent event) { return !canInteract() || super.dispatchGenericMotionEvent(event); }
        @Override public boolean dispatchHoverEvent(MotionEvent event) { return !canInteract() || super.dispatchHoverEvent(event); }
        @Override public boolean requestSendAccessibilityEvent(View child, AccessibilityEvent event) {
            return canInteract() && super.requestSendAccessibilityEvent(child, event);
        }
        @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
            return canInteract() && super.performAccessibilityAction(action, arguments);
        }
        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom); guardTree();
        }
    }

    private final class GuardedHostView extends AppWidgetHostView {
        GuardedHostView(Context context) { super(context); }
        @Override public void updateAppWidget(RemoteViews views) {
            super.updateAppWidget(views);
            if (preview != null) preview.guardTree();
        }
        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            if (preview != null) preview.guardTree();
        }
    }
}
