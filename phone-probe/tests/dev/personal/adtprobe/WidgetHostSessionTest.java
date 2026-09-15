package dev.personal.adtprobe;

import android.app.PendingIntent;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Looper;
import android.os.Process;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RemoteViews;
import android.widget.TextView;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowAppWidgetHost;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Inert local broadcast only: framework RemoteViews apply/reapply and offscreen View activation.
 * Test indicator views substitute for private ADT resources; no ADT widget/PendingIntent is loaded.
 * This demonstrates our host/latch behavior, not system-server delivery or ADT locked-phone behavior.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = WidgetHostSessionTest.HostShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class WidgetHostSessionTest {
    private static final String ACTION = "dev.personal.adtprobe.INERT_HOST_SESSION_TEST";
    private static final int SCENE = 101, CANCEL = 102, CONTAINER = 103, PROGRESS = 104, RING = 105;
    private Context context;
    private WidgetHostSession session;
    private AppWidgetHostView view;
    private BroadcastReceiver receiver;
    private int received;
    private boolean valid;
    private WidgetHostSession.Contract contract;

    @Before public void prepare() throws Exception {
        context = RuntimeEnvironment.getApplication();
        valid = true;
        contract = new WidgetHostSession.Contract(context.getPackageName(), android.R.layout.simple_list_item_1,
            android.R.id.text1, android.R.id.text1, SCENE, CANCEL, CONTAINER, PROGRESS, RING);
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) { received++; }
        };
        context.registerReceiver(receiver, new IntentFilter(ACTION), Context.RECEIVER_NOT_EXPORTED);
        startSession(AlarmAction.ARM_STAY, WidgetHostSession.REVIEWED_LABEL);
    }

    private void startSession(AlarmAction action, String label) throws Exception {
        if (session != null) session.close();
        int widgetId = action == AlarmAction.ARM_STAY ? 7 : 8;
        AppWidgetProviderInfo info = new AppWidgetProviderInfo();
        info.provider = new ComponentName(context, "InertTestProvider");
        Shadows.shadowOf(AppWidgetManager.getInstance(context)).addBoundWidget(widgetId, info);
        session = new WidgetHostSession(context, action, new WidgetHostSession.Binding() {
            @Override public WidgetHostSession.Snapshot snapshot() {
                return new WidgetHostSession.Snapshot(widgetId, 1, info, contract);
            }
            @Override public boolean valid(WidgetHostSession.Snapshot value, AppWidgetHost host) { return valid; }
        });
        session.start();
        view = (AppWidgetHostView) field("view");
        assertNotNull(session.status(), view);
        render(android.R.layout.simple_list_item_1, label);
    }

    @After public void close() {
        if (session != null) session.close();
        if (receiver != null) context.unregisterReceiver(receiver);
    }

    @Test public void detachedNativeRemoteViewsClickSendsOneInertBroadcastOnly() throws Exception {
        assertFalse(view.isAttachedToWindow());
        assertTrue(view.getMeasuredWidth() > 0);
        assertTrue(session.diagnostics(), session.isReady());
        assertTrue(Shadows.shadowOf((AppWidgetHost) field("host")).isListening());
        long generation = session.renderGeneration();
        assertTrue(session.activateOnce(generation));
        assertFalse(session.activateOnce(generation));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, received);
        assertTrue(session.status().contains("unverified"));
        render(android.R.layout.simple_list_item_1, WidgetHostSession.REVIEWED_LABEL);
        assertFalse("A later idle update cannot restore a consumed authorization", session.isReady());
        assertFalse(session.activateOnce(session.renderGeneration()));
    }

    @Test public void evenIdenticalRemoteViewsReapplyInvalidatesThePreviousGeneration() {
        long old = session.renderGeneration();
        render(android.R.layout.simple_list_item_1, WidgetHostSession.REVIEWED_LABEL);
        assertTrue(session.renderGeneration() > old);
        assertFalse(session.activateOnce(old));
        assertTrue(session.diagnostics(), session.activateOnce(session.renderGeneration()));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, received);
    }

    @Test public void wrongLayoutOrLabelCannotActivateEvenWithALiveNativeClickListener() {
        render(android.R.layout.simple_list_item_2, WidgetHostSession.REVIEWED_LABEL);
        assertFalse(session.isReady());
        assertFalse(session.activateOnce(session.renderGeneration()));
        render(android.R.layout.simple_list_item_1, "Disarm");
        assertFalse(session.isReady());
        assertFalse(session.activateOnce(session.renderGeneration()));
        assertEquals(0, received);
    }

    @Test public void exactNativeAllCapsLabelMatchesWithoutBroadCaseFolding() {
        render(android.R.layout.simple_list_item_1, "Watch Arm Stay");
        assertFalse("Raw mixed case without a native transformation is not the reviewed label", session.isReady());
        TextView label = view.findViewById(android.R.id.text1);
        label.setAllCaps(true);
        assertEquals("The stored scene name remains mixed case", "Watch Arm Stay", label.getText().toString());
        assertTrue(session.diagnostics(), session.isReady());
        assertTrue(session.activateOnce(session.renderGeneration()));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, received);
    }

    @Test public void armStaySessionRejectsTheExactDisarmWidgetLabel() {
        render(android.R.layout.simple_list_item_1, "WATCH DISARM");
        assertFalse(session.isReady());
        assertFalse(session.activateOnce(session.renderGeneration()));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, received);
        render(android.R.layout.simple_list_item_1, "WATCH ARM STAY");
        assertTrue(session.diagnostics(), session.isReady());
        assertTrue(session.activateOnce(session.renderGeneration()));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, received);
    }

    @Test public void disarmSessionRequiresItsExactDisplayedLabelAndStillActivatesOnlyOnce() throws Exception {
        startSession(AlarmAction.DISARM, "WATCH ARM STAY");
        assertFalse(session.isReady());
        assertFalse(session.activateOnce(session.renderGeneration()));
        for (String wrong : new String[] {"DISARM", "WATCH DISARM ", "Watch Disarm"}) {
            render(android.R.layout.simple_list_item_1, wrong);
            assertFalse(wrong, session.isReady());
            assertFalse(session.activateOnce(session.renderGeneration()));
        }
        render(android.R.layout.simple_list_item_1, "WATCH DISARM");
        assertTrue(session.diagnostics(), session.isReady());
        render(android.R.layout.simple_list_item_1, "Watch Disarm");
        assertFalse(session.isReady());
        ((TextView) view.findViewById(android.R.id.text1)).setAllCaps(true);
        assertTrue(session.diagnostics(), session.isReady());
        assertEquals(AlarmWidgetSlot.hostId(AlarmAction.DISARM),
            Shadows.shadowOf((AppWidgetHost) field("host")).getHostId());
        long generation = session.renderGeneration();
        assertTrue(session.activateOnce(generation));
        assertFalse(session.activateOnce(generation));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Only the inert test receiver can run", 1, received);
    }

    @Test public void cancelAtZeroProgressStillFailsClosed() {
        view.findViewById(CANCEL).setVisibility(View.VISIBLE);
        view.findViewById(SCENE).setVisibility(View.GONE);
        ((ProgressBar) view.findViewById(RING)).setProgress(0);
        assertFalse(session.isReady());
        assertFalse(session.activateOnce(session.renderGeneration()));
        assertEquals(0, received);
    }

    @Test public void disabledBusyMissingAndDuplicateIndicatorsAreRejected() {
        View target = view.findViewById(android.R.id.text1);
        target.setEnabled(false);
        assertFalse(session.isReady());
        target.setEnabled(true);
        ProgressBar ring = view.findViewById(RING);
        ring.setProgress(1);
        assertFalse(session.isReady());
        ring.setProgress(0);
        ring.setIndeterminate(true);
        assertFalse(session.isReady());
        ring.setIndeterminate(false);
        View container = view.findViewById(CONTAINER);
        container.setVisibility(View.INVISIBLE);
        assertFalse(session.isReady());
        container.setVisibility(View.VISIBLE);
        assertTrue(session.diagnostics(), session.isReady());
        FrameLayout nested = new FrameLayout(context);
        for (int i = 0; i < 2; i++) {
            ImageView duplicate = new ImageView(context); duplicate.setId(SCENE); nested.addView(duplicate);
        }
        view.addView(nested);
        assertFalse("Duplicates inside a nested subtree must also be rejected", session.isReady());
        view.removeView(nested);
        view.removeView(view.findViewById(SCENE));
        assertFalse(session.isReady());
    }

    @Test public void bindingChangesAreTerminalAndCloseCannotRestart() throws Exception {
        valid = false;
        assertFalse(session.isReady());
        valid = true;
        assertFalse(session.isReady());
        session.close();
        assertFalse(Shadows.shadowOf((AppWidgetHost) field("host")).isListening());
        session.start();
        assertFalse(session.activateOnce(session.renderGeneration()));
        assertEquals(0, received);
    }

    @Test public void oneShotIsConsumedBeforeReentrancyOrAnException() {
        assertTrue(session.diagnostics(), session.isReady());
        long generation = session.renderGeneration();
        int[] entered = {0};
        view.findViewById(android.R.id.text1).setOnClickListener(ignored -> {
            entered[0]++;
            assertFalse(session.activateOnce(generation));
            throw new IllegalStateException("inert test listener failure");
        });
        assertFalse(session.activateOnce(generation));
        assertEquals("The listener must actually be reached before testing consumption", 1, entered[0]);
        assertFalse(session.isReady());
        assertFalse(session.activateOnce(generation));
        assertEquals(0, received);
    }

    @Test public void installedBindingRejectsVersionRevisionOwnershipAndProfileChanges() throws Exception {
        String pkg = "com.adtuk.adtukalarm";
        PackageInfo installed = new PackageInfo(); installed.packageName = pkg; installed.versionCode = 2307;
        installed.applicationInfo = new ApplicationInfo(); installed.applicationInfo.packageName = pkg;
        installed.applicationInfo.enabled = true; installed.applicationInfo.uid = Process.myUid();
        Shadows.shadowOf(context.getPackageManager()).installPackage(installed);
        AppWidgetProviderInfo info = new AppWidgetProviderInfo();
        info.provider = new ComponentName(pkg, "com.alarm.alarmmobile.android.WidgetProvider");
        ActivityInfo receiverInfo = new ActivityInfo(); receiverInfo.applicationInfo = installed.applicationInfo;
        ReflectionHelpers.setField(info, "providerInfo", receiverInfo);
        Shadows.shadowOf(AppWidgetManager.getInstance(context)).addBoundWidget(7, info);
        Shadows.shadowOf(AppWidgetManager.getInstance(context)).addInstalledProvidersForProfile(Process.myUserHandle(), info);
        context.getSharedPreferences("widget_host", Context.MODE_PRIVATE).edit().clear()
            .putInt("active", 7).putInt("pending", -1).putLong("revision", 5)
            .putStringSet("owned", Collections.singleton("7")).commit();
        WidgetHostSession.Binding binding = installedBinding(AlarmAction.ARM_STAY);
        WidgetHostSession.Snapshot snapshot = new WidgetHostSession.Snapshot(7, 5, info, contract);
        AppWidgetHost host = (AppWidgetHost) field("host");
        assertTrue(binding.valid(snapshot, host));
        installed.versionCode = 2308;
        Shadows.shadowOf(context.getPackageManager()).installPackage(installed);
        assertFalse(binding.valid(snapshot, host));
        installed.versionCode = 2307;
        Shadows.shadowOf(context.getPackageManager()).installPackage(installed);
        context.getSharedPreferences("widget_host", Context.MODE_PRIVATE).edit().putLong("revision", 6).commit();
        assertFalse(binding.valid(snapshot, host));
        context.getSharedPreferences("widget_host", Context.MODE_PRIVATE).edit().putLong("revision", 5)
            .putStringSet("owned", Collections.emptySet()).commit();
        assertFalse(binding.valid(snapshot, host));
        context.getSharedPreferences("widget_host", Context.MODE_PRIVATE).edit()
            .putStringSet("owned", Collections.singleton("7")).commit();
        assertTrue(binding.valid(snapshot, host));
        receiverInfo.applicationInfo.uid = Process.myUid() + 100_000;
        assertFalse("A different profile must not match the current user's widget", binding.valid(snapshot, host));
    }

    @Test public void installedBindingsRejectTheOtherSlotsPreferencesAndHost() throws Exception {
        String pkg = "com.adtuk.adtukalarm";
        PackageInfo installed = new PackageInfo(); installed.packageName = pkg; installed.versionCode = 2307;
        installed.applicationInfo = new ApplicationInfo(); installed.applicationInfo.packageName = pkg;
        installed.applicationInfo.enabled = true; installed.applicationInfo.uid = Process.myUid();
        Shadows.shadowOf(context.getPackageManager()).installPackage(installed);
        AppWidgetProviderInfo info = new AppWidgetProviderInfo();
        info.provider = new ComponentName(pkg, "com.alarm.alarmmobile.android.WidgetProvider");
        ActivityInfo receiverInfo = new ActivityInfo(); receiverInfo.applicationInfo = installed.applicationInfo;
        ReflectionHelpers.setField(info, "providerInfo", receiverInfo);
        Shadows.shadowOf(AppWidgetManager.getInstance(context)).addBoundWidget(7, info);
        Shadows.shadowOf(AppWidgetManager.getInstance(context)).addBoundWidget(8, info);
        Shadows.shadowOf(AppWidgetManager.getInstance(context)).addInstalledProvidersForProfile(Process.myUserHandle(), info);
        context.getSharedPreferences(AlarmWidgetSlot.preferences(AlarmAction.ARM_STAY), Context.MODE_PRIVATE)
            .edit().clear().putInt("active", 7).putInt("pending", -1).putLong("revision", 5)
            .putStringSet("owned", Collections.singleton("7")).commit();
        context.getSharedPreferences(AlarmWidgetSlot.preferences(AlarmAction.DISARM), Context.MODE_PRIVATE)
            .edit().clear().putInt("active", 8).putInt("pending", -1).putLong("revision", 6)
            .putStringSet("owned", Collections.singleton("8")).commit();
        WidgetHostSession.Binding arm = installedBinding(AlarmAction.ARM_STAY);
        WidgetHostSession.Binding disarm = installedBinding(AlarmAction.DISARM);
        WidgetHostSession.Snapshot armSnapshot = new WidgetHostSession.Snapshot(7, 5, info, contract);
        WidgetHostSession.Snapshot disarmSnapshot = new WidgetHostSession.Snapshot(8, 6, info, contract);
        AppWidgetHost armHost = new AppWidgetHost(context, AlarmWidgetSlot.hostId(AlarmAction.ARM_STAY));
        AppWidgetHost disarmHost = new AppWidgetHost(context, AlarmWidgetSlot.hostId(AlarmAction.DISARM));
        assertTrue(arm.valid(armSnapshot, armHost));
        assertTrue(disarm.valid(disarmSnapshot, disarmHost));
        assertFalse(arm.valid(disarmSnapshot, disarmHost));
        assertFalse(disarm.valid(armSnapshot, armHost));
        assertFalse(arm.valid(armSnapshot, disarmHost));
        assertFalse(disarm.valid(disarmSnapshot, armHost));
        context.getSharedPreferences(AlarmWidgetSlot.preferences(AlarmAction.DISARM), Context.MODE_PRIVATE)
            .edit().putLong("revision", 7).commit();
        assertFalse(disarm.valid(disarmSnapshot, disarmHost));
        assertTrue("Changing Disarm setup must not invalidate the existing Arm Stay slot",
            arm.valid(armSnapshot, armHost));
        assertEquals(0, received);
    }

    private WidgetHostSession.Binding installedBinding(AlarmAction action) throws Exception {
        Constructor<?> constructor = Class.forName(WidgetHostSession.class.getName() + "$InstalledBinding")
            .getDeclaredConstructor(Context.class, AlarmAction.class);
        constructor.setAccessible(true);
        return (WidgetHostSession.Binding) constructor.newInstance(context, action);
    }

    private void render(int layout, String label) {
        PendingIntent inert = PendingIntent.getBroadcast(context, 902,
            new Intent(ACTION).setPackage(context.getPackageName()),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        RemoteViews remote = new RemoteViews(context.getPackageName(), layout);
        remote.setTextViewText(android.R.id.text1, label);
        remote.setOnClickPendingIntent(android.R.id.text1, inert);
        view.updateAppWidget(remote);
        // Synthetic indicator views let the same production idle predicate inspect all states.
        if (view.findViewById(SCENE) == null) {
            ImageView scene = new ImageView(context); scene.setId(SCENE); view.addView(scene);
            View cancel = new View(context); cancel.setId(CANCEL); cancel.setVisibility(View.GONE); view.addView(cancel);
            FrameLayout container = new FrameLayout(context); container.setId(CONTAINER); view.addView(container);
            ProgressBar progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
            progress.setId(PROGRESS); progress.setMax(1); progress.setProgress(1); container.addView(progress);
            ProgressBar ring = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
            ring.setId(RING); ring.setProgress(0); container.addView(ring);
        }
    }

    private Object field(String name) throws Exception {
        Field field = WidgetHostSession.class.getDeclaredField(name);
        field.setAccessible(true); return field.get(session);
    }

    /** Robolectric's stock host shadow replaces onCreateView; preserve that public subclass hook. */
    @Implements(AppWidgetHost.class)
    public static final class HostShadow extends ShadowAppWidgetHost {
        @RealObject private AppWidgetHost realHost;
        @Implementation protected AppWidgetHostView createView(Context context, int id, AppWidgetProviderInfo info) {
            return ReflectionHelpers.callInstanceMethod(realHost, "onCreateView",
                ReflectionHelpers.ClassParameter.from(Context.class, context),
                ReflectionHelpers.ClassParameter.from(int.class, id),
                ReflectionHelpers.ClassParameter.from(AppWidgetProviderInfo.class, info));
        }
        @Implementation protected int[] getAppWidgetIds() {
            return new int[] {getHostId() == AlarmWidgetSlot.hostId(AlarmAction.ARM_STAY) ? 7 : 8};
        }
        @Override @Implementation protected int allocateAppWidgetId() { throw new AssertionError("Must not allocate"); }
        @Implementation protected void deleteAppWidgetId(int id) { throw new AssertionError("Must not delete"); }
        @Implementation protected void deleteHost() { throw new AssertionError("Must not delete host"); }
    }
}
