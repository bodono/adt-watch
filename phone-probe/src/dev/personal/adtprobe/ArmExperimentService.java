package dev.personal.adtprobe;

import android.app.Activity;
import android.app.Application;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

/** Bounded widget requests. Only configuration persists; every activation needs a fresh watch confirmation. */
public final class ArmExperimentService extends Service {
    private static final String PREFS = "arm_experiment_status";
    private static final String CHANNEL = "arm_widget_experiment";
    private static final int NOTICE = 3201;
    private static final long START_GRANT_MS = 5_000;
    private static final long PASSIVE_MS = 60_000;
    private static final long AUTHORISED_MS = 120_000;
    private static final long ROUTINE_MS = 30_000;
    private static final long RESULT_GRACE_MS = 2_000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final RoutinePrepareReplayGuard RECENT_PREPARES = new RoutinePrepareReplayGuard();
    private static volatile ArmExperimentService active;
    private static volatile StartGrant pending;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Executor mainExecutor = command -> handler.post(() -> runCallback(command));
    private final ArmReadinessWait readinessWait = new ArmReadinessWait();
    private StartGrant grant;
    private AlarmAction action;
    private WidgetHostSession host;
    private String boundNode;
    private String requestId;
    private String challengeId;
    private long challengeGeneration;
    private long challengeUntil;
    private long resultUntil;
    private volatile long sessionUntil;
    private volatile boolean authorised;
    private volatile boolean readySnapshot;
    private volatile boolean challengeIssued;
    private volatile boolean stopped;
    private volatile boolean finishing;
    private volatile String statusText = "Experiment active: preparing widget host.";
    private boolean started;
    private boolean foreground;
    private boolean prepareInFlight;
    private boolean commitInFlight;
    private boolean resultRecorded;
    private boolean armLease;
    private boolean passiveReadyLocked;
    private Boolean lastReady;
    private Boolean lastLocked;
    private long dispatchElapsed = -1;
    private long dispatchWall = -1;

    private void runCallback(Runnable command) {
        if (stopped) return;
        if (finishing && SystemClock.elapsedRealtime() >= resultUntil) { stopNow(null); return; }
        try { command.run(); }
        catch (RuntimeException error) { stopNow("Experiment callback failed. No authorization remains; alarm state is unverified."); }
    }

    /** Called only by the visible Activity's deliberate button/positive confirmation. */
    static void startFromVisibleActivity(Activity activity, boolean armAuthorised) {
        startFromVisibleActivity(activity, AlarmAction.ARM_STAY, armAuthorised);
    }

    static void startFromVisibleActivity(Activity activity, AlarmAction action, boolean armAuthorised) {
        requireMain();
        if (action == null) return;
        if (activity == null || activity.isFinishing() || activity.isDestroyed()
                || activity.getWindow() == null
                || activity.getWindow().getDecorView().getWindowVisibility() != View.VISIBLE
                || !activity.getWindow().getDecorView().isShown() || !unlocked(activity)) {
            if (activity != null) writeStatus(activity, "Experiment rejected: prepare it from the visible, unlocked phone screen.");
            return;
        }
        if (isRunning()) {
            writeStatus(activity, "An experiment is already active. Cancel it before preparing another.");
            return;
        }
        if (WidgetSetupActivity.hasListeningHost()) {
            writeStatus(activity, "Experiment rejected: close widget setup before preparing this test.");
            return;
        }
        discardPending(); // An expired queued start must not retain its lifecycle observer.
        StartGrant next = new StartGrant(activity, action, armAuthorised);
        pending = next;
        activity.getApplication().registerActivityLifecycleCallbacks(next);
        writeStatus(activity, "Experiment active: starting the bounded widget host.");
        MAIN.postDelayed(() -> {
            if (pending == next) {
                pending = null;
                next.close();
                writeStatus(activity.getApplicationContext(), "Experiment start expired. No authorization remains.");
            }
        }, START_GRANT_MS + 1);
        try {
            // Deliberately no extras: only the fresh in-process grant authorizes this start.
            activity.startForegroundService(new Intent(activity, ArmExperimentService.class));
        } catch (RuntimeException error) {
            if (pending == next) pending = null;
            next.close();
            writeStatus(activity, "Experiment could not start. No authorization remains.");
            Probe.event(activity, "Arm experiment start failed (" + error.getClass().getSimpleName() + ").");
        }
    }

    static boolean isRunning() {
        ArmExperimentService current = active;
        StartGrant queued = pending;
        return current != null && !current.stopped
            || queued != null && SystemClock.elapsedRealtime() <= queued.until;
    }

    /** Navigation callers run on main, so the old host closes before another host opens. */
    static void cancelForNavigation(Context context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Context app = context.getApplicationContext();
            MAIN.post(() -> cancelForNavigation(app));
            return;
        }
        StartGrant queued = pending;
        pending = null;
        if (queued != null) queued.close();
        ArmExperimentService current = active;
        if (current != null) current.stopNow("Experiment canceled by phone navigation. No further request is authorized.");
        else if (queued != null) writeStatus(context, "Experiment preparation canceled. No authorization remains.");
        context.stopService(new Intent(context, ArmExperimentService.class));
    }

    static String status(Context context) {
        ArmExperimentService current = active;
        if (current != null && !current.stopped) return current.statusText;
        SharedPreferences preferences = context.getSharedPreferences(PREFS, MODE_PRIVATE);
        String value = preferences.getString("text", "No background widget experiment has run.");
        if (pending == null && value.startsWith("Experiment active:"))
            return "The previous experiment has no recorded final result. No authorization survived; alarm state is unverified.";
        return value;
    }

    /** Compatibility entry for explicitly prepared tests; it cannot start a background service. */
    static void receive(MessageEvent event) { receive(null, event); }

    /** Only PREPARE can start readiness after one-time setup; COMMIT never creates a service. */
    static void receive(Context context, MessageEvent event) {
        if (event == null) return;
        final String source;
        final ArmExperimentProtocol.Message message;
        final String toggleRevision;
        try {
            String path = event.getPath();
            boolean toggle = AlarmStateProtocol.TOGGLE_PATH.equals(path);
            boolean prepare = ArmExperimentProtocol.PREPARE_PATH.equals(path);
            boolean commit = ArmExperimentProtocol.COMMIT_PATH.equals(path);
            if (!prepare && !commit && !toggle) return;
            source = event.getSourceNodeId();
            if (!validNode(source)) return;
            if (toggle) {
                AlarmStateProtocol.Tap tap = AlarmStateProtocol.parseTap(event.getData());
                if (tap == null) return;
                toggleRevision = tap.revision;
                message = ArmExperimentProtocol.parse(ArmExperimentProtocol.encodePrepare(tap.action, tap.request));
            } else {
                toggleRevision = null;
                message = ArmExperimentProtocol.parse(event.getData());
            }
            if (message == null || prepare && message.kind != ArmExperimentProtocol.Kind.PREPARE
                    || commit && message.kind != ArmExperimentProtocol.Kind.COMMIT) return;
        } catch (RuntimeException error) { return; }
        Context app = context == null ? null : context.getApplicationContext();
        MAIN.post(() -> {
            ArmExperimentService current = active;
            if (current != null && !current.stopped && !current.finishing) {
                // A new toggle may never adopt an unrelated already-prepared session.
                if (toggleRevision != null) {
                    declineApproved(app, source, message, AlarmStateProtocol.DeclineReason.UNAVAILABLE);
                    return;
                }
                try { current.handle(source, message); }
                catch (RuntimeException error) {
                    current.stopNow("Experiment message handling failed. No authorization remains; alarm state is unverified.");
                }
            } else if (app != null && message.kind == ArmExperimentProtocol.Kind.PREPARE) {
                startRoutineReadiness(app, source, message, toggleRevision);
            }
        });
    }

    private static void startRoutineReadiness(Context app, String source, ArmExperimentProtocol.Message message) {
        startRoutineReadiness(app, source, message, null);
    }

    private static void startRoutineReadiness(Context app, String source, ArmExperimentProtocol.Message message,
            String toggleRevision) {
        requireMain();
        if (message.kind != ArmExperimentProtocol.Kind.PREPARE) return;
        RoutineAccess.Snapshot permission = RoutineAccess.snapshot(app);
        if (permission == null || !permission.nodeId.equals(source)) return;
        if (isRunning() || !phoneLocked(app) || WidgetSetupActivity.hasListeningHost()) {
            if (toggleRevision != null) sendDeclined(app, source, message, AlarmStateProtocol.DeclineReason.UNAVAILABLE);
            return;
        }
        if (toggleRevision != null) {
            PhoneAlarmState.Snapshot state = PhoneAlarmState.snapshot(app);
            AlarmStateProtocol.DeclineReason reason = null;
            // A still-fresh observation is enough to colour the watch but not to click ADT's
            // scene: the preflight read itself must have succeeded.
            if (state.readFailed || state.availability != AlarmStateProtocol.Availability.READY)
                reason = AlarmStateProtocol.DeclineReason.UNAVAILABLE;
            else if (message.action == AlarmAction.DISARM && state.state == AlarmStateProtocol.State.DISARMED
                    || message.action == AlarmAction.ARM_STAY && state.state == AlarmStateProtocol.State.ARMED_STAY)
                reason = AlarmStateProtocol.DeclineReason.ALREADY_SATISFIED;
            else if (!PhoneAlarmState.matches(app, toggleRevision, message.action))
                reason = AlarmStateProtocol.DeclineReason.STATE_CHANGED;
            if (reason != null) {
                sendDeclined(app, source, message, reason);
                PhoneStateLink.publish(app); return;
            }
        }
        if (!RECENT_PREPARES.admit(source, message.action, message.requestId, SystemClock.elapsedRealtime())) return;
        discardPending();
        StartGrant next = new StartGrant(app, permission, message, toggleRevision);
        pending = next;
        MAIN.postDelayed(() -> {
            if (pending == next) {
                pending = null; next.close();
                writeStatus(app, "Watch readiness start expired. No alarm request was sent.");
            }
        }, START_GRANT_MS + 1);
        try {
            // No action, command, challenge or durable authorization is carried in this Intent.
            app.startForegroundService(new Intent(app, ArmExperimentService.class));
        } catch (RuntimeException error) {
            if (pending == next) pending = null;
            next.close();
            writeStatus(app, "Android could not start watch readiness. No alarm request was sent.");
            Probe.event(app, "Routine readiness startup unavailable; no retry.");
        }
    }

    private static void declineApproved(Context app, String source, ArmExperimentProtocol.Message message,
            AlarmStateProtocol.DeclineReason reason) {
        if (app == null) return;
        RoutineAccess.Snapshot access = RoutineAccess.snapshot(app);
        if (access != null && source.equals(access.nodeId)) sendDeclined(app, source, message, reason);
    }

    private static void sendDeclined(Context app, String source, ArmExperimentProtocol.Message message,
            AlarmStateProtocol.DeclineReason reason) {
        try {
            Wearable.getMessageClient(app).sendMessage(source, AlarmStateProtocol.DECLINED_PATH,
                    new AlarmStateProtocol.Declined(message.action, message.requestId, reason).encode());
        } catch (RuntimeException ignored) { /* The watch deadline still ends this unsent attempt. */ }
    }

    @Override public void onCreate() {
        super.onCreate();
        active = this;
        try {
            writeStatus(this, statusText);
            NotificationManager notifications = getSystemService(NotificationManager.class);
            notifications.createNotificationChannel(new NotificationChannel(CHANNEL,
                "Bounded widget experiment", NotificationManager.IMPORTANCE_LOW));
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(NOTICE, notice(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
            else startForeground(NOTICE, notice());
            foreground = true;
        } catch (RuntimeException error) {
            discardPending();
            stopNow("Experiment could not enter foreground execution. No authorization remains.");
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (stopped) return START_NOT_STICKY;
        if (started) {
            discardPending();
            stopNow("Experiment canceled: duplicate service start was rejected.");
            return START_NOT_STICKY;
        }
        started = true;
        grant = pending;
        pending = null;
        if (intent == null || (flags & (START_FLAG_RETRY | START_FLAG_REDELIVERY)) != 0
                || grant == null || SystemClock.elapsedRealtime() > grant.until
                || WidgetSetupActivity.hasListeningHost()) {
            stopNow("Experiment rejected: no fresh preparation or widget setup still owns the host.");
            return START_NOT_STICKY;
        }
        action = grant.action;
        if (grant.routine != null && (!RoutineAccess.stillValid(this, grant.routine) || !phoneLocked(this)
                || grant.toggleRevision != null && !PhoneAlarmState.matches(this, grant.toggleRevision, action))) {
            stopNow("Watch readiness rejected: setup or phone lock changed before startup.");
            return START_NOT_STICKY;
        }
        armLease = authorised = grant.authorised;
        sessionUntil = SystemClock.elapsedRealtime() + (grant.routine != null ? ROUTINE_MS
            : authorised ? AUTHORISED_MS : PASSIVE_MS);
        if (grant.routine != null) {
            boundNode = grant.routine.nodeId;
            readinessWait.offer(boundNode, grant.initialRequest, SystemClock.elapsedRealtime(), sessionUntil);
        }
        try {
            host = new WidgetHostSession(this, action);
            host.start();
            Probe.event(this, grant.routine != null
                ? "Routine readiness: one action-bound request adopted; lease=30000ms; fresh watch confirmation required."
                : authorised ? "Arm experiment: one in-memory authorization adopted; lease=120000ms."
                : "Arm experiment: passive host check started; lease=60000ms; activation is not authorized.");
            handler.post(tick);
            if (authorised) discoverNode();
        } catch (RuntimeException error) {
            stopNow("Experiment rejected: background widget host could not start.");
        }
        return START_NOT_STICKY;
    }

    private void discoverNode() {
        try {
            Wearable.getNodeClient(this).getConnectedNodes().addOnCompleteListener(mainExecutor, task -> {
                if (!live()) return;
                List<Node> nodes = task.isSuccessful() ? task.getResult() : null;
                if (nodes == null || nodes.size() != 1 || !validNode(nodes.get(0).getId())) {
                    stopNow("Experiment rejected: exactly one connected watch is required.");
                    return;
                }
                if (grant.routine != null && !grant.routine.nodeId.equals(nodes.get(0).getId())) {
                    stopNow("Watch readiness rejected: connected watch differs from setup.");
                    return;
                }
                boundNode = nodes.get(0).getId();
                Probe.event(this, "Arm experiment: exactly one connected watch selected; no command sent.");
                refreshStatus();
            });
        } catch (RuntimeException error) {
            stopNow("Experiment rejected: connected watch discovery failed.");
        }
    }

    private boolean live() {
        if (stopped || finishing || active != this) return false;
        if (SystemClock.elapsedRealtime() >= sessionUntil) {
            stopNow(armLease ? "Experiment expired. No further widget request is authorized."
                : passiveReadyLocked ? "Passive check ended: the widget was ready while the phone was locked. This check did not activate a scene."
                : "Passive check ended without confirmed locked-phone readiness. This check did not activate a scene.");
            return false;
        }
        if (grant != null && grant.routine != null && !RoutineAccess.stillValid(this, grant.routine)) {
            stopNow("Watch request canceled: access or widget setup changed.");
            return false;
        }
        if (grant != null && grant.toggleRevision != null
                && !PhoneAlarmState.matches(this, grant.toggleRevision, action)) {
            PhoneStateLink.publish(this);
            stopNow("Watch request canceled: ADT's reported state changed or became unavailable.");
            return false;
        }
        if (WidgetSetupActivity.hasListeningHost()) {
            stopNow("Experiment canceled: widget setup took ownership of the host.");
            return false;
        }
        return true;
    }

    private boolean fullyLocked() {
        return phoneLocked(this) && grant != null && (grant.routine != null || !grant.ownerVisible);
    }

    private static boolean phoneLocked(Context context) {
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return keyguard != null && keyguard.isKeyguardLocked() && keyguard.isDeviceLocked();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!live()) return;
            try {
                readySnapshot = host != null && host.isReady();
                boolean locked = fullyLocked();
                if (!armLease && readySnapshot && locked) passiveReadyLocked = true;
                if (lastReady == null || lastReady != readySnapshot || lastLocked == null || lastLocked != locked) {
                    Probe.event(ArmExperimentService.this, "Arm experiment host snapshot: ready=" + readySnapshot
                        + ", fullyLocked=" + locked + ", authorized=" + authorised + ".");
                    lastReady = readySnapshot; lastLocked = locked;
                }
                if (challengeIssued && (!locked || !readySnapshot || host.renderGeneration() != challengeGeneration)) {
                    rejectChallenge("Arm experiment rejected: widget changed after the challenge.");
                    return;
                }
                if (challengeIssued && SystemClock.elapsedRealtime() >= challengeUntil) {
                    rejectChallenge("Arm experiment challenge expired; no further request is authorized.");
                    return;
                }
                advancePrepare();
                if (stopped || finishing) return;
                refreshStatus();
                handler.postDelayed(this, 250);
            } catch (RuntimeException error) {
                stopNow("Experiment rejected: widget readiness could not be verified.");
            }
        }
    };

    private void refreshStatus() {
        if (stopped || finishing) return;
        if (challengeIssued) publish("Experiment active: watch challenge issued. Confirmation expires after 20 seconds.");
        else if (readinessWait.hasRequest()) publish("Experiment active: checking watch readiness for up to five seconds.");
        else if (!readySnapshot) publish("Experiment active: waiting for the verified idle " + action.label() + " widget.");
        else if (!authorised) publish(fullyLocked()
            ? "Experiment active: passive widget is ready while the phone is locked. No scene can be activated."
            : "Experiment active: passive widget is ready. Lock the phone to check background readiness; no scene can be activated.");
        else if (boundNode == null) publish("Experiment active: widget ready; checking the single connected watch.");
        else publish(fullyLocked()
            ? "Experiment active: widget ready and phone locked. One watch-confirmed " + action.label() + " request may be accepted."
            : "Experiment active: widget and watch ready. Lock this phone, then prepare and confirm on the watch.");
    }

    private void handle(String source, ArmExperimentProtocol.Message message) {
        if (!live() || !authorised || message.action != action) return;
        if (message.kind == ArmExperimentProtocol.Kind.PREPARE) {
            if (challengeIssued || prepareInFlight || commitInFlight
                    || boundNode != null && !boundNode.equals(source)) return;
            if (readinessWait.offer(source, message.requestId, SystemClock.elapsedRealtime(), sessionUntil)) {
                Probe.event(this, "Arm experiment: first readiness request accepted; wait is at most 5000ms.");
                advancePrepare();
            }
        } else if (message.kind == ArmExperimentProtocol.Kind.COMMIT) {
            if (boundNode == null || !boundNode.equals(source)
                    || !challengeIssued || commitInFlight || !message.requestId.equals(requestId)
                    || !message.challengeId.equals(challengeId)) return;
            commitInFlight = true;
            checkCurrentNode(source, () -> commit(source, message));
        }
    }

    private void advancePrepare() {
        if (!readinessWait.hasRequest()) return;
        if (!live() || !authorised) { readinessWait.clear(); return; }
        if (!readinessWait.isFresh(SystemClock.elapsedRealtime())) {
            stopNow("Experiment readiness wait expired. No alarm request was sent.");
            return;
        }
        if (boundNode != null && !readinessWait.matchesNode(boundNode)) {
            stopNow("Experiment rejected: readiness source did not match the selected watch.");
            return;
        }
        if (challengeIssued || commitInFlight) return;
        if (!readinessWait.canProceed(boundNode, host != null && host.isReady(),
                fullyLocked(), SystemClock.elapsedRealtime())) {
            if (!readinessWait.hasRequest())
                stopNow("Experiment readiness wait expired. No alarm request was sent.");
            return;
        }
        if (prepareInFlight) return;
        prepareInFlight = true;
        String source = readinessWait.source();
        String request = readinessWait.requestId();
        checkCurrentNode(source, () -> prepare(source, request));
    }

    private void checkCurrentNode(String source, Runnable continuation) {
        try {
            Wearable.getNodeClient(this).getConnectedNodes().addOnCompleteListener(mainExecutor, task -> {
                if (!live()) return;
                List<Node> nodes = task.isSuccessful() ? task.getResult() : null;
                if (nodes == null || nodes.size() != 1 || !source.equals(nodes.get(0).getId())
                        || !source.equals(boundNode)) {
                    if (challengeIssued) rejectChallenge("Arm experiment rejected: connected watch changed.");
                    else stopNow("Experiment rejected: connected watch could not be reverified.");
                    return;
                }
                continuation.run();
            });
        } catch (RuntimeException error) {
            if (challengeIssued) rejectChallenge("Arm experiment rejected: watch verification failed.");
            else stopNow("Experiment rejected: watch verification failed.");
        }
    }

    private void prepare(String source, String request) {
        prepareInFlight = false;
        if (!live() || !authorised || challengeIssued) return;
        if (!readinessWait.isFresh(SystemClock.elapsedRealtime())) {
            stopNow("Experiment readiness wait expired. No alarm request was sent.");
            return;
        }
        if (!source.equals(boundNode) || !readinessWait.matchesNode(source)
                || !request.equals(readinessWait.requestId())) {
            stopNow("Experiment rejected: readiness request changed.");
            return;
        }
        if (!readinessWait.canProceed(boundNode, host != null && host.isReady(),
                fullyLocked(), SystemClock.elapsedRealtime())) {
            if (!readinessWait.hasRequest())
                stopNow("Experiment readiness wait expired. No alarm request was sent.");
            return;
        }
        // Widget verification can cross the deadline; a late node callback never creates a challenge.
        if (!readinessWait.isFresh(SystemClock.elapsedRealtime())) {
            stopNow("Experiment readiness wait expired. No alarm request was sent.");
            return;
        }
        readinessWait.clear();
        requestId = request;
        challengeId = UUID.randomUUID().toString();
        challengeGeneration = host.renderGeneration();
        challengeUntil = Math.min(sessionUntil,
            SystemClock.elapsedRealtime() + ArmExperimentProtocol.PHONE_CHALLENGE_TIMEOUT_MS);
        challengeIssued = true;
        Probe.event(this, "Arm experiment: one watch challenge issued; no alarm request sent.");
        refreshStatus();
        try {
            Wearable.getMessageClient(this).sendMessage(source, ArmExperimentProtocol.CHALLENGE_PATH,
                ArmExperimentProtocol.encodeChallenge(action, requestId, challengeId))
                .addOnCompleteListener(mainExecutor, task -> {
                    if (!live() || commitInFlight) return;
                    if (!task.isSuccessful()) stopNow("Experiment stopped: watch challenge submission failed.");
                });
        } catch (RuntimeException error) {
            stopNow("Experiment stopped: watch challenge could not be submitted.");
        }
    }

    private void commit(String source, ArmExperimentProtocol.Message message) {
        if (!live()) return;
        boolean requested = false;
        try {
            if (!authorised || message.action != action || !challengeIssued || !source.equals(boundNode)
                    || !message.requestId.equals(requestId) || !message.challengeId.equals(challengeId)
                    || SystemClock.elapsedRealtime() >= challengeUntil || !fullyLocked()
                    || host == null || !host.isReady() || host.renderGeneration() != challengeGeneration) {
                rejectChallenge("Arm experiment rejected: confirmation conditions were no longer valid.");
                return;
            }
            // This is the only activation call. Revoke the service permit before foreign UI code.
            authorised = false;
            dispatchElapsed = SystemClock.elapsedRealtime();
            dispatchWall = System.currentTimeMillis();
            KeyguardManager keyguard = getSystemService(KeyguardManager.class);
            boolean locked = keyguard != null && keyguard.isKeyguardLocked();
            boolean deviceLocked = keyguard != null && keyguard.isDeviceLocked();
            boolean visible = grant == null || (grant.routine == null && grant.ownerVisible);
            Probe.event(this, "Alarm experiment dispatch guards: action=" + action.name() + ", elapsed=" + dispatchElapsed + "ms, wall="
                + dispatchWall + "ms, keyguardLocked=" + locked + ", deviceLocked=" + deviceLocked
                + ", preparingActivityVisible=" + visible + ". Authorization consumed before activation.");
            // Recheck the deadline after logging; delayed work never becomes a queued command.
            requested = host.activateOnce(challengeGeneration, () ->
                fullyLocked() && SystemClock.elapsedRealtime() < challengeUntil
                    && SystemClock.elapsedRealtime() < sessionUntil && !WidgetSetupActivity.hasListeningHost()
                    && (grant.routine == null || RoutineAccess.stillValid(this, grant.routine))
                    && (grant.toggleRevision == null || PhoneAlarmState.beginCommand(this, grant.toggleRevision, action, requestId)))
                == WidgetHostSession.Activation.ATTEMPTED;
        } catch (RuntimeException error) {
            Probe.event(this, "Arm experiment activation returned an error; alarm state is unverified.");
        }
        finishResult(requested ? ArmExperimentProtocol.Outcome.REQUESTED : ArmExperimentProtocol.Outcome.REJECTED);
    }

    private void rejectChallenge(String reason) {
        Probe.event(this, reason);
        if (challengeIssued && requestId != null && challengeId != null && boundNode != null)
            finishResult(ArmExperimentProtocol.Outcome.REJECTED);
        else stopNow(reason);
    }

    private void finishResult(ArmExperimentProtocol.Outcome outcome) {
        if (stopped || finishing) return;
        authorised = false; finishing = true; resultRecorded = true;
        readinessWait.clear();
        resultUntil = SystemClock.elapsedRealtime() + RESULT_GRACE_MS;
        handler.removeCallbacksAndMessages(null);
        closeHost();
        publish(outcome == ArmExperimentProtocol.Outcome.REQUESTED
            ? action.label() + " widget click attempted once. Alarm state is unverified; no retry will occur."
            : action.label() + " experiment rejected. Alarm state is unverified; no retry will occur.");
        Probe.event(this, outcome == ArmExperimentProtocol.Outcome.REQUESTED
            ? "Arm experiment result: REQUESTED means native click attempted only, not alarm state."
            : "Arm experiment result: REJECTED; alarm state is unverified.");
        handler.postDelayed(() -> stopNow(null), RESULT_GRACE_MS);
        try {
            Wearable.getMessageClient(this).sendMessage(boundNode, ArmExperimentProtocol.RESULT_PATH,
                ArmExperimentProtocol.encodeResult(action, requestId, challengeId, outcome))
                .addOnCompleteListener(mainExecutor, task -> {
                    if (stopped) return;
                    Probe.event(this, task.isSuccessful()
                        ? "Arm experiment result submitted; watch receipt is unverified."
                        : "Arm experiment result submission failed; no retry will occur.");
                    stopNow(null);
                });
        } catch (RuntimeException error) {
            Probe.event(this, "Arm experiment result could not be submitted; no retry will occur.");
            stopNow(null);
        }
    }

    private Notification notice() {
        PendingIntent open = PendingIntent.getActivity(this, 3201,
            new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("ADT watch request")
            .setContentText("Temporary watch session. Tap to cancel; expires automatically.")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE);
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return builder.build();
    }

    private void publish(String value) {
        if (value.equals(statusText)) return;
        statusText = value;
        writeStatus(this, value);
    }
    private static void writeStatus(Context context, String value) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("text", value).putLong("time", System.currentTimeMillis()).apply();
    }
    private void closeHost() {
        readySnapshot = false;
        if (host != null) {
            try { host.close(); } catch (RuntimeException ignored) { }
            host = null;
        }
    }
    private void stopNow(String reason) {
        if (stopped) return;
        stopped = true; authorised = false;
        readinessWait.clear();
        handler.removeCallbacksAndMessages(null);
        closeHost();
        if (grant != null) { grant.close(); grant = null; }
        if (reason != null && !resultRecorded) {
            publish(reason);
            Probe.event(this, reason);
        }
        requestId = challengeId = boundNode = null;
        challengeIssued = false;
        if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; }
        if (active == this) active = null;
        stopSelf();
    }
    private static void discardPending() {
        StartGrant queued = pending; pending = null;
        if (queued != null) queued.close();
    }

    @Override public void onTimeout(int startId) { stopNow("Experiment ended at Android's service timeout. No authorization remains."); }
    @Override public void onTimeout(int startId, int type) { onTimeout(startId); }
    @Override public void onDestroy() {
        stopNow("Experiment service stopped. No authorization remains; alarm state is unverified.");
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override protected void dump(FileDescriptor fd, PrintWriter writer, String[] arguments) {
        writer.println("action=" + (action == null ? "none" : action.name()));
        writer.println("running=" + (!stopped) + " finishing=" + finishing);
        writer.println("authorized=" + authorised + " hostReady=" + readySnapshot);
        writer.println("nodeBound=" + (boundNode != null) + " challengeIssued=" + challengeIssued);
        writer.println("readinessPending=" + readinessWait.hasRequest());
        writer.println("leaseRemainingMs=" + Math.max(0, sessionUntil - SystemClock.elapsedRealtime()));
        writer.println("dispatchElapsedMs=" + dispatchElapsed + " dispatchWallMs=" + dispatchWall);
        if (host != null) writer.println("hostDiagnostics=" + host.diagnostics());
        writer.println("alarmState=unverified");
    }

    private static boolean unlocked(Context context) {
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return keyguard != null && !keyguard.isKeyguardLocked() && !keyguard.isDeviceLocked();
    }
    private static boolean validNode(String value) {
        if (value == null || value.isEmpty() || value.length() > 256) return false;
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return false;
        return true;
    }
    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Main thread required");
    }

    private static final class StartGrant implements Application.ActivityLifecycleCallbacks {
        final WeakReference<Activity> owner;
        final Application application;
        final boolean authorised;
        final AlarmAction action;
        final RoutineAccess.Snapshot routine;
        final String initialRequest;
        final String toggleRevision;
        final long until = SystemClock.elapsedRealtime() + START_GRANT_MS;
        volatile boolean ownerVisible = true;
        private boolean closed;
        StartGrant(Activity activity, boolean authorised) {
            this(activity, AlarmAction.ARM_STAY, authorised);
        }
        StartGrant(Activity activity, AlarmAction action, boolean authorised) {
            if (action == null) throw new IllegalArgumentException("An action is required");
            owner = new WeakReference<>(activity); application = activity.getApplication(); this.authorised = authorised;
            this.action = action; routine = null; initialRequest = null; toggleRevision = null;
        }
        StartGrant(Context context, RoutineAccess.Snapshot routine, ArmExperimentProtocol.Message prepare) {
            this(context, routine, prepare, null);
        }
        StartGrant(Context context, RoutineAccess.Snapshot routine, ArmExperimentProtocol.Message prepare,
                String toggleRevision) {
            if (routine == null || prepare == null || prepare.kind != ArmExperimentProtocol.Kind.PREPARE)
                throw new IllegalArgumentException("A readiness request and setup are required");
            owner = new WeakReference<>(null); application = (Application) context.getApplicationContext();
            authorised = true; action = prepare.action; this.routine = routine;
            initialRequest = prepare.requestId; ownerVisible = false;
            this.toggleRevision = toggleRevision;
        }
        void close() {
            if (!closed) { closed = true; application.unregisterActivityLifecycleCallbacks(this); owner.clear(); }
        }
        @Override public void onActivityStarted(Activity activity) { if (owner.get() == activity) ownerVisible = true; }
        @Override public void onActivityStopped(Activity activity) { if (owner.get() == activity) ownerVisible = false; }
        @Override public void onActivityDestroyed(Activity activity) { if (owner.get() == activity) ownerVisible = false; }
        @Override public void onActivityCreated(Activity activity, Bundle state) { }
        @Override public void onActivityResumed(Activity activity) { }
        @Override public void onActivityPaused(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    }
}
