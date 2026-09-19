package dev.personal.adtprobe;

import android.app.Activity;
import android.app.Application;
import android.app.KeyguardManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/**
 * Real service rejection/cancellation paths with a paused local main loop. No connected node,
 * ADT package, widget action, or GMS client is supplied. Service reflection observes state only.
 * The sole fixture write supplies SDK35's missing window-visibility state on its attached decor.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = RoutineAccessTest.HostShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class ArmExperimentServiceTest {
    private static final String REQUEST = "12345678-1234-4234-8234-123456789abc";
    private static final String CHALLENGE = "abcdef12-1234-4234-8234-123456789abc";
    private Application context;
    private ActivityController<ArmExperimentActivity> ownerController;
    private Activity owner;
    private ServiceController<ArmExperimentService> serviceController;
    private ArmExperimentService service;
    private ArmExperimentService.NodeSource originalNodeSource;
    private ArmExperimentService.Responder originalResponder;
    private PhoneAlarmState.Query originalQuery;
    private PhoneAlarmState.Schedule originalSchedule;
    private final List<AlarmStateProtocol.Declined> declines = new ArrayList<>();
    private AlarmStateProtocol.State backend;
    private int reads;

    @Before public void cleanStart() {
        context = RuntimeEnvironment.getApplication();
        originalNodeSource = ReflectionHelpers.getStaticField(ArmExperimentService.class, "nodeSource");
        originalResponder = ReflectionHelpers.getStaticField(ArmExperimentService.class, "responder");
        originalQuery = PhoneAlarmState.queryOperation; originalSchedule = PhoneAlarmState.scheduleOperation;
        ReflectionHelpers.setStaticField(ArmExperimentService.class, "responder",
            (ArmExperimentService.Responder) (ignored, node, path, payload) -> declines.add(AlarmStateProtocol.parseDeclined(payload)));
        ArmExperimentService.cancelForNavigation(context);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        while (Shadows.shadowOf(context).getNextStartedService() != null) { }
        Shadows.shadowOf(context.getSystemService(KeyguardManager.class)).setKeyguardLocked(false);
        Shadows.shadowOf(context.getSystemService(KeyguardManager.class)).setIsDeviceLocked(false);
    }

    @After public void close() {
        ReflectionHelpers.setStaticField(ArmExperimentService.class, "nodeSource", originalNodeSource);
        ReflectionHelpers.setStaticField(ArmExperimentService.class, "responder", originalResponder);
        PhoneAlarmState.queryOperation = originalQuery; PhoneAlarmState.scheduleOperation = originalSchedule;
        RoutineAccess.disable(context);
        ArmExperimentService.cancelForNavigation(context);
        if (serviceController != null) serviceController.destroy();
        if (ownerController != null) ownerController.pause().stop().destroy();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void forgedExtrasAndRestartFlagsCannotReplaceAnInProcessGrant() throws Exception {
        int[] flags = {0, Service.START_FLAG_RETRY, Service.START_FLAG_REDELIVERY,
            Service.START_FLAG_RETRY | Service.START_FLAG_REDELIVERY};
        for (int flag : flags) {
            createService();
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(forgedStart(), flag, 1));
            assertRejectedWithoutHost();
            serviceController.destroy();
            serviceController = null;
        }
        assertNull(Shadows.shadowOf(context).getNextStartedService());
    }

    @Test public void nullRestartCannotAdoptEvenAFreshPassiveGrant() throws Exception {
        queueGrant(false);
        Object queued = observe(null, "pending");
        createService();
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1));
        assertRejectedWithoutHost();
        assertGrantClosed(queued);
    }

    @Test public void retryFlagRejectsAndClosesAFreshPassiveGrant() throws Exception {
        queueGrant(false);
        Object queued = observe(null, "pending");
        createService();
        assertEquals(Service.START_NOT_STICKY,
            service.onStartCommand(new Intent(context, ArmExperimentService.class), Service.START_FLAG_RETRY, 1));
        assertRejectedWithoutHost();
        assertGrantClosed(queued);
    }

    @Test public void incomingPrepareAndCommitNeverStartAnExperimentService() throws Exception {
        ArmExperimentService.receive(message(ArmExperimentProtocol.PREPARE_PATH,
            ArmExperimentProtocol.encodePrepare(AlarmAction.ARM_STAY, REQUEST)));
        ArmExperimentService.receive(message(ArmExperimentProtocol.COMMIT_PATH,
            ArmExperimentProtocol.encodeCommit(AlarmAction.ARM_STAY, REQUEST, CHALLENGE)));
        ArmExperimentService.receive(null);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(ArmExperimentService.isRunning());
        assertNull(observe(null, "active"));
        assertNull(observe(null, "pending"));
        assertNull("Message receipt must not start a background service",
            Shadows.shadowOf(context).getNextStartedService());
    }

    @Test public void navigationRevokesQueuedArmAuthorizationBeforeServiceDelivery() throws Exception {
        queueGrant(true);
        Object queued = observe(null, "pending");
        assertEquals(Boolean.TRUE, member(queued, "authorised"));
        ArmExperimentService.cancelForNavigation(context);
        assertFalse(ArmExperimentService.isRunning());
        assertNull(observe(null, "pending"));
        assertGrantClosed(queued);

        // Android could still deliver the previously queued start; it must now fail closed.
        createService();
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(forgedStart(), 0, 1));
        assertRejectedWithoutHost();
        ArmExperimentService.receive(message(ArmExperimentProtocol.COMMIT_PATH,
            ArmExperimentProtocol.encodeCommit(AlarmAction.ARM_STAY, REQUEST, CHALLENGE)));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull(Shadows.shadowOf(context).getNextStartedService());
    }

    @Test public void queuedArmAuthorizationExpiresWithoutCreatingTheService() throws Exception {
        queueGrant(true);
        Object queued = observe(null, "pending");
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_001));
        assertFalse(ArmExperimentService.isRunning());
        assertNull(observe(null, "pending"));
        assertGrantClosed(queued);
        createService();
        assertEquals(Service.START_NOT_STICKY,
            service.onStartCommand(new Intent(context, ArmExperimentService.class), 0, 1));
        assertRejectedWithoutHost();
    }

    @Test public void passiveStartIgnoresActionExtrasAndCannotIssueAChallengeOrActivate() throws Exception {
        queueGrant(false);
        createService();
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(forgedStart(), 0, 1));
        assertTrue("The passive session must actually start for this check", ArmExperimentService.isRunning());
        assertEquals(Boolean.FALSE, observe(service, "authorised"));
        Object passiveHost = observe(service, "host");
        assertNotNull("Exercise a real passive host, rather than a rejected service", passiveHost);

        ArmExperimentService.receive(message(ArmExperimentProtocol.PREPARE_PATH,
            ArmExperimentProtocol.encodePrepare(AlarmAction.ARM_STAY, REQUEST)));
        ArmExperimentService.receive(message(ArmExperimentProtocol.COMMIT_PATH,
            ArmExperimentProtocol.encodeCommit(AlarmAction.ARM_STAY, REQUEST, CHALLENGE)));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Boolean.FALSE, observe(service, "authorised"));
        assertEquals(Boolean.FALSE, observe(service, "prepareInFlight"));
        assertEquals(Boolean.FALSE, observe(service, "commitInFlight"));
        assertEquals(Boolean.FALSE, observe(service, "challengeIssued"));
        assertEquals(-1L, observe(service, "dispatchElapsed"));
        assertEquals(Boolean.FALSE, member(passiveHost, "consumed"));
        assertNull(Shadows.shadowOf(context).getNextStartedService());

        ArmExperimentService.cancelForNavigation(context);
        assertFalse(ArmExperimentService.isRunning());
        assertNull(observe(null, "active"));
        assertNull(observe(null, "pending"));
        assertNull(observe(service, "host"));
        assertEquals(Boolean.TRUE, member(passiveHost, "closed"));
        assertEquals(Boolean.FALSE, member(passiveHost, "consumed"));
    }

    @Test public void lockedActivityLifecycleBounceKeepsTheRunningPassiveExperiment() throws Exception {
        Object passiveHost = startPassiveExperiment();
        phoneLocked(true);
        ownerController.pause().stop();
        assertTrue("Stopping the preparation screen must preserve its background experiment",
            ArmExperimentService.isRunning());

        // Mirrors the dock/dream lifecycle: Android starts/resumes this Activity while both
        // locks are still engaged. This is not an unlocked user return to the preparation UI.
        ownerController.start().resume();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100));
        assertTrue("A locked resume must not cancel the experiment", ArmExperimentService.isRunning());
        assertSame(passiveHost, observe(service, "host"));
        assertEquals(Boolean.TRUE, member(owner, "leftScreen"));
        assertNoActivation(passiveHost);
    }

    @Test public void actualUnlockedReturnCancelsTheRunningPassiveExperiment() throws Exception {
        Object passiveHost = startPassiveExperiment();
        phoneLocked(true);
        ownerController.pause().stop();
        assertTrue(ArmExperimentService.isRunning());

        phoneLocked(false);
        ownerController.start().resume();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse("Returning to the unlocked preparation screen must revoke the experiment",
            ArmExperimentService.isRunning());
        assertNull(observe(service, "host"));
        assertEquals(Boolean.TRUE, member(passiveHost, "closed"));
        assertNoActivation(passiveHost);
    }

    @Test public void unlockingAnAlreadyResumedActivityCancelsOnTheNextUiTick() throws Exception {
        Object passiveHost = startPassiveExperiment();
        phoneLocked(true);
        ownerController.pause().stop();
        ownerController.start().resume();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue("The locked lifecycle bounce must leave the experiment running",
            ArmExperimentService.isRunning());
        assertEquals(Boolean.TRUE, member(owner, "leftScreen"));

        // No second onResume: the window is already resumed when its lock state changes.
        phoneLocked(false);
        assertTrue("The test must begin with a live experiment before the UI recheck",
            ArmExperimentService.isRunning());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
        assertFalse("The next UI tick must catch an unlock without another lifecycle callback",
            ArmExperimentService.isRunning());
        assertNull(observe(service, "host"));
        assertEquals(Boolean.TRUE, member(passiveHost, "closed"));
        assertNoActivation(passiveHost);
    }

    @Test public void disarmPreparationCannotBeRetargetedByArmMessagesOrIntentExtras() throws Exception {
        queueGrant(AlarmAction.DISARM, true);
        Object queued = observe(null, "pending");
        assertEquals(AlarmAction.DISARM, member(queued, "action"));
        createService();
        service.onStartCommand(forgedStart().putExtra("action", "ARM_STAY"), 0, 1);
        assertEquals(AlarmAction.DISARM, observe(service, "action"));
        Object disarmHost = observe(service, "host");
        assertNotNull(disarmHost);
        assertEquals(AlarmAction.DISARM, member(disarmHost, "action"));
        ArmExperimentService.receive(message(ArmExperimentProtocol.PREPARE_PATH,
            ArmExperimentProtocol.encodePrepare(AlarmAction.ARM_STAY, REQUEST)));
        ArmExperimentService.receive(message(ArmExperimentProtocol.COMMIT_PATH,
            ArmExperimentProtocol.encodeCommit(AlarmAction.ARM_STAY, REQUEST, CHALLENGE)));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(((ArmReadinessWait) observe(service, "readinessWait")).hasRequest());
        assertEquals(Boolean.FALSE, observe(service, "challengeIssued"));
        assertEquals(-1L, observe(service, "dispatchElapsed"));
        assertEquals(Boolean.FALSE, member(disarmHost, "consumed"));
    }

    @Test public void preparedExperimentAdoptsOneMatchingWatchTapAndItsStateRevision() throws Exception {
        String revision = installLiveState(AlarmStateProtocol.State.DISARMED);
        // One inert connected node, delivered through the service's own executor like the real client.
        installNode("local-test-watch");
        queueGrant(AlarmAction.ARM_STAY, true);
        createService();
        assertEquals(Service.START_NOT_STICKY,
            service.onStartCommand(new Intent(context, ArmExperimentService.class), 0, 1));
        Object grant = observe(service, "grant");
        assertNull(member(grant, "toggleRevision"));
        ArmReadinessWait wait = (ArmReadinessWait) observe(service, "readinessWait");

        String stale = UUID.randomUUID().toString();
        toggle(AlarmAction.ARM_STAY, stale, UUID.randomUUID().toString());
        assertNull("A tap for a state the phone does not currently report is not adopted", member(grant, "toggleRevision"));
        assertFalse(wait.hasRequest());
        assertEquals("...and the watch is told why", 1, declines.size());
        assertEquals(AlarmStateProtocol.DeclineReason.STATE_CHANGED, declines.get(0).reason);
        toggle(AlarmAction.DISARM, stale, revision);
        assertNull("A tap for the other action is not adopted", member(grant, "toggleRevision"));
        assertFalse(wait.hasRequest());

        toggle(AlarmAction.ARM_STAY, REQUEST, revision);
        assertEquals("The matching tap binds the session to the displayed state revision", revision, member(grant, "toggleRevision"));
        assertEquals("local-test-watch", observe(service, "boundNode"));
        assertTrue(ArmExperimentService.isRunning());
        assertTrue(wait.hasRequest());
        assertEquals(REQUEST, wait.requestId());
        assertTrue(wait.matchesNode("local-test-watch"));
        assertEquals(Boolean.FALSE, observe(service, "challengeIssued"));
        assertEquals(-1L, observe(service, "dispatchElapsed"));

        toggle(AlarmAction.ARM_STAY, UUID.randomUUID().toString(), revision);
        assertEquals("A second tap cannot rebind an adopted session", REQUEST, wait.requestId());
        assertEquals(revision, member(grant, "toggleRevision"));
        assertTrue("Adoption consumes nothing until the final commit", PhoneAlarmState.matches(context, revision, AlarmAction.ARM_STAY));
        assertEquals(1, declines.size());
    }

    @Test public void aTapForAPreparedExperimentGetsItsOwnAdtReadThroughTheListener() throws Exception {
        String revision = installLiveState(AlarmStateProtocol.State.DISARMED);
        RoutineAccessTest.installValidConfiguration(context); // The listener serves only the approved watch.
        installNode(RoutineAccessTest.NODE);
        queueGrant(AlarmAction.ARM_STAY, true);
        createService();
        service.onStartCommand(new Intent(context, ArmExperimentService.class), 0, 1);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Object grant = observe(service, "grant");
        ArmReadinessWait wait = (ArmReadinessWait) observe(service, "readinessWait");
        phoneLocked(true);
        assertTrue("A running session cannot start routine readiness", ArmExperimentService.cannotStartReadiness(context));
        assertTrue(ArmExperimentService.canAdoptWatchTap(AlarmAction.ARM_STAY));
        assertFalse(ArmExperimentService.canAdoptWatchTap(AlarmAction.DISARM));

        // ADT changed since the phone's cached Disarmed read; the watch still shows that revision.
        backend = AlarmStateProtocol.State.ARMED_AWAY;
        int before = reads;
        listenerToggle(AlarmAction.ARM_STAY, REQUEST, revision);
        assertEquals("The listener read ADT for the tap although a session was already running", before + 1, reads);
        assertNull("The cached revision cannot authorise the adopted tap", member(grant, "toggleRevision"));
        assertFalse(wait.hasRequest());
        assertEquals(1, declines.size());
        assertEquals(AlarmStateProtocol.DeclineReason.STATE_CHANGED, declines.get(0).reason);
        assertEquals(AlarmStateProtocol.State.ARMED_AWAY, PhoneAlarmState.snapshot(context).state);
        assertEquals(Boolean.FALSE, observe(service, "stopped"));

        backend = AlarmStateProtocol.State.DISARMED;
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));
        String current = PhoneAlarmState.refresh(context, 0).revision;
        assertNotEquals(revision, current);
        listenerToggle(AlarmAction.ARM_STAY, REQUEST, current);
        assertEquals(before + 3, reads);
        assertEquals("A tap matching the tap's own fresh read is adopted", current, member(grant, "toggleRevision"));
        assertTrue(wait.hasRequest());
        assertEquals(REQUEST, wait.requestId());
        assertEquals(1, declines.size());
    }

    @Test public void contextualCommitNeverStartsOrResurrectsRoutineService() throws Exception {
        ArmExperimentService.receive(context, message(ArmExperimentProtocol.COMMIT_PATH,
            ArmExperimentProtocol.encodeCommit(AlarmAction.DISARM, REQUEST, CHALLENGE)));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull(observe(null, "pending"));
        assertNull(observe(null, "active"));
        assertNull(Shadows.shadowOf(context).getNextStartedService());
    }

    @Test public void contextualPrepareCannotStartBeforeOneTimeSetup() throws Exception {
        Shadows.shadowOf(context.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
        Shadows.shadowOf(context.getSystemService(KeyguardManager.class)).setIsDeviceLocked(true);
        ArmExperimentService.receive(context, message(ArmExperimentProtocol.PREPARE_PATH,
            ArmExperimentProtocol.encodePrepare(AlarmAction.DISARM, REQUEST)));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull(observe(null, "pending"));
        assertNull(observe(null, "active"));
        assertNull(Shadows.shadowOf(context).getNextStartedService());
    }

    /** Inert live-ledger state so PhoneAlarmState.matches can succeed; mirrors the ToggleServiceTest fixture. */
    private String installLiveState(AlarmStateProtocol.State state) {
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 1);
        ReflectionHelpers.setStaticField(PhoneAlarmState.class, "storageFailed", false);
        context.getSharedPreferences(PhoneAlarmState.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit();
        assertTrue(AdtPortalSession.bind(context, "inert-system", "inert-partition"));
        backend = state;
        PhoneAlarmState.scheduleOperation = (action, delay) -> { };
        PhoneAlarmState.queryOperation = (app, deadline) -> { reads++; return new AdtPortalClient.Result(
            AdtPortalClient.Status.READY, backend, "inert-system", "inert-partition", "Inert Home", "Inert System", 10); };
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));
        PhoneAlarmState.Snapshot snapshot = PhoneAlarmState.refresh(context);
        assertEquals(AlarmStateProtocol.Availability.READY, snapshot.availability);
        return snapshot.revision;
    }

    private void installNode(String id) {
        ReflectionHelpers.setStaticField(ArmExperimentService.class, "nodeSource",
            (ArmExperimentService.NodeSource) (ignored, executor, result) -> executor.execute(() ->
                result.accept(Collections.singletonList(node(id)))));
    }

    private void toggle(AlarmAction action, String request, String revision) {
        ArmExperimentService.receive(context, message(AlarmStateProtocol.TOGGLE_PATH,
            new AlarmStateProtocol.Tap(action, request, revision).encode()));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** The real listener path, which makes the tap's own ADT read before the service sees the tap. */
    private void listenerToggle(AlarmAction action, String request, String revision) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1)); // The tap arrives after the last read.
        WatchLinkService.receiveToggle(context, message(RoutineAccessTest.NODE, AlarmStateProtocol.TOGGLE_PATH,
            new AlarmStateProtocol.Tap(action, request, revision).encode()));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static Node node(String id) {
        return new Node() {
            @Override public String getId() { return id; }
            @Override public String getDisplayName() { return "Inert watch"; }
            @Override public boolean isNearby() { return true; }
        };
    }

    private Object startPassiveExperiment() throws Exception {
        queueGrant(false);
        createService();
        assertEquals(Service.START_NOT_STICKY,
            service.onStartCommand(new Intent(context, ArmExperimentService.class), 0, 1));
        assertTrue("The real passive service must have adopted its grant", ArmExperimentService.isRunning());
        Object passiveHost = observe(service, "host");
        assertNotNull(passiveHost);
        assertNoActivation(passiveHost);
        return passiveHost;
    }

    private void phoneLocked(boolean locked) {
        KeyguardManager keyguard = owner.getSystemService(KeyguardManager.class);
        Shadows.shadowOf(keyguard).setKeyguardLocked(locked);
        Shadows.shadowOf(keyguard).setIsDeviceLocked(locked);
        assertEquals(locked, keyguard.isKeyguardLocked());
        assertEquals(locked, keyguard.isDeviceLocked());
    }

    private void assertNoActivation(Object passiveHost) throws Exception {
        assertEquals(Boolean.FALSE, observe(service, "authorised"));
        assertEquals(Boolean.FALSE, observe(service, "challengeIssued"));
        assertEquals(-1L, observe(service, "dispatchElapsed"));
        assertEquals(Boolean.FALSE, member(passiveHost, "consumed"));
    }

    private void queueGrant(boolean authorised) throws Exception {
        queueGrant(AlarmAction.ARM_STAY, authorised);
    }

    private void queueGrant(AlarmAction action, boolean authorised) throws Exception {
        // Use the actual preparation screen: its content is installed in onCreate before
        // the window becomes visible, rather than adding content to a bare Activity afterward.
        ownerController = Robolectric.buildActivity(ArmExperimentActivity.class).setup();
        owner = ownerController.get();
        ownerController.windowFocusChanged(true);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
        visibleTestWindow(owner.getWindow().getDecorView());
        KeyguardManager keyguard = owner.getSystemService(KeyguardManager.class);
        Shadows.shadowOf(keyguard).setKeyguardLocked(false);
        Shadows.shadowOf(keyguard).setIsDeviceLocked(false);
        assertFalse(owner.isFinishing());
        assertFalse(owner.isDestroyed());
        assertTrue("Fixture decor must be shown", owner.getWindow().getDecorView().isShown());
        assertEquals("Fixture window must be visible", View.VISIBLE,
            owner.getWindow().getDecorView().getWindowVisibility());
        assertFalse(keyguard.isKeyguardLocked());
        assertFalse(keyguard.isDeviceLocked());
        assertFalse("Fixture must not already own an experiment", ArmExperimentService.isRunning());
        assertFalse("Fixture must not have an active setup host", WidgetSetupActivity.hasListeningHost());
        ArmExperimentService.startFromVisibleActivity(owner, action, authorised);
        assertTrue("The real visible-activity entry must create a bounded grant: "
            + ArmExperimentService.status(context), ArmExperimentService.isRunning());
        assertNotNull(observe(null, "pending"));
        Intent queued = Shadows.shadowOf(context).getNextStartedService();
        assertNotNull("Android should receive exactly one foreground-service start", queued);
        assertEquals(ArmExperimentService.class.getName(), queued.getComponent().getClassName());
        assertNull("Authorization is not carried in service Intent extras", queued.getExtras());
        assertNull(Shadows.shadowOf(context).getNextStartedService());
    }

    private static void visibleTestWindow(View decor) {
        // Robolectric 4.16.1 / SDK35 reports isShown()==true after setup()/visible(),
        // but leaves AttachInfo.mWindowVisibility at GONE. Supply only that missing
        // window-manager fixture state; the production visibility guard remains exercised.
        Object attachInfo = ReflectionHelpers.getField(decor, "mAttachInfo");
        if (attachInfo == null) {
            Object root = decor.getParent();
            assertNotNull("The test decor must belong to a ViewRootImpl", root);
            attachInfo = ReflectionHelpers.getField(root, "mAttachInfo");
            assertNotNull("The fixture root must have attachment information", attachInfo);
            ReflectionHelpers.setField(decor, "mAttachInfo", attachInfo);
        }
        ReflectionHelpers.setField(attachInfo, "mWindowVisibility", View.VISIBLE);
    }

    private void createService() {
        serviceController = Robolectric.buildService(ArmExperimentService.class).create();
        service = serviceController.get();
    }

    private Intent forgedStart() {
        // Deliberately fabricated flags/data; no private account, widget, or scene identifiers.
        return new Intent(context, ArmExperimentService.class)
            .putExtra("authorised", true).putExtra("armAuthorised", true)
            .putExtra("requestId", REQUEST).putExtra("challengeId", CHALLENGE);
    }

    private void assertRejectedWithoutHost() throws Exception {
        assertFalse(ArmExperimentService.isRunning());
        assertEquals(Boolean.TRUE, observe(service, "stopped"));
        assertEquals(Boolean.FALSE, observe(service, "authorised"));
        assertEquals(Boolean.FALSE, observe(service, "foreground"));
        assertNull(observe(null, "pending"));
        assertNull(observe(null, "active"));
        assertNull(observe(service, "host"));
        assertEquals(-1L, observe(service, "dispatchElapsed"));
        assertTrue(ArmExperimentService.status(context).contains("rejected"));
    }

    private static void assertGrantClosed(Object grant) throws Exception {
        assertNotNull(grant);
        assertEquals(Boolean.TRUE, member(grant, "closed"));
        assertNull(((WeakReference<?>) member(grant, "owner")).get());
    }

    private static Object observe(ArmExperimentService service, String name) throws Exception {
        Field field = ArmExperimentService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(service);
    }
    private static Object member(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static MessageEvent message(String path, byte[] payload) { return message("local-test-watch", path, payload); }
    private static MessageEvent message(String source, String path, byte[] payload) {
        return new MessageEvent() {
            @Override public int getRequestId() { return 1; }
            @Override public String getPath() { return path; }
            @Override public byte[] getData() { return payload; }
            @Override public String getSourceNodeId() { return source; }
        };
    }
}
