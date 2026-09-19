package dev.personal.adtprobe;

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Looper;
import android.os.Process;
import android.text.method.TransformationMethod;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RemoteViews;
import android.widget.TextView;
import java.util.Collections;
import java.util.function.BooleanSupplier;

/**
 * One offscreen session for the user's already configured, reviewed widget for one fixed action.
 * All calls and updates must run on the main thread. The caller MUST first stop the setup
 * Activity's host, enforce exclusive host ownership, and close this session before reopening it.
 * This class does not authorize an alarm action: a service must independently enforce explicit
 * reviewed setup or short-lived phone preparation, fresh watch confirmation/source, and phone locks.
 * It never allocates/deletes/binds widgets, reads a PendingIntent, or retries an activation.
 * An attempted activation means only that the native View's click was invoked.
 */
final class WidgetHostSession implements AutoCloseable {
    private static final ComponentName PROVIDER = new ComponentName("com.adtuk.adtukalarm",
        "com.alarm.alarmmobile.android.WidgetProvider");
    private static final long REVIEWED_VERSION = 2307;
    private final Context context;
    private final AlarmAction action;
    private final Binding binding;
    private AppWidgetHost host;
    private AppWidgetHostView view;
    private Snapshot snapshot;
    private RemoteViews lastViews;
    private long generation;
    private boolean started, closed, listening, rendering, consumed, broken;
    private String status = "Widget host has not started.";

    WidgetHostSession(Context context, AlarmAction action) {
        this(context, action, new InstalledBinding(context.getApplicationContext(), action));
    }

    // Dependency seam for inert framework-view tests. Production uses InstalledBinding only.
    WidgetHostSession(Context context, AlarmAction action, Binding binding) {
        if (action == null) throw new IllegalArgumentException("An action is required");
        this.context = context.getApplicationContext();
        this.action = action;
        this.binding = binding;
    }

    void start() {
        mainThread();
        if (started || closed) return;
        started = true;
        if (WidgetSetupActivity.hasListeningHost()) {
            invalidate("Widget setup already owns the host; activation is blocked.", true);
            return;
        }
        try {
            snapshot = binding.snapshot();
            host = new AppWidgetHost(context, AlarmWidgetSlot.hostId(action)) {
                @Override protected AppWidgetHostView onCreateView(Context ignored, int id,
                        AppWidgetProviderInfo info) {
                    return new ObservedHostView(context);
                }
                @Override public void onAppWidgetRemoved(int id) {
                    if (snapshot != null && id == snapshot.id) invalidate("Widget was removed.", true);
                    super.onAppWidgetRemoved(id);
                }
                @Override protected void onProviderChanged(int id, AppWidgetProviderInfo info) {
                    if (snapshot != null && id == snapshot.id) {
                        // A cached metadata refresh at startListening is not itself a version change.
                        // Invalidate its render, and permit only a later normal update if identity still matches.
                        boolean identityValid = InstalledBinding.allowed(info) && validBinding();
                        invalidate("Widget provider metadata refreshed; waiting for a new idle render.", !identityValid);
                    }
                    super.onProviderChanged(id, info);
                }
                @Override protected void onProvidersChanged() {
                    if (!validBinding()) invalidate("Widget provider is no longer verified.", true);
                    super.onProvidersChanged();
                }
            };
            if (!validBinding()) {
                invalidate("The reviewed widget binding is unavailable.", true);
                return;
            }
            // createView can synchronously deliver cached RemoteViews. No window is attached.
            view = host.createView(context, snapshot.id, snapshot.info);
            if (!(view instanceof ObservedHostView)) {
                invalidate("The widget host could not observe rendering.", true);
                return;
            }
            host.startListening();
            listening = true;
            Bundle options = new Bundle();
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 240);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 320);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 100);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 150);
            // ADT initially configures a compact widget. This asks its normal provider for wide UI.
            AppWidgetManager.getInstance(context).updateAppWidgetOptions(snapshot.id, options);
            measure(view);
            isReady();
        } catch (Exception ignored) {
            invalidate("Widget hosting failed; activation is blocked.", true);
            stopListening();
        }
    }

    boolean isReady() {
        mainThread();
        if (closed || !started || broken || consumed || rendering || !listening) return false;
        if (WidgetSetupActivity.hasListeningHost()) {
            invalidate("Widget setup took ownership; activation is blocked.", true);
            return false;
        }
        if (!validBinding()) {
            invalidate("Widget setup or provider changed; activation is blocked.", true);
            return false;
        }
        boolean ready = target() != null;
        status = ready ? "Reviewed " + action.label() + " widget is idle; authorization is still required."
            : "Waiting for the reviewed idle " + action.label() + " widget; activation is blocked.";
        return ready;
    }

    String status() { mainThread(); isReady(); return status; }
    long renderGeneration() { mainThread(); return generation; }

    /** Read-only condition summary: no provider/package names, widget IDs, scene text or intents. */
    String diagnostics() {
        mainThread();
        Contract contract = snapshot == null ? null : snapshot.contract;
        View root = diagnosticView(contract == null ? 0 : contract.root);
        View label = diagnosticView(contract == null ? 0 : contract.label);
        View scene = diagnosticView(contract == null ? 0 : contract.scene);
        View cancel = diagnosticView(contract == null ? 0 : contract.cancel);
        View container = diagnosticView(contract == null ? 0 : contract.container);
        View progress = diagnosticView(contract == null ? 0 : contract.progress);
        View ring = diagnosticView(contract == null ? 0 : contract.ring);
        boolean packageMatches = contract != null && lastViews != null
            && contract.packageName.equals(lastViews.getPackage());
        boolean layoutMatches = contract != null && lastViews != null
            && contract.layout == lastViews.getLayoutId();
        boolean labelMatches = reviewedLabelMatches(label, action.widgetLabel());
        // Do not call isReady(): diagnostics must not invalidate or otherwise change the session.
        return "started=" + started + " listening=" + listening + " closed=" + closed
            + " broken=" + broken + " consumed=" + consumed + " rendering=" + rendering
            + " setupHostListening=" + WidgetSetupActivity.hasListeningHost()
            + " bindingVerified=" + validBinding() + " remoteViewsPresent=" + (lastViews != null)
            + " packageMatches=" + packageMatches + " layoutMatches=" + layoutMatches
            + " labelMatches=" + labelMatches
            + " rootClickable=" + (root != null && root.isClickable())
            + " rootEnabled=" + (root != null && root.isEnabled())
            + " rootHasListener=" + (root != null && root.hasOnClickListeners())
            + diagnosticVisibility("root", root) + diagnosticVisibility("label", label)
            + diagnosticVisibility("scene", scene) + " sceneImageType=" + (scene instanceof ImageView)
            + diagnosticVisibility("cancel", cancel) + diagnosticVisibility("container", container)
            + " containerGroupType=" + (container instanceof ViewGroup)
            + diagnosticVisibility("progress", progress) + diagnosticProgress("progress", progress)
            + diagnosticVisibility("ring", ring) + diagnosticProgress("ring", ring);
    }

    private View diagnosticView(int id) { return view == null ? null : unique(view, id); }

    private String diagnosticVisibility(String name, View candidate) {
        String visibility = "missing_or_duplicate";
        if (candidate != null) {
            switch (candidate.getVisibility()) {
                case View.VISIBLE: visibility = "visible"; break;
                case View.INVISIBLE: visibility = "invisible"; break;
                case View.GONE: visibility = "gone"; break;
                default: visibility = "unknown";
            }
        }
        return " " + name + "Visibility=" + visibility + " " + name + "VisibleAndEnabledWithinHost="
            + (candidate != null && visibleWithin(candidate, view));
    }

    private static String diagnosticProgress(String name, View candidate) {
        if (!(candidate instanceof ProgressBar)) return " " + name + "ProgressBarType=false";
        ProgressBar bar = (ProgressBar) candidate;
        return " " + name + "ProgressBarType=true " + name + "Value=" + bar.getProgress()
            + " " + name + "Max=" + bar.getMax() + " " + name + "Indeterminate=" + bar.isIndeterminate();
    }

    enum Activation { NOT_ATTEMPTED, ATTEMPTED }

    /** Compatibility for inert callers; true includes an uncertain native click result. */
    boolean activateOnce(long expectedGeneration) {
        return activateOnce(expectedGeneration, () -> true) == Activation.ATTEMPTED;
    }

    /**
     * External authorization/lock/source/expiry guards are mandatory. The synchronous
     * beforeClick callback persists the command only after the final host checks.
     */
    Activation activateOnce(long expectedGeneration, BooleanSupplier beforeClick) {
        mainThread();
        if (expectedGeneration != generation || !isReady()) return Activation.NOT_ATTEMPTED;
        View target = target();
        if (target == null || expectedGeneration != generation) return Activation.NOT_ATTEMPTED;
        // Reserve the one-shot before the callback too: reentrancy cannot attempt a second click.
        consumed = true;
        if (!beforeClick.getAsBoolean()) {
            status = "Widget activation blocked before native click; no request was attempted.";
            return Activation.NOT_ATTEMPTED;
        }
        status = "Widget activation attempted; alarm state is unverified.";
        try { target.performClick(); }
        catch (RuntimeException ignored) { /* Foreign code may already have submitted its action. */ }
        return Activation.ATTEMPTED;
    }

    @Override public void close() {
        mainThread();
        if (closed) return;
        closed = true;
        generation++;
        lastViews = null;
        stopListening();
        view = null;
        status = "Widget host closed; activation is blocked.";
    }

    private void stopListening() {
        if (host == null || !listening) return;
        listening = false;
        try { host.stopListening(); } catch (RuntimeException ignored) { }
    }

    private boolean validBinding() {
        try { return snapshot != null && host != null && binding.valid(snapshot, host); }
        catch (RuntimeException ignored) { return false; }
    }

    private View target() {
        if (snapshot == null || view == null || lastViews == null) return null;
        Contract contract = snapshot.contract;
        if (!contract.packageName.equals(lastViews.getPackage())
                || lastViews.getLayoutId() != contract.layout) return null;
        return idleTarget(view, contract, action);
    }

    private void invalidate(String reason, boolean terminal) {
        mainThread();
        generation++;
        lastViews = null;
        broken |= terminal;
        if (!closed && !consumed) status = reason;
    }

    private void measure(View widget) {
        int width = Math.round(280 * context.getResources().getDisplayMetrics().density);
        int height = Math.round(150 * context.getResources().getDisplayMetrics().density);
        widget.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        widget.layout(0, 0, width, height);
    }

    private final class ObservedHostView extends AppWidgetHostView {
        ObservedHostView(Context context) { super(context); }
        @Override public void updateAppWidget(RemoteViews remoteViews) {
            mainThread();
            WidgetHostSession.this.invalidate("Widget updated; the previous render is no longer eligible.", false);
            if (closed || broken) return;
            rendering = true;
            try {
                // Default synchronous apply/reapply, no executor: validation observes this update.
                super.updateAppWidget(remoteViews);
                WidgetHostSession.this.measure(this);
                lastViews = remoteViews;
            } catch (RuntimeException ignored) {
                lastViews = null;
            } finally { rendering = false; }
        }
    }

    static View idleTarget(View tree, Contract ids, AlarmAction action) {
        if (action == null) return null;
        View root = unique(tree, ids.root), label = unique(tree, ids.label);
        View scene = unique(tree, ids.scene), cancel = unique(tree, ids.cancel);
        View container = unique(tree, ids.container), progress = unique(tree, ids.progress);
        View ring = unique(tree, ids.ring);
        if (root == null || !root.isEnabled() || !root.isClickable() || !root.hasOnClickListeners()
                || !visibleWithin(root, tree) || !reviewedLabelMatches(label, action.widgetLabel())
                || !visibleWithin(label, tree) || !(scene instanceof ImageView)
                || !visibleWithin(scene, tree) || cancel == null || cancel.getVisibility() != View.GONE
                || !(container instanceof ViewGroup) || !visibleWithin(container, tree)
                || !(progress instanceof ProgressBar) || !visibleWithin(progress, tree)
                || !(ring instanceof ProgressBar) || !visibleWithin(ring, tree)) return null;
        ProgressBar full = (ProgressBar) progress, countdown = (ProgressBar) ring;
        if (full.isIndeterminate() || full.getMax() != 1 || full.getProgress() != 1
                || countdown.isIndeterminate() || countdown.getProgress() != 0) return null;
        // Ring max is deliberately unconstrained: fresh XML and a completed countdown differ.
        return root;
    }

    private static boolean reviewedLabelMatches(View label, String expected) {
        if (!(label instanceof TextView)) return false;
        TextView text = (TextView) label;
        try {
            CharSequence displayed = text.getText();
            TransformationMethod transformation = text.getTransformationMethod();
            // Compare the exact public rendered label the user reviewed. ADT can retain a mixed-case
            // scene name while its native TextView displays all caps; do not broadly case-fold names.
            if (transformation != null) displayed = transformation.getTransformation(displayed, text);
            return displayed != null && expected.contentEquals(displayed);
        } catch (RuntimeException ignored) { return false; }
    }

    private static boolean visibleWithin(View view, View tree) {
        for (View current = view; current != null; ) {
            if (current.getVisibility() != View.VISIBLE || !current.isEnabled()) return false;
            if (current == tree) return true;
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return false; // Window attachment/isShown would incorrectly reject intentional offscreen UI.
    }

    private static View unique(View tree, int id) {
        if (id <= 0 || count(tree, id) != 1) return null;
        return tree.findViewById(id);
    }

    private static int count(View tree, int id) {
        int found = tree.getId() == id ? 1 : 0;
        if (tree instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) tree;
            for (int i = 0; i < group.getChildCount(); i++) {
                found += count(group.getChildAt(i), id);
                if (found > 1) return found;
            }
        }
        return found;
    }

    private static void mainThread() {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("Widget hosting must run on the main thread");
    }

    interface Binding {
        Snapshot snapshot() throws Exception;
        boolean valid(Snapshot snapshot, AppWidgetHost host);
    }

    static final class Snapshot {
        final int id;
        final long revision;
        final AppWidgetProviderInfo info;
        final Contract contract;
        Snapshot(int id, long revision, AppWidgetProviderInfo info, Contract contract) {
            this.id = id; this.revision = revision; this.info = info; this.contract = contract;
        }
    }

    static final class Contract {
        final String packageName;
        final int layout, root, label, scene, cancel, container, progress, ring;
        Contract(String packageName, int layout, int root, int label, int scene, int cancel,
                int container, int progress, int ring) {
            this.packageName = packageName; this.layout = layout; this.root = root; this.label = label;
            this.scene = scene; this.cancel = cancel; this.container = container;
            this.progress = progress; this.ring = ring;
        }
    }

    private static final class InstalledBinding implements Binding {
        final Context context;
        final SharedPreferences prefs;
        final AppWidgetManager manager;
        InstalledBinding(Context context, AlarmAction action) {
            this.context = context;
            prefs = context.getSharedPreferences(AlarmWidgetSlot.preferences(action), Context.MODE_PRIVATE);
            manager = AppWidgetManager.getInstance(context);
        }
        @Override public Snapshot snapshot() throws Exception {
            int id = prefs.getInt("active", -1);
            long revision = prefs.getLong("revision", 0);
            Resources resources = context.getPackageManager().getResourcesForApplication(PROVIDER.getPackageName());
            Contract contract = new Contract(PROVIDER.getPackageName(), resource(resources, "layout", "widget_icon"),
                resource(resources, "id", "widget_layout"), resource(resources, "id", "widget_text"),
                resource(resources, "id", "widget_scene_image"), resource(resources, "id", "cancel_widget_request"),
                resource(resources, "id", "progress_bar_container"), resource(resources, "id", "widget_progress_bar"),
                resource(resources, "id", "widget_progress_bar_ring"));
            return new Snapshot(id, revision, manager.getAppWidgetInfo(id), contract);
        }
        @Override public boolean valid(Snapshot snapshot, AppWidgetHost host) {
            try {
                if (snapshot.id <= 0 || prefs.getLong("revision", 0) != snapshot.revision
                        || prefs.getInt("active", -1) != snapshot.id || prefs.getInt("pending", -1) > 0
                        || !prefs.getStringSet("owned", Collections.emptySet()).contains(Integer.toString(snapshot.id)))
                    return false;
                PackageInfo installed = context.getPackageManager().getPackageInfo(PROVIDER.getPackageName(), 0);
                if (installed.getLongVersionCode() != REVIEWED_VERSION || installed.applicationInfo == null
                        || !installed.applicationInfo.enabled) return false;
                boolean owned = false, available = false;
                for (int id : host.getAppWidgetIds()) if (id == snapshot.id) owned = true;
                for (AppWidgetProviderInfo info : manager.getInstalledProvidersForProfile(Process.myUserHandle()))
                    if (allowed(info)) available = true;
                return owned && available && allowed(manager.getAppWidgetInfo(snapshot.id));
            } catch (Exception ignored) { return false; }
        }
        private static boolean allowed(AppWidgetProviderInfo info) {
            return info != null && PROVIDER.equals(info.provider) && Process.myUserHandle().equals(info.getProfile());
        }
        private static int resource(Resources resources, String type, String name) {
            int value = resources.getIdentifier(name, type, PROVIDER.getPackageName());
            if (value == 0) throw new IllegalStateException("Reviewed widget resources unavailable");
            return value;
        }
    }
}
