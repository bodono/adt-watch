package dev.personal.adtprobe;

import android.app.Application;
import android.app.KeyguardManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.google.android.gms.wearable.MessageEvent;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/**
 * Real routine preferences, service receipt and grant adoption with inert framework ownership.
 * No ADT resources, RemoteViews or click PendingIntent exist in the fixture. A valid adoption
 * is canceled before queued node/readiness callbacks run. Reflection observes service state;
 * the process-loss case only erases in-memory command state, never creates authorization.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = RoutineAccessTest.HostShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class RoutineServiceTest {
    private static final String NODE = RoutineAccessTest.NODE;
    private static final String OTHER_NODE = "inert-unapproved-watch";
    private Application context;
    private ServiceController<ArmExperimentService> controller;
    private ArmExperimentService service;

    @Before public void prepare() {
        context = RuntimeEnvironment.getApplication();
        ArmExperimentService.cancelForNavigation(context);
        idle();
        while (Shadows.shadowOf(context).getNextStartedService() != null) { }
        RoutineAccessTest.installValidConfiguration(context);
        phoneLocked(true);
        assertNotNull("Positive fixture must satisfy the real routine setup checks", RoutineAccess.snapshot(context));
    }

    @After public void close() {
        ArmExperimentService.cancelForNavigation(context);
        if (controller != null) controller.destroy();
        RoutineAccess.disable(context);
        idle();
    }

    @Test public void approvedPrepareQueuesOneActionBoundServiceIntentWithoutCommandExtras() {
        Map<String, ?> savedSetup = access().getAll();
        for (AlarmAction action : AlarmAction.values()) {
            String request = request();
            Intent start = queue(action, request);
            assertEquals(new ComponentName(context, ArmExperimentService.class), start.getComponent());
            assertTrue("Command state must not travel in Android's replayable Intent",
                start.getExtras() == null || start.getExtras().isEmpty());
            Object grant = pending();
            assertEquals(action, member(grant, "action"));
            assertEquals(request, member(grant, "initialRequest"));
            assertNotNull(member(grant, "routine"));
            assertEquals(Boolean.FALSE, member(grant, "ownerVisible"));
            assertNull("Receiving PREPARE must not instantiate a service or host in this fixture", active());
            assertEquals("Only setup, not a request or deadline, is persisted", savedSetup, access().getAll());
            assertNull(Shadows.shadowOf(context).getNextStartedService());
            ArmExperimentService.cancelForNavigation(context);
            assertEquals(Boolean.TRUE, member(grant, "closed"));
        }
    }

    @Test public void validGrantAdoptionBindsItsActionAndThirtySecondReadinessLease() {
        String request = request();
        Intent start = queue(AlarmAction.DISARM, request);
        Object queued = pending();
        createService();
        long before = SystemClock.elapsedRealtime();
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(start, 0, 1));
        long after = SystemClock.elapsedRealtime();
        assertSame("The actual queued grant must be adopted", queued, member(service, "grant"));
        assertNull(pending());
        assertEquals(AlarmAction.DISARM, member(service, "action"));
        assertEquals(NODE, member(service, "boundNode"));
        long until = (Long) member(service, "sessionUntil");
        assertTrue("Routine adoption gets only a 30-second lease", until >= before + 30_000 && until <= after + 30_000);
        ArmReadinessWait wait = (ArmReadinessWait) member(service, "readinessWait");
        assertTrue(wait.hasRequest());
        assertEquals(request, wait.requestId());
        assertTrue(wait.matchesNode(NODE));
        Object host = member(service, "host");
        assertNotNull("Positive adoption reaches the real host object", host);
        assertEquals(AlarmAction.DISARM, member(host, "action"));
        assertEquals(Boolean.FALSE, member(service, "challengeIssued"));
        assertEquals(-1L, member(service, "dispatchElapsed"));
        assertEquals(Boolean.FALSE, member(host, "consumed"));

        // Do not advance the main loop into node discovery or readiness callbacks.
        ArmExperimentService.cancelForNavigation(context);
        assertEquals(Boolean.TRUE, member(host, "closed"));
        assertEquals(Boolean.FALSE, member(host, "consumed"));
        assertFalse(wait.hasRequest());
        assertFalse(ArmExperimentService.isRunning());
        idle();
        assertEquals(-1L, member(service, "dispatchElapsed"));
    }

    @Test public void duplicateOrChangedPrepareCannotReplaceOrExtendAQueuedRequest() {
        String request = request();
        queue(AlarmAction.ARM_STAY, request);
        Object first = pending();
        long until = (Long) member(first, "until");
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4_000));
        receive(NODE, ArmExperimentProtocol.PREPARE_PATH, ArmExperimentProtocol.encodePrepare(AlarmAction.ARM_STAY, request));
        receive(NODE, ArmExperimentProtocol.PREPARE_PATH, ArmExperimentProtocol.encodePrepare(AlarmAction.DISARM, request()));
        receive(OTHER_NODE, ArmExperimentProtocol.PREPARE_PATH, ArmExperimentProtocol.encodePrepare(AlarmAction.ARM_STAY, request()));
        assertSame(first, pending());
        assertEquals(until, member(first, "until"));
        assertEquals(AlarmAction.ARM_STAY, member(first, "action"));
        assertEquals(request, member(first, "initialRequest"));
        assertNull(Shadows.shadowOf(context).getNextStartedService());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_001));
        assertNull("Duplicates cannot postpone the original start deadline", pending());
        assertEquals(Boolean.TRUE, member(first, "closed"));
        assertFalse(ArmExperimentService.isRunning());
    }

    @Test public void commitWrongNodeAndUnlockedInputsCannotColdStart() {
        for (AlarmAction action : AlarmAction.values()) {
            receive(NODE, ArmExperimentProtocol.COMMIT_PATH,
                ArmExperimentProtocol.encodeCommit(action, request(), request()));
        }
        assertNotNull(RoutineAccess.snapshot(context));
        assertNoQueuedStart();
        receive(OTHER_NODE, ArmExperimentProtocol.PREPARE_PATH,
            ArmExperimentProtocol.encodePrepare(AlarmAction.DISARM, request()));
        assertNoQueuedStart();
        phoneLocked(false);
        receive(NODE, ArmExperimentProtocol.PREPARE_PATH,
            ArmExperimentProtocol.encodePrepare(AlarmAction.ARM_STAY, request()));
        assertNoQueuedStart();
        phoneLocked(true);
        assertNotNull("Positive control: locking permits a fresh approved request", queue(AlarmAction.ARM_STAY, request()));
    }

    @Test public void expiredGrantCannotBeAdoptedWhenAndroidFinallyDeliversItsIntent() {
        Intent start = queue(AlarmAction.DISARM, request());
        Object grant = pending();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_001));
        assertNull(pending());
        assertEquals(Boolean.TRUE, member(grant, "closed"));
        createService();
        service.onStartCommand(start, 0, 1);
        assertRejectedWithoutHost();
    }

    @Test public void disablingSetupBeforeDeliveryInvalidatesEvenAnUnexpiredGrant() {
        Intent start = queue(AlarmAction.ARM_STAY, request());
        Object grant = pending();
        assertTrue(RoutineAccess.disable(context));
        assertSame("The delivery guard must recheck setup even before explicit cancellation", grant, pending());
        createService();
        service.onStartCommand(start, 0, 1);
        assertRejectedWithoutHost();
        assertEquals(Boolean.TRUE, member(grant, "closed"));
    }

    @Test public void cancelingAQueuedSessionDoesNotLetTheSamePrepareStartAgain() {
        String request = request();
        queue(AlarmAction.DISARM, request);
        ArmExperimentService.cancelForNavigation(context);
        receive(NODE, ArmExperimentProtocol.PREPARE_PATH, ArmExperimentProtocol.encodePrepare(AlarmAction.DISARM, request));
        assertNoQueuedStart();
        assertNotNull("A new explicit attempt still works after cancellation", queue(AlarmAction.DISARM, request()));
    }

    @Test public void lostProcessCommandStateCannotResumeFromAnIntentOrCommit() {
        String request = request();
        Intent deliveredAfterDeath = queue(AlarmAction.DISARM, request);
        Map<String, ?> savedSetup = access().getAll();
        // Model process loss by erasing only in-memory command references and scheduled work.
        Handler main = ReflectionHelpers.getStaticField(ArmExperimentService.class, "MAIN");
        main.removeCallbacksAndMessages(null);
        ReflectionHelpers.setStaticField(ArmExperimentService.class, "pending", null);
        ReflectionHelpers.setStaticField(ArmExperimentService.class, "active", null);
        assertNotNull("One-time setup persists across process loss", RoutineAccess.snapshot(context));
        assertEquals(savedSetup, access().getAll());
        createService();
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(deliveredAfterDeath, 0, 1));
        assertRejectedWithoutHost();
        receive(NODE, ArmExperimentProtocol.COMMIT_PATH,
            ArmExperimentProtocol.encodeCommit(AlarmAction.DISARM, request, request()));
        assertNoQueuedStart();
    }

    private Intent queue(AlarmAction action, String request) {
        receive(NODE, ArmExperimentProtocol.PREPARE_PATH, ArmExperimentProtocol.encodePrepare(action, request));
        assertNotNull("Approved PREPARE must create an in-process grant", pending());
        Intent intent = Shadows.shadowOf(context).getNextStartedService();
        assertNotNull("Approved PREPARE must request one foreground-service start", intent);
        return intent;
    }

    private void receive(String source, String path, byte[] bytes) {
        ArmExperimentService.receive(context, new MessageEvent() {
            @Override public int getRequestId() { return 1; }
            @Override public String getPath() { return path; }
            @Override public byte[] getData() { return bytes; }
            @Override public String getSourceNodeId() { return source; }
        });
        idle();
    }

    private void assertNoQueuedStart() {
        assertNull(pending());
        assertNull(active());
        assertFalse(ArmExperimentService.isRunning());
        assertNull(Shadows.shadowOf(context).getNextStartedService());
    }

    private void assertRejectedWithoutHost() {
        assertEquals(Boolean.TRUE, member(service, "stopped"));
        assertEquals(Boolean.FALSE, member(service, "authorised"));
        assertEquals(Boolean.FALSE, member(service, "challengeIssued"));
        assertEquals(-1L, member(service, "dispatchElapsed"));
        assertNull(member(service, "host"));
        assertNull(member(service, "grant"));
        assertNoQueuedStart();
    }

    private void createService() {
        controller = Robolectric.buildService(ArmExperimentService.class).create();
        service = controller.get();
    }

    private void phoneLocked(boolean locked) {
        Shadows.shadowOf(context.getSystemService(KeyguardManager.class)).setIsDeviceSecure(true);
        Shadows.shadowOf(context.getSystemService(KeyguardManager.class)).setKeyguardLocked(locked);
        Shadows.shadowOf(context.getSystemService(KeyguardManager.class)).setIsDeviceLocked(locked);
    }

    private SharedPreferences access() { return context.getSharedPreferences(RoutineAccess.PREFERENCES, Context.MODE_PRIVATE); }
    private String request() { return UUID.randomUUID().toString(); }
    private void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private Object pending() { return ReflectionHelpers.getStaticField(ArmExperimentService.class, "pending"); }
    private Object active() { return ReflectionHelpers.getStaticField(ArmExperimentService.class, "active"); }
    private Object member(Object target, String name) { return ReflectionHelpers.getField(target, name); }

}
