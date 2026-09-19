package dev.personal.adtprobe;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** ADT's authenticated reported state is the only authority. Notifications are refresh hints. */
final class PhoneAlarmState {
    static final String ADT_PACKAGE = "com.adtuk.adtukalarm";
    static final String PREFERENCES = "live_adt_state";
    static final long COMMAND_CONFIRMATION_MILLIS = AdtLiveLedger.PENDING_LIMIT_MS;
    private static final long QUERY_MS = 8_000;
    private static final ReentrantLock QUERY_LOCK = new ReentrantLock();
    private static final ScheduledExecutorService READS = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "ADT status reads"); thread.setDaemon(true); return thread;
    });
    private static final AtomicBoolean HINT_QUEUED = new AtomicBoolean();
    interface Schedule { void after(Runnable action, long delayMillis); }
    static Schedule scheduleOperation = (action, delay) -> READS.schedule(action, delay, TimeUnit.MILLISECONDS);
    private static boolean storageFailed;
    interface Query { AdtPortalClient.Result query(Context context, long deadline); }
    static Query queryOperation = PhoneAlarmState::queryPortal;
    private PhoneAlarmState() { }

    static final class Snapshot {
        final AlarmStateProtocol.State state;
        final String revision, completedRequest, observationId;
        final long ageMillis;
        final AlarmStateProtocol.Availability availability;
        final AlarmStateProtocol.Evidence evidence = AlarmStateProtocol.Evidence.ADT_QUERY;
        final boolean pending;
        Snapshot(AdtLiveLedger.Snapshot value, AlarmStateProtocol.Availability failure) {
            state = value != null && failure == null ? value.state : AlarmStateProtocol.State.UNKNOWN;
            revision = value == null ? "-" : value.revision;
            observationId = value == null ? "-" : value.observationId;
            completedRequest = value == null ? "-" : value.completedRequest;
            ageMillis = value == null ? 0 : value.observationAgeMillis;
            availability = failure != null ? failure : value.availability;
            pending = value != null && value.pending;
        }
    }

    static synchronized Snapshot snapshot(Context context) {
        AdtPortalSession.Binding binding = AdtPortalSession.binding(context);
        if (binding == null) return new Snapshot(null, AlarmStateProtocol.Availability.SETUP);
        SharedPreferences stored = preferences(context);
        AdtLiveLedger.Snapshot value = read(stored, binding).snapshot(SystemClock.elapsedRealtime(), boot(context));
        AlarmStateProtocol.Availability failure = null;
        if (storageFailed) failure = AlarmStateProtocol.Availability.NO_STATE;
        else if (!binding.id.equals(stored.getString("bindingId", ""))) failure = AlarmStateProtocol.Availability.NO_STATE;
        else {
            String status = stored.getString("queryStatus", "UNAVAILABLE");
            if ("LOGIN_REQUIRED".equals(status) || "VERIFY_LOGIN".equals(status)) failure = AlarmStateProtocol.Availability.NO_ACCESS;
            else if ("AMBIGUOUS".equals(status) || "UNSUPPORTED".equals(status)) failure = AlarmStateProtocol.Availability.NO_STATE;
            else if (!"READY".equals(status) && !"BUSY".equals(status)) failure = AlarmStateProtocol.Availability.OFFLINE;
        }
        return new Snapshot(value, failure);
    }

    /** Worker-only read. Concurrent requests share a read begun after their arrival where possible. */
    static Snapshot refresh(Context context) {
        Context app = context.getApplicationContext();
        AdtPortalSession.Binding binding = AdtPortalSession.binding(app);
        if (binding == null) return snapshot(app);
        long arrived = SystemClock.elapsedRealtime(), deadline = arrived + QUERY_MS;
        boolean locked = false;
        try {
            locked = QUERY_LOCK.tryLock(QUERY_MS, TimeUnit.MILLISECONDS);
            if (!locked) { failed(app, binding, AdtPortalClient.Status.UNAVAILABLE); return snapshot(app); }
            synchronized (PhoneAlarmState.class) {
                SharedPreferences stored = preferences(app);
                if (binding.id.equals(stored.getString("bindingId", ""))
                        && stored.getLong("lastQueryStarted", -1) >= arrived
                        && stored.getInt("lastQueryBoot", -1) == boot(app)) return snapshot(app);
            }
            long started = SystemClock.elapsedRealtime();
            int queryBoot = boot(app);
            AdtPortalClient.Result result = queryOperation.query(app, deadline);
            long received = SystemClock.elapsedRealtime();
            synchronized (PhoneAlarmState.class) {
                if (!AdtPortalSession.valid(app, binding)) return snapshot(app);
                if (result == null || received >= deadline || received < started || queryBoot != boot(app)) {
                    failed(app, binding, AdtPortalClient.Status.UNAVAILABLE); return snapshot(app);
                }
                if (result.status != AdtPortalClient.Status.READY && result.status != AdtPortalClient.Status.BUSY) {
                    failed(app, binding, result.status); return snapshot(app);
                }
                AdtLiveLedger ledger = read(preferences(app), binding);
                if (!ledger.observe(new AdtLiveLedger.Sample(result.state, result.systemId, result.partitionId,
                        started, received, queryBoot, Boolean.TRUE.equals(result.loading)))) {
                    failed(app, binding, AdtPortalClient.Status.UNSUPPORTED); return snapshot(app);
                }
                write(app, binding, ledger, result.status, started, queryBoot);
                return snapshot(app);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); failed(app, binding, AdtPortalClient.Status.UNAVAILABLE);
        } catch (RuntimeException error) {
            failed(app, binding, AdtPortalClient.Status.UNAVAILABLE);
        } finally { if (locked) QUERY_LOCK.unlock(); }
        return snapshot(app);
    }

    static synchronized boolean matches(Context context, String revision, AlarmAction action) {
        Snapshot current = snapshot(context);
        return current.availability == AlarmStateProtocol.Availability.READY
            && current.revision.equals(revision) && actionFor(current.state) == action;
    }
    static boolean beginCommand(Context context, String revision, AlarmAction action) {
        return beginCommand(context, revision, action, UUID.randomUUID().toString());
    }
    /** The consuming record reaches disk before the caller may click ADT's native scene widget. */
    static synchronized boolean beginCommand(Context context, String revision, AlarmAction action, String requestId) {
        if (!matches(context, revision, action)) return false;
        AdtPortalSession.Binding binding = AdtPortalSession.binding(context);
        if (binding == null) return false;
        SharedPreferences stored = preferences(context);
        AdtLiveLedger ledger = read(stored, binding);
        if (!ledger.begin(revision, action, requestId, SystemClock.elapsedRealtime(), boot(context))) return false;
        if (!write(context, binding, ledger, AdtPortalClient.Status.READY,
                stored.getLong("lastQueryStarted", -1), stored.getInt("lastQueryBoot", -1))) return false;
        Context app = context.getApplicationContext();
        try { PhoneStateLink.publish(app); } catch (RuntimeException ignored) { }
        try { scheduleResultRead(app, requestId, SystemClock.elapsedRealtime() + COMMAND_CONFIRMATION_MILLIS); }
        catch (RuntimeException ignored) { /* A watch query will also read the result. */ }
        return true;
    }

    private static void scheduleResultRead(Context app, String request, long deadline) {
        scheduleOperation.after(() -> {
            // These are GETs only. Never retry a scene or infer its success from dispatch.
            AdtPortalSession.Binding binding = AdtPortalSession.binding(app);
            if (binding == null) return;
            synchronized (PhoneAlarmState.class) {
                AdtLiveLedger.Snapshot value = read(preferences(app), binding).snapshot(SystemClock.elapsedRealtime(), boot(app));
                if (!request.equals(value.requestId)) return;
            }
            Snapshot value = refresh(app);
            PhoneStateLink.publish(app);
            if (value.pending && SystemClock.elapsedRealtime() < deadline) scheduleResultRead(app, request, deadline);
        }, 1_000);
    }

    /** Notification contents never enter the ledger; a missed notification is recovered by a GET. */
    static void hint(Context context) {
        Context app = context.getApplicationContext();
        if (AdtPortalSession.binding(app) == null || !HINT_QUEUED.compareAndSet(false, true)) return;
        try {
            scheduleOperation.after(() -> {
                try { refresh(app); PhoneStateLink.publish(app); }
                finally { HINT_QUEUED.set(false); }
            }, 500);
        } catch (RuntimeException ignored) { HINT_QUEUED.set(false); }
    }

    static String setupStatus(Context context) {
        Snapshot value = snapshot(context);
        switch (value.availability) {
            case READY: return "ADT live status: " + stateLabel(value.state) + ". Checked " + value.ageMillis / 1000 + " seconds ago.";
            case SETUP: return "Set up ADT live status to connect to your alarm's current reported state.";
            case NO_ACCESS: return "ADT sign-in has expired or needs verification. Open Set up ADT live status.";
            case STALE: return "Swipe to the watch tile or refresh to query ADT again.";
            case BUSY: return "Querying ADT for the result of the request.";
            case OFFLINE: return "Could not read ADT. Refresh to try another status check.";
            default: return "No verified ADT status. Open Set up ADT live status and check the selected system.";
        }
    }
    static AlarmAction actionFor(AlarmStateProtocol.State state) { return AlarmStateProtocol.action(state); }
    private static String stateLabel(AlarmStateProtocol.State state) {
        return state == AlarmStateProtocol.State.DISARMED ? "Disarmed"
            : state == AlarmStateProtocol.State.ARMED_STAY ? "Armed Stay"
            : state == AlarmStateProtocol.State.ARMED_AWAY ? "Armed Away" : "Unknown";
    }
    private static AdtPortalClient.Result queryPortal(Context context, long deadline) {
        FutureTask<AdtPortalClient.Session> session = new FutureTask<>(() -> AdtPortalSession.session(context));
        new Handler(Looper.getMainLooper()).post(session);
        try {
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0) return null;
            AdtPortalSession.Binding binding = AdtPortalSession.binding(context);
            if (binding == null) return null;
            // One round trip when the partition names the saved system as its owner; the client
            // itself reads the saved system to prove the binding when it does not.
            AdtPortalClient.Session portal = session.get(remaining, TimeUnit.MILLISECONDS);
            AdtPortalClient.Result result = new AdtPortalClient(portal).status(binding.systemId, binding.partitionId, deadline);
            // The host that answered an authenticated read is the one to try first next time.
            if (result.status == AdtPortalClient.Status.READY || result.status == AdtPortalClient.Status.BUSY)
                AdtPortalSession.recordVerifiedOrigin(context, portal.origin());
            return result;
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); return null; }
        catch (Exception error) { return null; }
        finally { session.cancel(false); }
    }
    private static synchronized void failed(Context context, AdtPortalSession.Binding binding, AdtPortalClient.Status status) {
        if (!AdtPortalSession.valid(context, binding)) return;
        SharedPreferences stored = preferences(context);
        SharedPreferences.Editor edit = stored.edit();
        if (!binding.id.equals(stored.getString("bindingId", ""))) edit.clear();
        storageFailed = !edit.putString("bindingId", binding.id).putString("queryStatus", status.name()).commit();
    }
    private static AdtLiveLedger read(SharedPreferences stored, AdtPortalSession.Binding binding) {
        Map<String, String> values = new LinkedHashMap<>();
        if (binding.id.equals(stored.getString("bindingId", "")))
            for (Map.Entry<String, ?> entry : stored.getAll().entrySet())
                if (entry.getValue() instanceof String) values.put(entry.getKey(), (String) entry.getValue());
        return AdtLiveLedger.restore(binding.systemId, binding.partitionId, values);
    }
    private static boolean write(Context context, AdtPortalSession.Binding binding, AdtLiveLedger ledger,
            AdtPortalClient.Status status, long started, int queryBoot) {
        if (!AdtPortalSession.valid(context, binding)) return false;
        SharedPreferences.Editor edit = preferences(context).edit().clear();
        for (Map.Entry<String, String> item : ledger.save().entrySet()) edit.putString(item.getKey(), item.getValue());
        storageFailed = !edit.putString("bindingId", binding.id).putString("queryStatus", status.name())
            .putLong("lastQueryStarted", started).putInt("lastQueryBoot", queryBoot).commit();
        return !storageFailed;
    }
    static int boot(Context context) {
        try { return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT); }
        catch (Exception error) { return -1; }
    }
    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
}
