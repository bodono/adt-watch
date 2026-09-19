package dev.personal.adtprobe;

import android.app.Application;
import android.app.KeyguardManager;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RemoteViews;
import android.widget.TextView;
import com.google.android.gms.wearable.MessageEvent;
import java.util.UUID;
import java.time.Duration;
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
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.RealObject;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

/** Real state/grant gates with synthetic local views. No ADT PendingIntent or resources exist. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = ToggleServiceTest.HostShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class ToggleServiceTest {
    private static final String NODE = RoutineAccessTest.NODE;
    private static final int SCENE = 101, CANCEL = 102, CONTAINER = 103, PROGRESS = 104, RING = 105;
    private Application context;
    private ServiceController<ArmExperimentService> controller;
    private ArmExperimentService service;
    private WidgetHostSession inertHost;
    private Runnable validationSideEffect;
    private PhoneAlarmState.Query originalQuery;
    private PhoneAlarmState.Schedule originalSchedule;
    private AdtPortalClient.Result queryResult;
    private int queryCalls;
    private String request, revision, challenge;
    private int clicks;
    private int validations, rejectValidation = -1;
    private boolean falseAfterClick, throwAfterClick;

    @Before public void prepare() {
        context = RuntimeEnvironment.getApplication();
        ArmExperimentService.cancelForNavigation(context); idle();
        while (Shadows.shadowOf(context).getNextStartedService() != null) { }
        RoutineAccessTest.installValidConfiguration(context);
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 1);
        ReflectionHelpers.setStaticField(PhoneAlarmState.class, "storageFailed", false);
        context.getSharedPreferences(PhoneAlarmState.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit();
        assertTrue(AdtPortalSession.bind(context, "inert-system", "inert-partition"));
        originalQuery = ReflectionHelpers.getStaticField(PhoneAlarmState.class, "queryOperation");
        originalSchedule = ReflectionHelpers.getStaticField(PhoneAlarmState.class, "scheduleOperation");
        ReflectionHelpers.setStaticField(PhoneAlarmState.class, "scheduleOperation", (PhoneAlarmState.Schedule) (action, delay) -> { });
        ReflectionHelpers.setStaticField(PhoneAlarmState.class, "queryOperation", (PhoneAlarmState.Query) (app, deadline) -> {
            queryCalls++;
            return queryResult;
        });
        fresh(AlarmStateProtocol.State.DISARMED);
        revision = PhoneAlarmState.snapshot(context).revision;
        assertEquals(AlarmStateProtocol.Availability.READY, PhoneAlarmState.snapshot(context).availability);
        Shadows.shadowOf(context.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
        Shadows.shadowOf(context.getSystemService(KeyguardManager.class)).setIsDeviceLocked(true);
        request = UUID.randomUUID().toString(); challenge = UUID.randomUUID().toString();
    }

    @After public void close() {
        ArmExperimentService.cancelForNavigation(context);
        if (controller != null) controller.destroy();
        if (inertHost != null) inertHost.close();
        RoutineAccess.disable(context); idle();
        ReflectionHelpers.setStaticField(PhoneAlarmState.class, "queryOperation", originalQuery);
        ReflectionHelpers.setStaticField(PhoneAlarmState.class, "scheduleOperation", originalSchedule);
    }

    @Test public void tapRequiresApprovedSourceCurrentRevisionAndMatchingAction() {
        receive("different-watch", AlarmStateProtocol.TOGGLE_PATH, tap(AlarmAction.ARM_STAY, revision));
        noStart();
        receive(NODE, AlarmStateProtocol.TOGGLE_PATH, tap(AlarmAction.DISARM, revision));
        noStart();
        receive(NODE, AlarmStateProtocol.TOGGLE_PATH, tap(AlarmAction.ARM_STAY, UUID.randomUUID().toString()));
        noStart();
        Intent start = queue();
        Object grant = pending();
        assertEquals(revision, member(grant, "toggleRevision"));
        assertEquals(AlarmAction.ARM_STAY, member(grant, "action"));
        assertEquals(request, member(grant, "initialRequest"));
        assertTrue(start.getExtras() == null || start.getExtras().isEmpty());
    }

    @Test public void stateChangedWhileStartWasQueuedRejectsBeforeHostCreation() {
        Intent start = queue();
        fresh(AlarmStateProtocol.State.ARMED_STAY);
        create(start);
        assertEquals(Boolean.TRUE, member(service, "stopped"));
        assertNull(member(service, "host"));
        assertEquals(-1L, member(service, "dispatchElapsed"));
        assertEquals(0, clicks);
    }

    @Test public void tapInsideTheReuseWindowSkipsTheExtraRead() {
        int before = queryCalls;
        Intent start = queue();
        assertNotNull(start);
        assertEquals("A read from moments ago is reused for the preflight", before, queryCalls);
    }

    @Test public void staleTapRefreshesFromAdtAndAlreadySatisfiedActionsNeverStartAHost() {
        int before = queryCalls;
        queryResult = report(AlarmStateProtocol.State.ARMED_STAY);
        pastReuse();
        receive(NODE, AlarmStateProtocol.TOGGLE_PATH, tap(AlarmAction.ARM_STAY, revision));
        noStart();
        assertEquals(before + 1, queryCalls);
        assertEquals(AlarmStateProtocol.State.ARMED_STAY, PhoneAlarmState.snapshot(context).state);
        revision = PhoneAlarmState.snapshot(context).revision;
        queryResult = report(AlarmStateProtocol.State.DISARMED);
        pastReuse();
        receive(NODE, AlarmStateProtocol.TOGGLE_PATH, tap(AlarmAction.DISARM, revision));
        noStart();
        assertEquals(before + 2, queryCalls);
        assertEquals(AlarmStateProtocol.State.DISARMED, PhoneAlarmState.snapshot(context).state);
        assertEquals(0, clicks);
    }

    @Test public void invalidOrUnapprovedTapCannotReadTheAccount() {
        int before = queryCalls;
        receive("different-watch", AlarmStateProtocol.TOGGLE_PATH, tap(AlarmAction.ARM_STAY, revision));
        receive(NODE, AlarmStateProtocol.TOGGLE_PATH, new byte[]{1, 2, 3});
        assertEquals(before, queryCalls);
        noStart();
    }

    @Test public void failedFreshReadCannotReusePreviouslyReadyStateToArm() {
        queryResult = new AdtPortalClient.Result(AdtPortalClient.Status.UNAVAILABLE,
            AlarmStateProtocol.State.UNKNOWN, "", "", "", "", 10);
        pastReuse();
        receive(NODE, AlarmStateProtocol.TOGGLE_PATH, tap(AlarmAction.ARM_STAY, revision));
        noStart();
        assertTrue("The failed preflight read is what blocks the command", PhoneAlarmState.snapshot(context).readFailed);
        assertEquals("The earlier fresh observation still colours the watch", AlarmStateProtocol.Availability.READY,
            PhoneAlarmState.snapshot(context).availability);
        assertEquals(0, clicks);
    }

    @Test public void transientReadFailureAfterThePreflightDoesNotCancelTheSession() {
        create(queue());
        queryResult = new AdtPortalClient.Result(AdtPortalClient.Status.UNAVAILABLE,
            AlarmStateProtocol.State.UNKNOWN, "", "", "", "", 10);
        ShadowSystemClock.advanceBy(Duration.ofMillis(1));
        PhoneAlarmState.refresh(context, 0); // A notification hint whose read timed out.
        assertTrue(PhoneAlarmState.matches(context, revision, AlarmAction.ARM_STAY));
        assertEquals(Boolean.TRUE, ReflectionHelpers.callInstanceMethod(service, "live"));
        assertEquals(Boolean.FALSE, member(service, "stopped"));
    }

    @Test public void revokingWatchSetupDuringTheReadPreventsNativeReadiness() {
        ReflectionHelpers.setStaticField(PhoneAlarmState.class, "queryOperation", (PhoneAlarmState.Query) (app, deadline) -> {
            RoutineAccess.disable(context);
            return report(AlarmStateProtocol.State.DISARMED);
        });
        pastReuse();
        receive(NODE, AlarmStateProtocol.TOGGLE_PATH, tap(AlarmAction.ARM_STAY, revision));
        noStart();
        assertEquals(0, clicks);
    }

    @Test public void diagnosticCommitCannotColdStartOrReplaceAToggleGrant() {
        receive(NODE, ArmExperimentProtocol.COMMIT_PATH,
            ArmExperimentProtocol.encodeCommit(AlarmAction.ARM_STAY, request, challenge));
        noStart();
        Intent start = queue(); Object first = pending();
        receive(NODE, ArmExperimentProtocol.PREPARE_PATH,
            ArmExperimentProtocol.encodePrepare(AlarmAction.DISARM, UUID.randomUUID().toString()));
        assertSame(first, pending()); assertEquals(revision, member(first, "toggleRevision"));
        assertNull(Shadows.shadowOf(context).getNextStartedService());
        assertNotNull(start);
    }

    @Test public void v2PrepareInsideActiveToggleDoesNotRemoveItsStateGate() {
        create(queue());
        Object grant = member(service, "grant");
        assertEquals(revision, member(grant, "toggleRevision"));
        assertTrue(PhoneAlarmState.beginCommand(context, revision, AlarmAction.ARM_STAY));
        // Exercise the same handler used for an incoming v2 counterpart, before queued node callbacks.
        invokeHandle(ArmExperimentProtocol.parse(ArmExperimentProtocol.encodePrepare(AlarmAction.ARM_STAY, request)));
        assertEquals(Boolean.TRUE, member(service, "stopped"));
        assertEquals(-1L, member(service, "dispatchElapsed"));
        assertEquals(0, clicks);
    }

    @Test public void finalCommitPersistsBusyAndConsumesPermitBeforeExactlyOneLocalClick() {
        create(queue()); installInertReadyHost(); setChallenge();
        invokeCommit();
        assertEquals(1, clicks);
        assertEquals(AlarmStateProtocol.Availability.BUSY, PhoneAlarmState.snapshot(context).availability);
        assertEquals(Boolean.TRUE, member(inertHost, "consumed"));
        invokeCommit();
        assertEquals("A duplicate final callback must not invoke another listener", 1, clicks);
        assertFalse(PhoneAlarmState.matches(context, revision, AlarmAction.ARM_STAY));
    }

    @Test public void stateChangeDuringFinalWidgetValidationBlocksNativeActivation() {
        create(queue()); installInertReadyHost(); setChallenge();
        validationSideEffect = () -> fresh(AlarmStateProtocol.State.ARMED_STAY);
        invokeCommit();
        assertEquals(0, clicks);
        assertEquals("The one-shot is reserved before the state check, without entering the click",
            Boolean.TRUE, member(inertHost, "consumed"));
        assertEquals(AlarmStateProtocol.State.ARMED_STAY, PhoneAlarmState.snapshot(context).state);
        assertFalse(PhoneAlarmState.matches(context, revision, AlarmAction.ARM_STAY));
    }

    @Test public void finalBindingRejectionDoesNotPersistAPhantomPendingCommand() {
        create(queue()); installInertReadyHost(); setChallenge();
        // The service's first readiness check passes; the host's final check fails.
        rejectValidation = validations + 2;
        invokeCommit();
        assertEquals(rejectValidation, validations);
        assertEquals(0, clicks);
        assertEquals(Boolean.FALSE, member(inertHost, "consumed"));
        assertFalse(PhoneAlarmState.snapshot(context).pending);
        assertTrue(PhoneAlarmState.matches(context, revision, AlarmAction.ARM_STAY));
        assertTrue(ArmExperimentService.status(context).contains("experiment rejected"));
    }

    @Test public void falseReturnAfterNativeClickRemainsRequestedAndCannotRepeat() {
        create(queue()); installInertReadyHost(); setChallenge();
        falseAfterClick = true;
        invokeCommit();
        assertAttemptedWithoutRetry();
    }

    @Test public void exceptionAfterNativeClickRemainsRequestedAndCannotRepeat() {
        create(queue()); installInertReadyHost(); setChallenge();
        throwAfterClick = true;
        invokeCommit();
        assertAttemptedWithoutRetry();
    }

    private void assertAttemptedWithoutRetry() {
        assertEquals(1, clicks);
        assertEquals(AlarmStateProtocol.Availability.BUSY, PhoneAlarmState.snapshot(context).availability);
        assertEquals(Boolean.TRUE, member(inertHost, "consumed"));
        assertTrue("An entered native click cannot become a definite rejection",
            ArmExperimentService.status(context).contains("widget click attempted once"));
        invokeCommit();
        assertEquals(1, clicks);
    }

    private void installInertReadyHost() {
        WidgetHostSession original = (WidgetHostSession) member(service, "host");
        assertNotNull(original); original.close();
        AppWidgetProviderInfo info = AppWidgetManager.getInstance(context).getAppWidgetInfo(7);
        WidgetHostSession.Contract contract = new WidgetHostSession.Contract(context.getPackageName(), android.R.layout.simple_list_item_1,
            android.R.id.text1, android.R.id.text1, SCENE, CANCEL, CONTAINER, PROGRESS, RING);
        inertHost = new WidgetHostSession(context, AlarmAction.ARM_STAY, new WidgetHostSession.Binding() {
            @Override public WidgetHostSession.Snapshot snapshot() { return new WidgetHostSession.Snapshot(7, 1, info, contract); }
            @Override public boolean valid(WidgetHostSession.Snapshot snapshot, AppWidgetHost host) {
                validations++;
                Runnable sideEffect = validationSideEffect; validationSideEffect = null;
                if (sideEffect != null) sideEffect.run();
                return validations != rejectValidation;
            }
        });
        inertHost.start();
        AppWidgetHostView view = (AppWidgetHostView) member(inertHost, "view");
        RemoteViews remote = new RemoteViews(context.getPackageName(), android.R.layout.simple_list_item_1);
        remote.setTextViewText(android.R.id.text1, AlarmAction.ARM_STAY.widgetLabel());
        view.updateAppWidget(remote);
        View originalTarget = view.findViewById(android.R.id.text1);
        ViewGroup parent = (ViewGroup) originalTarget.getParent();
        parent.removeView(originalTarget);
        TextView target = new TextView(context) {
            @Override public boolean performClick() {
                boolean result = super.performClick();
                if (throwAfterClick) throw new IllegalStateException("Inert post-click failure");
                return !falseAfterClick && result;
            }
        };
        target.setId(android.R.id.text1);
        target.setText(AlarmAction.ARM_STAY.widgetLabel());
        parent.addView(target);
        target.setOnClickListener(ignored -> {
            assertEquals("The service permit must already be consumed", Boolean.FALSE, member(service, "authorised"));
            assertEquals("BUSY must be durably saved before entering a view listener", AlarmStateProtocol.Availability.BUSY,
                PhoneAlarmState.snapshot(context).availability);
            assertEquals("PENDING", context.getSharedPreferences(PhoneAlarmState.PREFERENCES, Context.MODE_PRIVATE).getString("outcome", ""));
            assertEquals(request, context.getSharedPreferences(PhoneAlarmState.PREFERENCES, Context.MODE_PRIVATE).getString("request", ""));
            clicks++;
        });
        ImageView scene = new ImageView(context); scene.setId(SCENE); view.addView(scene);
        View cancel = new View(context); cancel.setId(CANCEL); cancel.setVisibility(View.GONE); view.addView(cancel);
        FrameLayout container = new FrameLayout(context); container.setId(CONTAINER); view.addView(container);
        ProgressBar progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progress.setId(PROGRESS); progress.setMax(1); progress.setProgress(1); container.addView(progress);
        ProgressBar ring = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        ring.setId(RING); ring.setProgress(0); container.addView(ring);
        assertTrue(inertHost.diagnostics(), inertHost.isReady());
        ReflectionHelpers.setField(service, "host", inertHost);
    }

    private void setChallenge() {
        // Simulate the verified-node callback's already-issued challenge. Network discovery is not exercised here.
        ReflectionHelpers.setField(service, "requestId", request);
        ReflectionHelpers.setField(service, "challengeId", challenge);
        ReflectionHelpers.setField(service, "challengeIssued", true);
        ReflectionHelpers.setField(service, "challengeGeneration", inertHost.renderGeneration());
        ReflectionHelpers.setField(service, "challengeUntil", SystemClock.elapsedRealtime() + 20_000);
    }

    private void invokeCommit() {
        ReflectionHelpers.callInstanceMethod(service, "commit", ReflectionHelpers.ClassParameter.from(String.class, NODE),
            ReflectionHelpers.ClassParameter.from(ArmExperimentProtocol.Message.class,
                ArmExperimentProtocol.parse(ArmExperimentProtocol.encodeCommit(AlarmAction.ARM_STAY, request, challenge))));
    }

    private void invokeHandle(ArmExperimentProtocol.Message message) {
        ReflectionHelpers.callInstanceMethod(service, "handle", ReflectionHelpers.ClassParameter.from(String.class, NODE),
            ReflectionHelpers.ClassParameter.from(ArmExperimentProtocol.Message.class, message));
    }

    private void create(Intent start) {
        controller = Robolectric.buildService(ArmExperimentService.class).create(); service = controller.get();
        service.onStartCommand(start, 0, 1);
        // Remain before pending node/readiness callbacks; commits use an explicitly inert simulated challenge.
    }

    private Intent queue() {
        receive(NODE, AlarmStateProtocol.TOGGLE_PATH, tap(AlarmAction.ARM_STAY, revision));
        assertNotNull(pending());
        Intent result = Shadows.shadowOf(context).getNextStartedService(); assertNotNull(result); return result;
    }
    private byte[] tap(AlarmAction action, String value) { return new AlarmStateProtocol.Tap(action, request, value).encode(); }
    private void receive(String source, String path, byte[] bytes) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(1));
        MessageEvent event = new MessageEvent() {
            @Override public int getRequestId() { return 1; }
            @Override public String getPath() { return path; }
            @Override public byte[] getData() { return bytes; }
            @Override public String getSourceNodeId() { return source; }
        };
        if (AlarmStateProtocol.TOGGLE_PATH.equals(path)) WatchLinkService.receiveToggle(context, event);
        else ArmExperimentService.receive(context, event);
        idle();
    }
    private void noStart() { assertNull(pending()); assertNull(Shadows.shadowOf(context).getNextStartedService()); }
    private Object pending() { return ReflectionHelpers.getStaticField(ArmExperimentService.class, "pending"); }
    private Object member(Object object, String field) { return ReflectionHelpers.getField(object, field); }
    private void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }

    private void fresh(AlarmStateProtocol.State state) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(1));
        queryResult = report(state);
        PhoneAlarmState.refresh(context, 0);
    }
    private void pastReuse() { ShadowSystemClock.advanceBy(Duration.ofMillis(PhoneAlarmState.REUSE_MS)); }
    private AdtPortalClient.Result report(AlarmStateProtocol.State state) {
        return new AdtPortalClient.Result(AdtPortalClient.Status.READY, state,
            "inert-system", "inert-partition", "Inert Home", "Inert System", 10);
    }

    @Implements(AppWidgetHost.class)
    public static final class HostShadow extends RoutineAccessTest.HostShadow {
        @RealObject private AppWidgetHost realHost;
        @Implementation protected AppWidgetHostView createView(Context context, int id, AppWidgetProviderInfo info) {
            return ReflectionHelpers.callInstanceMethod(realHost, "onCreateView", ReflectionHelpers.ClassParameter.from(Context.class, context),
                ReflectionHelpers.ClassParameter.from(int.class, id), ReflectionHelpers.ClassParameter.from(AppWidgetProviderInfo.class, info));
        }
    }
}
