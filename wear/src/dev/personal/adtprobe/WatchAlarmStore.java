package dev.personal.adtprobe;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.annotation.Nullable;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import java.util.Set;
import java.util.UUID;

/** Reported ADT state only: this class has no alarm-command transport. */
final class WatchAlarmStore {
    static final String PREFERENCES = "watch_alarm_state";
    private static final long QUERY_MS = 10_000;
    private static final long THROTTLE_MS = 2_000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Query pending;
    private static long lastRefresh = -1;
    private static boolean refreshQueued;
    private static Selection activeSelection;

    static final class ViewState {
        final String label, detail, revision;
        final AlarmAction action;
        final boolean enabled;
        ViewState(String label, String detail, String revision, @Nullable AlarmAction action, boolean enabled) {
            this.label = label; this.detail = detail; this.revision = revision;
            this.action = action; this.enabled = enabled;
        }
    }

    static final class Selection {
        final String phone, stateRevision;
        final AlarmAction action;
        Selection(String phone, String stateRevision, AlarmAction action) {
            this.phone = phone; this.stateRevision = stateRevision; this.action = action;
        }
    }

    /** Query is process-local; neither a nonce nor an uncompleted request is persisted. */
    static final class Query {
        final String nonce = UUID.randomUUID().toString();
        final long started;
        String phone;
        Query(long started) { this.started = started; }
        boolean live(long now) { return now >= started && now - started < QUERY_MS; }
    }

    private WatchAlarmStore() { }

    static synchronized ViewState read(Context context) {
        return readAt(context, SystemClock.elapsedRealtime(), boot(context));
    }

    static synchronized ViewState readAt(Context context, long now, int boot) {
        SharedPreferences p = prefs(context);
        recoverPossibleSend(p);
        if (p.getBoolean("busy", false)) return neutral("Checking alarm", p.getBoolean("awaiting", false)
                ? "Waiting for a new ADT update" : "Request in progress");
        if (!trusted(p, boot) || !fresh(p, now)) return neutral("State unknown", "Tap Refresh to contact your phone");
        AlarmStateProtocol.Availability availability = availability(p);
        if (availability != AlarmStateProtocol.Availability.READY) return neutral("State unknown", detail(availability));
        long age = age(p, now);
        AlarmStateProtocol.State state = state(p);
        AlarmAction action = AlarmStateProtocol.action(state);
        if (action == null || age > AlarmStateProtocol.MAX_STATE_AGE_MS
                || !AlarmStateProtocol.uuid(p.getString("revision", "")))
            return neutral("State unknown", "An up-to-date ADT report is needed");
        String token = p.getString("token", "");
        long issued = p.getLong("tokenIssued", -1);
        if (!AlarmStateProtocol.uuid(token) || issued < 0 || now < issued
                || now - issued >= AlarmStateProtocol.LINK_FRESH_MS) {
            token = UUID.randomUUID().toString();
            p.edit().putString("token", token).putLong("tokenIssued", now).apply();
        }
        String label = state == AlarmStateProtocol.State.DISARMED ? "Disarmed"
                : state == AlarmStateProtocol.State.ARMED_STAY ? "Armed Stay" : "Armed Away";
        return new ViewState(label, ageLabel(age), token, action, true);
    }

    static void refresh(Context context) {
        Context app = context.getApplicationContext();
        MAIN.post(() -> startRefresh(app));
    }

    private static void startRefresh(Context app) {
            Query query = beginQuery(SystemClock.elapsedRealtime());
            if (query == null) {
                synchronized (WatchAlarmStore.class) {
                    long now = SystemClock.elapsedRealtime();
                    if (pending != null && pending.live(now) || refreshQueued) return;
                    refreshQueued = true;
                    long delay = lastRefresh >= 0 && now >= lastRefresh ? Math.max(1, THROTTLE_MS - (now - lastRefresh)) : 1;
                    MAIN.postDelayed(() -> {
                        synchronized (WatchAlarmStore.class) { refreshQueued = false; }
                        startRefresh(app);
                    }, delay);
                }
                return;
            }
            MAIN.postDelayed(() -> failQuery(app, query), QUERY_MS);
            try {
                Wearable.getCapabilityClient(app).getCapability(WatchProtocol.PHONE_CAPABILITY,
                        CapabilityClient.FILTER_REACHABLE).addOnCompleteListener(task -> {
                    if (!task.isSuccessful() || task.getResult() == null) { failQuery(app, query); return; }
                    Set<Node> nodes = task.getResult().getNodes();
                    if (nodes == null || nodes.size() != 1) { failQuery(app, query); return; }
                    String phone = nodes.iterator().next().getId();
                    if (!selectSource(app, query, phone, SystemClock.elapsedRealtime(), boot(app))) return;
                    try {
                        Wearable.getMessageClient(app).sendMessage(phone, AlarmStateProtocol.QUERY_PATH,
                                AlarmStateProtocol.query(query.nonce)).addOnFailureListener(error -> failQuery(app, query));
                    } catch (RuntimeException error) { failQuery(app, query); }
                });
            } catch (RuntimeException error) { failQuery(app, query); }
    }

    static synchronized Query beginQuery(long now) {
        if (pending != null && pending.live(now)) return null;
        if (lastRefresh >= 0 && now >= lastRefresh && now - lastRefresh < THROTTLE_MS) return null;
        lastRefresh = now;
        pending = new Query(now);
        return pending;
    }

    static synchronized boolean selectSource(Context context, Query query, String source, long now, int boot) {
        if (pending != query || !query.live(now) || !WatchProtocol.validNodeId(source) || boot < 0) return false;
        query.phone = source;
        SharedPreferences p = prefs(context);
        if (!trusted(p, boot) || !source.equals(p.getString("source", ""))) {
            // Switching source or rebooting removes actionable cache, never a possible-send latch.
            p.edit().putString("source", source).putInt("boot", boot)
                    .remove("token").remove("tokenIssued").remove("revision").remove("seen")
                    .remove("received").remove("age").remove("state")
                    .putString("availability", AlarmStateProtocol.Availability.OFFLINE.name()).apply();
            changed(context);
        }
        return true;
    }

    static void receive(Context context, MessageEvent event) {
        if (event == null || !AlarmStateProtocol.STATE_PATH.equals(event.getPath())) return;
        AlarmStateProtocol.Report report = AlarmStateProtocol.parseReport(event.getData());
        String source = event.getSourceNodeId();
        if (report == null || !WatchProtocol.validNodeId(source)) return;
        Context app = context.getApplicationContext();
        MAIN.post(() -> {
            if (accept(app, source, report, SystemClock.elapsedRealtime(), boot(app)) && "-".equals(report.request))
                refresh(app);
        });
    }

    /** The same production admission path is exercised with inert reports in local tests. */
    static synchronized boolean accept(Context context, String source, AlarmStateProtocol.Report report,
            long now, int boot) {
        SharedPreferences p = prefs(context);
        recoverPossibleSend(p);
        if (!trusted(p, boot) || !source.equals(p.getString("source", ""))) return false;
        boolean answer = !"-".equals(report.request);
        if (!answer) {
            // An unsolicited report has no bounded round trip or ordered revision. It is a hint
            // to query, never freshness/ordering proof and never permission to clear a busy latch.
            pending = null;
            p.edit().putString("availability", (report.availability == AlarmStateProtocol.Availability.READY
                    ? AlarmStateProtocol.Availability.OFFLINE : report.availability).name())
                    .remove("token").remove("tokenIssued").apply();
            changed(context);
            return true;
        }
        if (answer && (pending == null || !pending.live(now) || !source.equals(pending.phone)
                || !report.request.equals(pending.nonce))) return false;

        String oldRevision = p.getString("revision", "");
        boolean ready = report.availability == AlarmStateProtocol.Availability.READY;
        boolean same = report.revision.equals(oldRevision);
        long previousAge = p.getLong("age", -1);
        long previousReceived = p.getLong("received", -1);
        String seen = p.getString("seen", "");
        if (ready) {
            if (same && report.state != state(p)) return false;
            if (same && report.ageMillis < previousAge) return false;
            if (!same && seen.contains("|" + report.revision + "|")) return false;
            // A changed UUID is not an ordering proof. Older reported events cannot replace newer ones.
            if (!same && AlarmStateProtocol.uuid(oldRevision) && previousReceived >= 0
                    && now >= previousReceived && report.ageMillis > age(p, now)) return false;
            if (same && report.ageMillis == previousAge && report.availability == availability(p)) {
                if (answer) pending = null;
                return false; // A duplicate must not keep either the link or the report fresh forever.
            }
        }
        if (answer) pending = null;
        SharedPreferences.Editor edit = p.edit();
        boolean wasFresh = fresh(p, now);
        edit.putString("availability", report.availability.name());
        if (ready) {
            edit.putString("revision", report.revision).putString("state", report.state.name());
            if (!same || report.ageMillis > previousAge) {
                long newAge = same ? Math.max(report.ageMillis, age(p, now)) : report.ageMillis;
                edit.putLong("age", newAge).putLong("received", now);
            }
            if (!same) {
                // Bounded suppression complements source/nonce checks and the age ordering rule.
                String updated = seen + "|" + report.revision + "|";
                if (updated.length() > 38 * 64) updated = updated.substring(updated.length() - 38 * 64);
                edit.putString("seen", updated);
            }
            if (!same || !wasFresh) edit.remove("token").remove("tokenIssued");
            if (p.getBoolean("awaiting", false) && changedAfterAction(p, source, report))
                edit.putBoolean("busy", false).putBoolean("awaiting", false).remove("token").remove("tokenIssued");
        } else {
            edit.remove("token").remove("tokenIssued");
        }
        edit.apply();
        changed(context);
        return true;
    }

    static synchronized Selection consume(Context context, String token, AlarmAction action) {
        return consumeAt(context, token, action, SystemClock.elapsedRealtime(), boot(context));
    }

    /** Revalidates a consumed selection immediately before the caller sends its COMMIT. */
    static synchronized boolean stillCurrent(Context context, Selection selection) {
        return currentAt(context, selection, SystemClock.elapsedRealtime(), boot(context));
    }

    static synchronized boolean currentAt(Context context, Selection selection, long now, int boot) {
        SharedPreferences p = prefs(context);
        return selection != null && trusted(p, boot) && fresh(p, now)
                && availability(p) == AlarmStateProtocol.Availability.READY
                && age(p, now) <= AlarmStateProtocol.MAX_STATE_AGE_MS
                && selection.phone.equals(p.getString("source", ""))
                && selection.stateRevision.equals(p.getString("revision", ""))
                && selection.action == AlarmStateProtocol.action(state(p));
    }

    static synchronized Selection consumeAt(Context context, String token, AlarmAction action, long now, int boot) {
        if (!AlarmStateProtocol.uuid(token) || action == null) return null;
        ViewState view = readAt(context, now, boot);
        if (!view.enabled || view.action != action || !token.equals(view.revision)) return null;
        SharedPreferences p = prefs(context);
        Selection selected = new Selection(p.getString("source", ""), p.getString("revision", ""), action);
        activeSelection = selected;
        // Synchronous persistence is intentional: process death must not resurrect this user's tap.
        boolean saved = p.edit().putBoolean("busy", true).putBoolean("awaiting", false)
                .putString("actionSource", selected.phone).putString("actionRevision", selected.stateRevision)
                .putString("actionState", p.getString("state", "UNKNOWN"))
                .remove("token").remove("tokenIssued").commit();
        changed(context);
        if (!saved) activeSelection = null;
        return saved ? selected : null;
    }

    static synchronized void finish(Context context, boolean commandMayHaveBeenSent) {
        SharedPreferences p = prefs(context);
        if (!p.getBoolean("busy", false)) return;
        boolean waiting = commandMayHaveBeenSent;
        if (waiting && availability(p) == AlarmStateProtocol.Availability.READY
                && trusted(p, boot(context)) && fresh(p, SystemClock.elapsedRealtime())
                && age(p, SystemClock.elapsedRealtime()) <= AlarmStateProtocol.MAX_STATE_AGE_MS) {
            waiting = !changedAfterAction(p, p.getString("source", ""), p.getString("revision", ""), state(p));
        }
        p.edit().putBoolean("busy", waiting).putBoolean("awaiting", waiting)
                .remove("token").remove("tokenIssued").apply();
        activeSelection = null;
        changed(context);
    }

    private static void recoverPossibleSend(SharedPreferences p) {
        if (p.getBoolean("busy", false) && !p.getBoolean("awaiting", false)
                && (activeSelection == null || !activeSelection.phone.equals(p.getString("actionSource", ""))
                    || !activeSelection.stateRevision.equals(p.getString("actionRevision", ""))))
            p.edit().putBoolean("awaiting", true).apply();
    }

    private static boolean changedAfterAction(SharedPreferences p, String source, AlarmStateProtocol.Report report) {
        return report.availability == AlarmStateProtocol.Availability.READY
                && changedAfterAction(p, source, report.revision, report.state);
    }

    private static boolean changedAfterAction(SharedPreferences p, String source, String revision, AlarmStateProtocol.State state) {
        return source.equals(p.getString("actionSource", ""))
                && !revision.equals(p.getString("actionRevision", ""))
                && !state.name().equals(p.getString("actionState", ""));
    }

    private static synchronized void failQuery(Context context, Query query) {
        if (pending != query) return;
        pending = null;
        prefs(context).edit().putString("availability", AlarmStateProtocol.Availability.OFFLINE.name())
                .remove("token").remove("tokenIssued").apply();
        changed(context);
    }

    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE); }
    private static int boot(Context context) { return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1); }
    private static boolean trusted(SharedPreferences p, int boot) {
        return boot >= 0 && p.getInt("boot", -1) == boot && WatchProtocol.validNodeId(p.getString("source", ""));
    }
    private static boolean fresh(SharedPreferences p, long now) {
        long received = p.getLong("received", -1);
        return received >= 0 && now >= received && now - received < AlarmStateProtocol.LINK_FRESH_MS;
    }
    private static long age(SharedPreferences p, long now) {
        long age = p.getLong("age", Long.MAX_VALUE), received = p.getLong("received", -1);
        if (age < 0 || received < 0 || now < received || Long.MAX_VALUE - age < now - received) return Long.MAX_VALUE;
        return age + now - received;
    }
    private static AlarmStateProtocol.State state(SharedPreferences p) {
        try { return AlarmStateProtocol.State.valueOf(p.getString("state", "UNKNOWN")); }
        catch (IllegalArgumentException error) { return AlarmStateProtocol.State.UNKNOWN; }
    }
    private static AlarmStateProtocol.Availability availability(SharedPreferences p) {
        try { return AlarmStateProtocol.Availability.valueOf(p.getString("availability", "OFFLINE")); }
        catch (IllegalArgumentException error) { return AlarmStateProtocol.Availability.OFFLINE; }
    }
    private static ViewState neutral(String label, String detail) { return new ViewState(label, detail, "", null, false); }
    private static String ageLabel(long age) {
        if (age < 60_000) return "ADT update just now";
        if (age < 3_600_000) return "ADT update " + age / 60_000 + " min ago";
        return "ADT update " + age / 3_600_000 + " hr ago";
    }
    private static String detail(AlarmStateProtocol.Availability state) {
        switch (state) {
            case NO_ACCESS: return "Allow ADT status access on your phone";
            case SETUP: return "Finish alarm setup on your phone";
            case BUSY: return "Waiting for your phone";
            case STALE: return "An up-to-date ADT report is needed";
            case NO_STATE: return "Waiting for an ADT state report";
            default: return "Tap Refresh to contact your phone";
        }
    }
    private static void changed(Context context) {
        // Tile renderer availability cannot undo a safely saved state or consumed token.
        try { AlarmTileService.requestUpdate(context); } catch (RuntimeException ignored) { }
    }
}
