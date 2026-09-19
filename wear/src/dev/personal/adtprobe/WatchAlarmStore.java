package dev.personal.adtprobe;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import androidx.annotation.Nullable;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Reported ADT state only: this class has no alarm-command transport. */
final class WatchAlarmStore {
    static final String PREFERENCES = "watch_alarm_state";
    private static final long QUERY_MS = 10_000;
    private static final long THROTTLE_MS = 2_000;
    private static final long HINT_THROTTLE_MS = 250;
    private static final long RECOVERY_MS = 30_000;
    private static final long PASSIVE_FRESH_MS = 45_000;
    private static final long PASSIVE_COOLDOWN_MS = 60_000;
    private static final long DUPLICATE_DELIVERY_MS = 2_000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Query pending;
    private static long lastRefresh = -1;
    private static boolean refreshQueued;
    private static ScheduledRefresh scheduledRefresh;
    private static Selection activeSelection;
    private static Recovery recovery;
    private static byte[] lastDelivery;
    private static String lastDeliverySource;
    private static long lastDeliveryAt = -1;

    /** Status transport cannot carry an alarm command. Replaced with inert callbacks in tests. */
    interface StatusTransport {
        void discover(Context context, Consumer<String> found, Runnable failed);
        void query(Context context, String phone, String nonce, Runnable failed);
    }
    private static StatusTransport transport = new StatusTransport() {
        @Override public void discover(Context context, Consumer<String> found, Runnable failed) {
            Wearable.getCapabilityClient(context).getCapability(WatchProtocol.PHONE_CAPABILITY,
                    CapabilityClient.FILTER_REACHABLE).addOnCompleteListener(task -> {
                if (!task.isSuccessful() || task.getResult() == null) { failed.run(); return; }
                Set<Node> nodes = task.getResult().getNodes();
                if (nodes == null || nodes.size() != 1) { failed.run(); return; }
                found.accept(nodes.iterator().next().getId());
            });
        }
        @Override public void query(Context context, String phone, String nonce, Runnable failed) {
            Wearable.getMessageClient(context).sendMessage(phone, AlarmStateProtocol.QUERY_PATH,
                    AlarmStateProtocol.query(nonce)).addOnFailureListener(error -> failed.run());
        }
    };

    private static final class Recovery {
        final long started;
        boolean hinted;
        Recovery(long started) { this.started = started; }
        boolean live(long now) { return now >= started && now - started < RECOVERY_MS; }
    }

    private static final class ScheduledRefresh {
        final Recovery owner;
        final long at;
        ScheduledRefresh(Recovery owner, long at) { this.owner = owner; this.at = at; }
    }

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
        final Recovery owner;
        String phone;
        Query(long started) { this.started = started; owner = recovery; }
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
                ? statusDetail(p, now, boot, true) : "Request in progress");
        AlarmStateProtocol.Availability availability = availability(p);
        if (!trusted(p, boot) || !fresh(p, now) || availability != AlarmStateProtocol.Availability.READY)
            return neutral("State unknown", statusDetail(p, now, boot, false));
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
        MAIN.post(() -> startRecovery(app));
    }

    /** Called on the main thread by visible UI; rendering cannot create a recovery loop. */
    static synchronized boolean refreshIfNeeded(Context context) {
        if (activeSelection != null) return false;
        if (isRefreshing()) return true;
        long now = SystemClock.elapsedRealtime();
        int boot = boot(context);
        SharedPreferences p = prefs(context);
        ViewState current = readAt(context, now, boot);
        long received = p.getLong("received", -1);
        if (current.enabled && received >= 0 && now >= received && now - received < PASSIVE_FRESH_MS) return false;
        long started = p.getLong("passiveRecoveryStarted", -1);
        if (p.getInt("passiveRecoveryBoot", -1) == boot && started >= 0
                && (now < started || now - started < PASSIVE_COOLDOWN_MS)) return false;
        startRecovery(context.getApplicationContext());
        return isRefreshing();
    }

    static synchronized boolean isRefreshing() {
        return recovery != null && recovery.live(SystemClock.elapsedRealtime());
    }

    /** Opaque lifetime marker for UI waiters; never an alarm-action token. */
    static synchronized Object refreshIdentity() {
        return isRefreshing() ? recovery : null;
    }

    private static synchronized void startRecovery(Context app) {
        startRecovery(app, false);
    }

    private static synchronized void startRecovery(Context app, boolean hint) {
        if (activeSelection != null) return;
        long now = SystemClock.elapsedRealtime();
        if (recovery != null && recovery.live(now)) {
            if (hint) expediteRefresh(app, recovery, now);
            return;
        }
        cancelRecovery();
        Recovery started = new Recovery(now);
        started.hinted = hint;
        recovery = started;
        diagnostic("RECOVERY_START");
        prefs(app).edit().putLong("passiveRecoveryStarted", now).putInt("passiveRecoveryBoot", boot(app)).apply();
        MAIN.postDelayed(() -> expireRecovery(app, started), RECOVERY_MS);
        changed(app);
        startRefresh(app, started);
    }

    private static synchronized void startRefresh(Context app, Recovery expected) {
        long now = SystemClock.elapsedRealtime();
        if (recovery != expected || !expected.live(now) || activeSelection != null) return;
        long minimumInterval = expected.hinted ? HINT_THROTTLE_MS : THROTTLE_MS;
        Query query = beginQuery(now, minimumInterval);
        if (query == null) {
            long delay = pending != null && pending.live(now) ? QUERY_MS - (now - pending.started)
                    : lastRefresh >= 0 && now >= lastRefresh ? minimumInterval - (now - lastRefresh) : 1;
            scheduleRefresh(app, expected, Math.max(1, delay));
            return;
        }
        expected.hinted = false;
        MAIN.postDelayed(() -> failQuery(app, query), QUERY_MS);
        try {
            diagnostic("DISCOVERY_START");
            transport.discover(app, phone -> MAIN.post(() -> {
                diagnostic("DISCOVERED durationMs=" + (SystemClock.elapsedRealtime() - query.started));
                if (!selectSource(app, query, phone, SystemClock.elapsedRealtime(), boot(app))) return;
                try {
                    diagnostic("QUERY_SEND");
                    transport.query(app, phone, query.nonce, () -> MAIN.post(() -> failQuery(app, query)));
                }
                catch (RuntimeException error) { failQuery(app, query); }
            }), () -> MAIN.post(() -> failQuery(app, query)));
        } catch (RuntimeException error) { failQuery(app, query); }
    }

    private static synchronized void scheduleRefresh(Context app, Recovery expected, long delay) {
        long now = SystemClock.elapsedRealtime();
        if (expected == null || recovery != expected || !expected.live(now)) return;
        long at = now + Math.max(0, delay);
        if (refreshQueued && scheduledRefresh != null && scheduledRefresh.owner == expected && scheduledRefresh.at <= at) return;
        ScheduledRefresh scheduled = new ScheduledRefresh(expected, at);
        scheduledRefresh = scheduled;
        refreshQueued = true;
        MAIN.postDelayed(() -> {
            synchronized (WatchAlarmStore.class) {
                if (recovery != expected || scheduledRefresh != scheduled) return;
                refreshQueued = false;
                scheduledRefresh = null;
                startRefresh(app, expected);
            }
        }, Math.max(0, delay));
    }

    private static void expediteRefresh(Context app, Recovery expected, long now) {
        expected.hinted = true;
        long delay = lastRefresh >= 0 && now >= lastRefresh ? Math.max(0, HINT_THROTTLE_MS - (now - lastRefresh)) : 0;
        scheduleRefresh(app, expected, delay);
    }

    private static synchronized void expireRecovery(Context context, Recovery expected) {
        if (recovery != expected) return;
        diagnostic("RECOVERY_EXPIRE");
        if (pending != null && pending.owner == expected) markTransportFailed(context);
        cancelRecovery();
        changed(context);
    }

    private static void cancelRecovery() {
        if (recovery != null) diagnostic("RECOVERY_CANCEL");
        recovery = null;
        pending = null;
        refreshQueued = false;
        scheduledRefresh = null;
    }

    private static void afterAnswer(Context context) {
        boolean actionable = read(context).enabled;
        if (actionable) prefs(context).edit().remove("passiveRecoveryStarted").remove("passiveRecoveryBoot").apply();
        if (recovery == null) return;
        AlarmStateProtocol.Availability status = availability(prefs(context));
        if (actionable || status == AlarmStateProtocol.Availability.NO_ACCESS
                || status == AlarmStateProtocol.Availability.SETUP || status == AlarmStateProtocol.Availability.STALE) {
            cancelRecovery();
        } else scheduleRefresh(context.getApplicationContext(), recovery, THROTTLE_MS);
    }

    static synchronized Query beginQuery(long now) {
        return beginQuery(now, THROTTLE_MS);
    }

    private static Query beginQuery(long now, long minimumInterval) {
        if (pending != null && pending.live(now)) return null;
        if (lastRefresh >= 0 && now >= lastRefresh && now - lastRefresh < minimumInterval) return null;
        lastRefresh = now;
        pending = new Query(now);
        return pending;
    }

    static synchronized boolean selectSource(Context context, Query query, String source, long now, int boot) {
        if (!queryCurrent(query, now) || !WatchProtocol.validNodeId(source) || boot < 0) return false;
        query.phone = source;
        SharedPreferences p = prefs(context);
        if (!trusted(p, boot) || !source.equals(p.getString("source", ""))) {
            // Switching source or rebooting removes actionable cache, never a possible-send latch.
            p.edit().putString("source", source).putInt("boot", boot)
                    .remove("token").remove("tokenIssued").remove("revision").remove("seen")
                    .remove("received").remove("age").remove("state").remove("contactReceived")
                    .putString("availability", AlarmStateProtocol.Availability.OFFLINE.name()).apply();
            changed(context);
        }
        return true;
    }

    static void receive(Context context, MessageEvent event) {
        if (event == null || !AlarmStateProtocol.STATE_PATH.equals(event.getPath())) return;
        byte[] data = event.getData();
        AlarmStateProtocol.Report report = AlarmStateProtocol.parseReport(data);
        String source = event.getSourceNodeId();
        if (report == null || !WatchProtocol.validNodeId(source)) return;
        if (duplicateDelivery(source, data, SystemClock.elapsedRealtime())) return;
        Context app = context.getApplicationContext();
        MAIN.post(() -> {
            if (accept(app, source, report, SystemClock.elapsedRealtime(), boot(app)) && "-".equals(report.request))
                startRecovery(app, true);
        });
    }

    /**
     * Play Services delivers a message both to WatchStateListener and to the Activity's live
     * MessageClient listener while the app is open. A second copy of an answer is already
     * rejected by the nonce bookkeeping, but a second copy of a hint would discard the query
     * the first copy just issued and cost another round trip, so identical copies are dropped.
     */
    private static synchronized boolean duplicateDelivery(String source, byte[] data, long now) {
        boolean duplicate = lastDelivery != null && source.equals(lastDeliverySource)
                && Arrays.equals(data, lastDelivery) && now >= lastDeliveryAt && now - lastDeliveryAt < DUPLICATE_DELIVERY_MS;
        if (duplicate) { diagnostic("DUPLICATE_DELIVERY"); return true; }
        lastDelivery = data.clone(); lastDeliverySource = source; lastDeliveryAt = now;
        return false;
    }

    /** The same production admission path is exercised with inert reports in local tests. */
    static synchronized boolean accept(Context context, String source, AlarmStateProtocol.Report report,
            long now, int boot) {
        SharedPreferences p = prefs(context);
        recoverPossibleSend(p);
        if (!trusted(p, boot) || !source.equals(p.getString("source", ""))) return false;
        boolean answer = !"-".equals(report.request);
        if (!answer) {
            diagnostic("HINT_ACCEPT availability=" + report.availability.name());
            // An unsolicited report has no bounded round trip or ordered revision. It is a hint
            // to query, never freshness/ordering proof and never permission to clear a busy latch.
            pending = null;
            p.edit().putString("availability", (report.availability == AlarmStateProtocol.Availability.READY
                    ? AlarmStateProtocol.Availability.OFFLINE : report.availability).name())
                    .remove("token").remove("tokenIssued").apply();
            changed(context);
            if (recovery != null && recovery.live(now)) expediteRefresh(context.getApplicationContext(), recovery, now);
            return true;
        }
        if (answer && (pending == null || !queryCurrent(pending, now) || !source.equals(pending.phone)
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
                diagnostic("ANSWER_DUPLICATE availability=" + report.availability.name());
                if (answer) pending = null;
                p.edit().putLong("contactReceived", now).apply();
                afterAnswer(context);
                changed(context);
                return false; // Contact alone must not keep the actionable report fresh forever.
            }
        }
        if (answer) pending = null;
        SharedPreferences.Editor edit = p.edit();
        boolean wasFresh = fresh(p, now);
        edit.putString("availability", report.availability.name()).putLong("contactReceived", now);
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
        diagnostic("ANSWER_ACCEPT availability=" + report.availability.name());
        afterAnswer(context);
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
        cancelRecovery();
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
        refresh(context);
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
        diagnostic("QUERY_FAIL durationMs=" + (SystemClock.elapsedRealtime() - query.started));
        pending = null;
        markTransportFailed(context);
        scheduleRefresh(context.getApplicationContext(), recovery, THROTTLE_MS);
        changed(context);
    }

    private static void markTransportFailed(Context context) {
        prefs(context).edit().putString("availability", AlarmStateProtocol.Availability.OFFLINE.name())
                .remove("token").remove("tokenIssued").remove("contactReceived").apply();
    }

    private static boolean queryCurrent(Query query, long now) {
        return pending == query && query.live(now) && (query.owner == null
                || recovery == query.owner && query.owner.live(now));
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
            case BUSY: return "Phone reached; waiting for ADT";
            case STALE: return "An up-to-date ADT report is needed";
            case NO_STATE: return "Phone reached; no ADT report";
            case OFFLINE: return "Phone reached; ADT status unavailable";
            default: return "Tap Refresh to contact your phone";
        }
    }
    private static String statusDetail(SharedPreferences p, long now, int boot, boolean awaiting) {
        long contact = p.getLong("contactReceived", -1);
        if (trusted(p, boot) && contact >= 0 && now >= contact && now - contact < AlarmStateProtocol.LINK_FRESH_MS) {
            AlarmStateProtocol.Availability status = availability(p);
            if (status != AlarmStateProtocol.Availability.READY) return detail(status);
            if (awaiting) return "Waiting for a new ADT update";
            return "An up-to-date ADT report is needed";
        }
        if (recovery != null && recovery.live(now)) return "Connecting to phone…";
        return awaiting ? "No phone reply; check ADT" : "No phone reply. Tap Refresh";
    }
    private static void changed(Context context) {
        // Tile renderer availability cannot undo a safely saved state or consumed token.
        try { AlarmTileService.requestUpdate(context); } catch (RuntimeException ignored) { }
    }

    private static void diagnostic(String event) {
        Log.i("AdtWatchStatus", SystemClock.elapsedRealtime() + " " + event);
    }
}
