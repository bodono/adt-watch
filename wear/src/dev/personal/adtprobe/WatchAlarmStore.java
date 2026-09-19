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
import java.util.Set;
import java.util.UUID;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
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
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Query pending;
    private static long lastRefresh = -1;
    private static boolean refreshQueued;
    private static ScheduledRefresh scheduledRefresh;
    private static Selection activeSelection;
    private static Recovery recovery;

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
        final long started, finalBoundary;
        boolean hinted;
        long finalStarted = -1;
        Recovery(long started, long finalBoundary) { this.started = started; this.finalBoundary = finalBoundary; }
        boolean live(long now) {
            long origin = finalStarted >= 0 ? finalStarted : started;
            return now >= origin && now - origin < (finalStarted >= 0 ? QUERY_MS : RECOVERY_MS);
        }
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

    /** Remaining display lifetime only; consuming an action still rechecks the monotonic state. */
    static synchronized long remainingActionValidityMillis(Context context) {
        long now = SystemClock.elapsedRealtime();
        SharedPreferences p = prefs(context);
        long issued = p.getLong("tokenIssued", -1);
        if (p.getBoolean("busy", false) || !trusted(p, boot(context)) || !fresh(p, now)
                || availability(p) != AlarmStateProtocol.Availability.READY || AlarmStateProtocol.action(state(p)) == null
                || !AlarmStateProtocol.uuid(p.getString("revision", "")) || !AlarmStateProtocol.uuid(p.getString("token", ""))
                || issued < 0 || now < issued) return 0;
        return Math.max(0, Math.min(AlarmStateProtocol.LINK_FRESH_MS - (now - p.getLong("received", -1)),
                Math.min(AlarmStateProtocol.LINK_FRESH_MS - (now - issued), maxAge(p) - age(p, now))));
    }

    static synchronized ViewState readAt(Context context, long now, int boot) {
        SharedPreferences p = prefs(context);
        recoverPossibleSend(p);
        prepareLegacyWait(p, now, boot);
        if (p.getBoolean("busy", false)) {
            if (p.getBoolean("awaiting", false) && unconfirmed(p, now, boot))
                return neutral("Result unconfirmed", availability(p) == AlarmStateProtocol.Availability.NO_ACCESS
                    ? detail(AlarmStateProtocol.Availability.NO_ACCESS) : "Check ADT on your phone");
            return neutral("Checking alarm", p.getBoolean("awaiting", false)
                    ? statusDetail(p, now, boot, true) : "Request in progress");
        }
        AlarmStateProtocol.Availability availability = availability(p);
        if (availability == AlarmStateProtocol.Availability.UNCONFIRMED)
            return neutral("Result unconfirmed", "Check ADT on your phone");
        if (!trusted(p, boot) || !fresh(p, now) || availability != AlarmStateProtocol.Availability.READY)
            return neutral("State unknown", statusDetail(p, now, boot, false));
        long age = age(p, now);
        AlarmStateProtocol.State state = state(p);
        AlarmAction action = AlarmStateProtocol.action(state);
        if (action == null || age > maxAge(p)
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
        String observedAt = new SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(new Date(Math.max(0, System.currentTimeMillis() - age)));
        return new ViewState(label, "ADT checked " + observedAt, token, action, true);
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
        SharedPreferences p = prefs(app);
        recoverPossibleSend(p); prepareLegacyWait(p, now, boot(app));
        long boundary = uncertaintyBoundary(p, now, boot(app));
        Recovery started = new Recovery(now, boundary >= now && boundary - now <= RECOVERY_MS ? boundary : -1);
        started.hinted = hint;
        recovery = started;
        diagnostic("RECOVERY_START");
        prefs(app).edit().putLong("passiveRecoveryStarted", now).putInt("passiveRecoveryBoot", boot(app)).apply();
        MAIN.postDelayed(() -> expireRecovery(app, started), RECOVERY_MS);
        if (started.finalBoundary >= 0)
            MAIN.postDelayed(() -> finalBoundaryRead(app, started), Math.max(0, started.finalBoundary - now));
        changed(app);
        startRefresh(app, started);
    }

    private static synchronized void startRefresh(Context app, Recovery expected) {
        long now = SystemClock.elapsedRealtime();
        if (recovery != expected || !expected.live(now) || activeSelection != null) return;
        long minimumInterval = expected.finalStarted >= 0 ? 0 : expected.hinted ? HINT_THROTTLE_MS : THROTTLE_MS;
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
        long now = SystemClock.elapsedRealtime();
        if (expected.finalStarted < 0 && expected.finalBoundary >= 0 && now >= expected.finalBoundary) {
            finalBoundaryRead(context.getApplicationContext(), expected);
            if (recovery != expected || expected.finalStarted >= 0) return;
        }
        if (expected.finalStarted >= 0 && expected.live(now)) return;
        diagnostic("RECOVERY_EXPIRE");
        // A query still in flight at the deadline is dropped. That is a transport failure only
        // if the phone never answered during this window; otherwise its last answer (typically
        // BUSY while waiting for ADT) remains the displayed diagnosis instead of "No phone reply".
        if (pending != null && pending.owner == expected && !contactedSince(prefs(context), expected.started))
            markTransportFailed(context);
        cancelRecovery();
        changed(context);
    }

    private static boolean contactedSince(SharedPreferences p, long started) {
        long contact = p.getLong("contactReceived", -1);
        return contact >= 0 && contact >= started;
    }

    /** One read after the uncertainty boundary, never a command or an endlessly extended burst. */
    private static synchronized void finalBoundaryRead(Context app, Recovery expected) {
        long now = SystemClock.elapsedRealtime();
        if (recovery != expected || expected.finalStarted >= 0 || expected.finalBoundary < 0
                || now < expected.finalBoundary || activeSelection != null || !prefs(app).getBoolean("busy", false)) return;
        if (pending != null && pending.owner == expected && pending.live(now) && pending.started >= expected.finalBoundary) {
            expected.finalStarted = pending.started; // An existing qualifying query is the final attempt.
        } else {
            pending = null; // Only supersede a pre-boundary read; its nonce cannot prove the required later observation.
            expected.finalStarted = now;
            refreshQueued = false; scheduledRefresh = null;
            startRefresh(app, expected);
        }
        MAIN.postDelayed(() -> expireRecovery(app, expected), Math.max(0, QUERY_MS - (now - expected.finalStarted)));
    }

    private static long uncertaintyBoundary(SharedPreferences p, long now, int boot) {
        if (!p.getBoolean("busy", false) || !p.getBoolean("commitMayHaveBeenSent", true)) return -1;
        boolean legacy = !hasCommitOrigin(p, now, boot);
        long started = p.getLong(legacy ? "legacyWaitStarted" : "commitStarted", -1);
        if (started < 0 || p.getInt(legacy ? "legacyWaitBoot" : "commitBoot", -1) != boot
                || started > Long.MAX_VALUE - RECOVERY_MS) return -1;
        return started + RECOVERY_MS;
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
                || status == AlarmStateProtocol.Availability.SETUP || status == AlarmStateProtocol.Availability.STALE
                || status == AlarmStateProtocol.Availability.UNCONFIRMED) {
            cancelRecovery();
        } else if (recovery != null && recovery.finalStarted >= 0) cancelRecovery();
        else scheduleRefresh(context.getApplicationContext(), recovery, THROTTLE_MS);
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
                    .remove("received").remove("age").remove("state").remove("contactReceived").remove("completedRequest").remove("evidence")
                    .remove("observationId").remove("seenObservations")
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
                startRecovery(app, true);
        });
    }

    /** The same production admission path is exercised with inert reports in local tests. */
    static synchronized boolean accept(Context context, String source, AlarmStateProtocol.Report report,
            long now, int boot) {
        SharedPreferences p = prefs(context);
        recoverPossibleSend(p);
        prepareLegacyWait(p, now, boot);
        if (!trusted(p, boot) || !source.equals(p.getString("source", ""))) return false;
        boolean answer = !"-".equals(report.request);
        if (!answer) {
            diagnostic("HINT_ACCEPT availability=" + report.availability.name());
            // An unsolicited report has no bounded round trip or ordered revision. It is a hint
            // to query, never freshness/ordering proof and never permission to clear a busy latch.
            // Keep a live correlated read: frequent phone polling hints must not starve its answer.
            if (pending != null && !queryCurrent(pending, now)) pending = null;
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
        long roundTrip = now - pending.started;
        long observationAge = report.ageMillis > Long.MAX_VALUE - roundTrip ? Long.MAX_VALUE : report.ageMillis + roundTrip;
        boolean ready = report.availability == AlarmStateProtocol.Availability.READY
                && report.evidence == AlarmStateProtocol.Evidence.ADT_QUERY && observationAge < AlarmStateProtocol.QUERY_FRESH_MS;
        boolean same = report.revision.equals(oldRevision);
        long previousReceived = p.getLong("received", -1);
        String seen = p.getString("seen", "");
        String observations = p.getString("seenObservations", "");
        if (ready) {
            if (!AlarmStateProtocol.uuid(report.observationId)) return false;
            if (same && report.state != state(p)) return false;
            if (observations.contains("|" + report.observationId + "|")) {
                pending = null;
                p.edit().putLong("contactReceived", now).apply();
                afterAnswer(context); changed(context);
                return false; // A repeated observation never renews its original age or settles a command.
            }
            if (!same && seen.contains("|" + report.revision + "|")) return false;
            // Raw phone age is a lower bound at receipt; the stored age is an upper bound.
            // Comparing two RTT-inflated upper bounds would reject a legitimate slower new query.
            if (evidence(p) == AlarmStateProtocol.Evidence.ADT_QUERY && AlarmStateProtocol.uuid(oldRevision) && previousReceived >= 0
                    && now >= previousReceived && report.ageMillis > age(p, now)) return false;
        }
        boolean checkedAfterCommit = ready && queryAfterUncertainCommit(p, source, report, pending, now, boot);
        if (answer) pending = null;
        SharedPreferences.Editor edit = p.edit();
        boolean wasFresh = fresh(p, now);
        edit.putString("availability", (report.availability == AlarmStateProtocol.Availability.READY && !ready
                ? report.evidence == AlarmStateProtocol.Evidence.ADT_QUERY ? AlarmStateProtocol.Availability.STALE
                    : AlarmStateProtocol.Availability.NO_STATE : report.availability).name()).putLong("contactReceived", now);
        if (ready) {
            edit.putString("revision", report.revision).putString("state", report.state.name())
                    .putString("completedRequest", report.completedRequest).putString("evidence", report.evidence.name())
                    .putString("observationId", report.observationId)
                    .putString("seenObservations", remember(observations, report.observationId))
                    .putLong("age", observationAge).putLong("received", now);
            if (checkedAfterCommit) {
                if (AlarmStateProtocol.uuid(p.getString("actionRequest", "")))
                    edit.putString("settledActionRequest", p.getString("actionRequest", ""));
                else edit.putString("settledLegacyObservation", report.observationId);
            }
            if (!same) {
                // Bounded suppression complements source/nonce checks and the age ordering rule.
                edit.putString("seen", remember(seen, report.revision));
            }
            if (!same || !wasFresh) edit.remove("token").remove("tokenIssued");
            if (p.getBoolean("awaiting", false) && (changedAfterAction(p, source, report) || checkedAfterCommit))
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
                && age(p, now) <= maxAge(p)
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
                .putLong("actionStarted", now).putBoolean("commitMayHaveBeenSent", false).remove("actionRequest")
                .remove("commitStarted").remove("commitBoot").remove("checkedActionRequest")
                .remove("settledActionRequest").putString("actionObservation", p.getString("observationId", ""))
                .remove("legacyWaitStarted").remove("legacyWaitBoot").remove("settledLegacyObservation")
                .putString("actionSource", selected.phone).putString("actionRevision", selected.stateRevision)
                .putString("actionState", p.getString("state", "UNKNOWN"))
                .remove("token").remove("tokenIssued").commit();
        changed(context);
        if (!saved) activeSelection = null;
        return saved ? selected : null;
    }

    static synchronized boolean attachRequest(Context context, Selection selected, String request) {
        return selected != null && activeSelection == selected && AlarmStateProtocol.uuid(request)
                && prefs(context).edit().putString("actionRequest", request).commit();
    }

    static synchronized boolean markCommitting(Context context, Selection selected, String request) {
        return activeSelection == selected && stillCurrent(context, selected)
                && request.equals(prefs(context).getString("actionRequest", ""))
                && prefs(context).edit().putBoolean("commitMayHaveBeenSent", true)
                    .putLong("commitStarted", SystemClock.elapsedRealtime()).putInt("commitBoot", boot(context)).commit();
    }

    static synchronized void finish(Context context, boolean commandMayHaveBeenSent) {
        SharedPreferences p = prefs(context);
        if (!p.getBoolean("busy", false)) return;
        boolean waiting = commandMayHaveBeenSent;
        if (waiting && availability(p) == AlarmStateProtocol.Availability.READY
                && trusted(p, boot(context)) && fresh(p, SystemClock.elapsedRealtime())
                && age(p, SystemClock.elapsedRealtime()) <= maxAge(p)) {
            waiting = !changedAfterAction(p, p.getString("source", ""), p.getString("revision", ""), state(p));
        }
        p.edit().putBoolean("busy", waiting).putBoolean("awaiting", waiting)
                .putBoolean("commitMayHaveBeenSent", waiting)
                .remove("token").remove("tokenIssued").apply();
        activeSelection = null;
        changed(context);
        refresh(context);
    }

    private static void recoverPossibleSend(SharedPreferences p) {
        if (p.getBoolean("busy", false) && !p.getBoolean("awaiting", false)
                && (activeSelection == null || !activeSelection.phone.equals(p.getString("actionSource", ""))
                    || !activeSelection.stateRevision.equals(p.getString("actionRevision", "")))) {
            if (p.getBoolean("commitMayHaveBeenSent", true)) p.edit().putBoolean("awaiting", true).apply();
            else p.edit().putBoolean("busy", false).putBoolean("awaiting", false)
                    .remove("token").remove("tokenIssued").remove("actionRequest").apply();
        }
    }

    private static boolean changedAfterAction(SharedPreferences p, String source, AlarmStateProtocol.Report report) {
        return report.availability == AlarmStateProtocol.Availability.READY
                && changedAfterAction(p, source, report.revision, report.state, report.completedRequest, report.evidence);
    }

    private static boolean changedAfterAction(SharedPreferences p, String source, String revision, AlarmStateProtocol.State state) {
        return changedAfterAction(p, source, revision, state, p.getString("completedRequest", "-"), evidence(p));
    }

    private static boolean changedAfterAction(SharedPreferences p, String source, String revision,
            AlarmStateProtocol.State state, String completedRequest, AlarmStateProtocol.Evidence evidence) {
        String request = p.getString("actionRequest", "");
        return evidence == AlarmStateProtocol.Evidence.ADT_QUERY && source.equals(p.getString("actionSource", ""))
                && (AlarmStateProtocol.uuid(request) && (request.equals(completedRequest)
                    || request.equals(p.getString("settledActionRequest", "")))
                    || !AlarmStateProtocol.uuid(request) && AlarmStateProtocol.uuid(p.getString("settledLegacyObservation", ""))
                        && p.getString("settledLegacyObservation", "").equals(p.getString("observationId", "")));
    }

    private static boolean hasCommitOrigin(SharedPreferences p, long now, int boot) {
        long committed = p.getLong("commitStarted", -1);
        return AlarmStateProtocol.uuid(p.getString("actionRequest", "")) && committed >= 0 && now >= committed
                && p.getInt("commitBoot", -1) == boot;
    }

    /** Old installations have no comparable COMMIT clock. Start one bounded read-only migration wait. */
    private static void prepareLegacyWait(SharedPreferences p, long now, int boot) {
        if (!p.getBoolean("busy", false) || !p.getBoolean("commitMayHaveBeenSent", true)
                || hasCommitOrigin(p, now, boot) || boot < 0) return;
        long started = p.getLong("legacyWaitStarted", -1);
        if (started < 0 || now < started || p.getInt("legacyWaitBoot", -1) != boot)
            p.edit().putLong("legacyWaitStarted", now).putInt("legacyWaitBoot", boot).apply();
    }

    /** A full query round trip bounds the check's age without comparing device clocks. */
    private static boolean queryAfterUncertainCommit(SharedPreferences p, String source, AlarmStateProtocol.Report report,
            Query query, long now, int boot) {
        boolean legacy = !hasCommitOrigin(p, now, boot);
        long committed = p.getLong(legacy ? "legacyWaitStarted" : "commitStarted", -1);
        if (report.evidence != AlarmStateProtocol.Evidence.ADT_QUERY
                || report.availability != AlarmStateProtocol.Availability.READY
                || !p.getBoolean("busy", false) || !p.getBoolean("commitMayHaveBeenSent", true)
                || !source.equals(p.getString("actionSource", ""))
                || report.observationId.equals(p.getString("actionObservation", ""))
                || committed < 0 || now < committed || now - committed < RECOVERY_MS
                || p.getInt(legacy ? "legacyWaitBoot" : "commitBoot", -1) != boot
                || query == null || !queryCurrent(query, now) || !source.equals(query.phone)
                || !report.request.equals(query.nonce) || legacy && query.started - committed < RECOVERY_MS) return false;
        long sinceCommit = now - committed, roundTrip = now - query.started;
        // Subtraction avoids overflow and treats the entire round trip as possible
        // additional age. A check at/before COMMIT, a push, or an old query cannot settle it.
        return roundTrip >= 0 && roundTrip < sinceCommit && report.ageMillis < sinceCommit - roundTrip;
    }

    private static boolean unconfirmed(SharedPreferences p, long now, int boot) {
        long started = p.getLong("actionStarted", -1);
        return availability(p) == AlarmStateProtocol.Availability.UNCONFIRMED || !trusted(p, boot)
                || started < 0 || now < started || now - started >= RECOVERY_MS;
    }

    private static synchronized void failQuery(Context context, Query query) {
        if (pending != query) return;
        diagnostic("QUERY_FAIL durationMs=" + (SystemClock.elapsedRealtime() - query.started));
        pending = null;
        markTransportFailed(context);
        if (recovery != null && recovery.finalStarted >= 0) cancelRecovery();
        else scheduleRefresh(context.getApplicationContext(), recovery, THROTTLE_MS);
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
        return evidence(p) == AlarmStateProtocol.Evidence.ADT_QUERY && AlarmStateProtocol.uuid(p.getString("observationId", ""))
                && received >= 0 && now >= received && now - received < AlarmStateProtocol.LINK_FRESH_MS
                && age(p, now) < AlarmStateProtocol.QUERY_FRESH_MS;
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
    private static AlarmStateProtocol.Evidence evidence(SharedPreferences p) {
        try { return AlarmStateProtocol.Evidence.valueOf(p.getString("evidence", "ADT_NOTIFICATION")); }
        catch (IllegalArgumentException error) { return AlarmStateProtocol.Evidence.ADT_NOTIFICATION; }
    }
    private static long maxAge(SharedPreferences p) {
        return AlarmStateProtocol.QUERY_FRESH_MS;
    }
    private static String remember(String seen, String id) {
        String updated = seen + "|" + id + "|";
        return updated.length() > 38 * 64 ? updated.substring(updated.length() - 38 * 64) : updated;
    }
    private static AlarmStateProtocol.Availability availability(SharedPreferences p) {
        try { return AlarmStateProtocol.Availability.valueOf(p.getString("availability", "OFFLINE")); }
        catch (IllegalArgumentException error) { return AlarmStateProtocol.Availability.OFFLINE; }
    }
    private static ViewState neutral(String label, String detail) { return new ViewState(label, detail, "", null, false); }
    private static String detail(AlarmStateProtocol.Availability state) {
        switch (state) {
            case NO_ACCESS: return "Sign in to ADT on your phone";
            case SETUP: return "Finish alarm setup on your phone";
            case BUSY: return "Phone reached; waiting for ADT";
            case UNCONFIRMED: return "Result unconfirmed; check ADT on your phone";
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
