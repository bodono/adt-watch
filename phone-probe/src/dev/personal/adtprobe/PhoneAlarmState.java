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
    /** A successful read this recent is answered from the ledger instead of querying ADT again. */
    static final long REUSE_MS = 3_000;
    /** Interval between confirmation reads after a command; each is an authenticated portal read. */
    static final long CONFIRMATION_POLL_MS = 2_500;
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

    /** Worker-only read that reuses a successful read younger than REUSE_MS. */
    static Snapshot refresh(Context context) { return refresh(context, REUSE_MS, -1); }

    /** Worker-only read; zero reuse forces a read unless one began after this call arrived. */
    static Snapshot refresh(Context context, long reuseMillis) { return refresh(context, reuseMillis, -1); }

    /**
     * Worker-only read. A read that began after this call arrived is always shared. A successful
     * read younger than reuseMillis that also began after notBeforeElapsed is reused, so the
     * watch's retries, the confirmation poll and a tap's preflight do not each cost ADT a query.
     * A failed read is never reused: the next caller tries again.
     */
    static Snapshot refresh(Context context, long reuseMillis, long notBeforeElapsed) {
        Context app = context.getApplicationContext();
        AdtPortalSession.Binding binding = AdtPortalSession.binding(app);
        if (binding == null) return snapshot(app);
        long arrived = SystemClock.elapsedRealtime(), deadline = arrived + QUERY_MS;
        boolean locked = false;
        try {
            locked = QUERY_LOCK.tryLock(QUERY_MS, TimeUnit.MILLISECONDS);
            // Another caller's read held the lock the whole time. That says nothing about ADT, so
            // do not record a failure that would turn a fresh observation OFFLINE for everyone.
            if (!locked) return snapshot(app);
            synchronized (PhoneAlarmState.class) {
                SharedPreferences stored = preferences(app);
                long lastStarted = stored.getLong("lastQueryStarted", -1), now = SystemClock.elapsedRealtime();
                if (binding.id.equals(stored.getString("bindingId", "")) && stored.getInt("lastQueryBoot", -1) == boot(app)
                        && lastReadSucceeded(stored) && (lastStarted >= arrived
                            || lastStarted > notBeforeElapsed && now >= lastStarted && now - lastStarted < reuseMillis))
                    return snapshot(app);
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
            long requestStarted;
            synchronized (PhoneAlarmState.class) {
                AdtLiveLedger.Snapshot value = read(preferences(app), binding).snapshot(SystemClock.elapsedRealtime(), boot(app));
                if (!request.equals(value.requestId)) return;
                requestStarted = value.requestStartedElapsed;
            }
            // Only a read begun after the request can confirm it; a recent one from the watch's
            // own query is reused. The watch polls on its own, so a hint goes out only on change.
            Snapshot before = snapshot(app);
            Snapshot value = refresh(app, REUSE_MS, requestStarted);
            if (changed(before, value)) PhoneStateLink.publish(app);
            if (value.pending && SystemClock.elapsedRealtime() < deadline) scheduleResultRead(app, request, deadline);
        }, CONFIRMATION_POLL_MS);
    }

    static boolean changed(Snapshot before, Snapshot after) {
        return before.state != after.state || before.availability != after.availability || before.pending != after.pending
            || !before.revision.equals(after.revision) || !before.completedRequest.equals(after.completedRequest);
    }

    private static boolean lastReadSucceeded(SharedPreferences stored) {
        String status = stored.getString("queryStatus", "");
        return "READY".equals(status) || "BUSY".equals(status);
    }

    /** Notification contents never enter the ledger; a missed notification is recovered by a GET. */
    static void hint(Context context) {
        Context app = context.getApplicationContext();
        if (AdtPortalSession.binding(app) == null || !HINT_QUEUED.compareAndSet(false, true)) return;
        try {
            scheduleOperation.after(() -> {
                // A notification means the state probably just changed, so this read is not reused.
                try { refresh(app, 0); PhoneStateLink.publish(app); }
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
            return new AdtPortalClient(session.get(remaining, TimeUnit.MILLISECONDS))
                .queryBound(binding.systemId, binding.partitionId, deadline);
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
