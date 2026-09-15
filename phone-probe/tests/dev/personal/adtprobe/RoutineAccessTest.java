package dev.personal.adtprobe;

import android.app.Activity;
import android.app.KeyguardManager;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.companion.CompanionDeviceManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Process;
import android.view.View;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.AssociationInfoBuilder;
import org.robolectric.shadows.ShadowAppWidgetHost;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Real preferences plus inert CDM/widget records. Never loads an ADT view or sends a message. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = RoutineAccessTest.HostShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class RoutineAccessTest {
    public static final String NODE = "inert-approved-watch";
    public static final int ASSOCIATION = 17;
    private Context context;

    @Before public void prepare() {
        context = RuntimeEnvironment.getApplication();
        installValidConfiguration(context);
    }

    /** Reusable positive service fixture; all names, IDs and records are synthetic. */
    public static void installValidConfiguration(Context context) {
        RoutineAccess.disable(context);
        Shadows.shadowOf(context.getSystemService(KeyguardManager.class)).setIsDeviceSecure(true);
        String pkg = "com.adtuk.adtukalarm";
        PackageInfo installed = new PackageInfo(); installed.packageName = pkg; installed.versionCode = 2307;
        installed.applicationInfo = new ApplicationInfo(); installed.applicationInfo.packageName = pkg;
        installed.applicationInfo.enabled = true; installed.applicationInfo.uid = Process.myUid();
        Shadows.shadowOf(context.getPackageManager()).installPackage(installed);
        AppWidgetProviderInfo info = new AppWidgetProviderInfo();
        info.provider = new ComponentName(pkg, "com.alarm.alarmmobile.android.WidgetProvider");
        ActivityInfo providerInfo = new ActivityInfo(); providerInfo.applicationInfo = installed.applicationInfo;
        providerInfo.packageName = pkg; providerInfo.name = info.provider.getClassName();
        ReflectionHelpers.setField(info, "providerInfo", providerInfo);
        Shadows.shadowOf(AppWidgetManager.getInstance(context)).addInstalledProvidersForProfile(Process.myUserHandle(), info);
        for (AlarmAction action : AlarmAction.values()) {
            int id = action == AlarmAction.ARM_STAY ? 7 : 9;
            long revision = action == AlarmAction.ARM_STAY ? 5 : 6;
            Shadows.shadowOf(AppWidgetManager.getInstance(context)).addBoundWidget(id, info);
            context.getSharedPreferences(AlarmWidgetSlot.preferences(action), Context.MODE_PRIVATE).edit().clear()
                .putInt("active", id).putInt("pending", -1).putInt("stage", WidgetSetupState.NONE)
                .putLong("revision", revision).putStringSet("owned", Collections.singleton(Integer.toString(id))).commit();
        }
        addAssociation(context, ASSOCIATION, false);
        writeEnabledAccess(context, 1);
    }

    public static void writeEnabledAccess(Context context, long revision) {
        context.getSharedPreferences(RoutineAccess.PREFERENCES, Context.MODE_PRIVATE).edit().clear()
            .putBoolean("enabled", true).putString("node", NODE).putInt("association", ASSOCIATION)
            .putInt("arm_widget", 7).putInt("disarm_widget", 9).putLong("arm_revision", 5)
            .putLong("disarm_revision", 6).putLong("revision", revision).commit();
    }

    private static void addAssociation(Context context, int id, boolean selfManaged) {
        Shadows.shadowOf(context.getSystemService(CompanionDeviceManager.class)).addAssociation(
            AssociationInfoBuilder.newBuilder().setId(id).setUserId(ReflectionHelpers.<Integer>callInstanceMethod(Process.myUserHandle(), "getIdentifier"))
                .setPackageName(context.getPackageName()).setDisplayName("Inert associated watch")
                .setDeviceMacAddress("02:00:00:00:00:17").setSelfManaged(selfManaged).build());
    }

    @Test public void positiveSnapshotHasOnlyReviewedConfigurationAndIsImmutableAcrossRevocation() {
        RoutineAccess.Snapshot first = RoutineAccess.snapshot(context);
        assertNotNull("The real preference/CDM/widget fixture must pass first", first);
        assertEquals(NODE, first.nodeId);
        assertEquals(5, first.armRevision); assertEquals(6, first.disarmRevision); assertEquals(1, first.revision);
        assertTrue(RoutineAccess.stillValid(context, first));
        assertEquals(new HashSet<>(Arrays.asList("enabled", "node", "association", "arm_widget", "disarm_widget",
            "arm_revision", "disarm_revision", "revision")), access().getAll().keySet());
        assertTrue(RoutineAccess.disable(context));
        assertEquals(1, first.revision);
        assertNull(RoutineAccess.snapshot(context));
        assertFalse(RoutineAccess.stillValid(context, first));
        assertFalse(access().contains("node"));
    }

    @Test public void reenableCannotRestoreAnOldGrantEvenForTheSameWatchAndWidgets() {
        RoutineAccess.Snapshot old = RoutineAccess.snapshot(context);
        RoutineAccess.disable(context);
        writeEnabledAccess(context, 3);
        assertNotNull(RoutineAccess.snapshot(context));
        assertFalse(RoutineAccess.stillValid(context, old));
        assertFalse(RoutineAccess.stillValid(context, null));
    }

    @Test public void removedReviewedAssociationCannotBeReplacedByAnotherRecord() {
        RoutineAccess.Snapshot old = RoutineAccess.snapshot(context);
        context.getSystemService(CompanionDeviceManager.class).disassociate(ASSOCIATION);
        addAssociation(context, ASSOCIATION + 1, false);
        assertNull(RoutineAccess.snapshot(context));
        assertFalse(RoutineAccess.stillValid(context, old));
    }

    @Test public void selfManagedRecordDoesNotSatisfyNativeAssociationReview() {
        context.getSystemService(CompanionDeviceManager.class).disassociate(ASSOCIATION);
        addAssociation(context, ASSOCIATION, true);
        assertNull(RoutineAccess.snapshot(context));
        assertTrue(RoutineAccess.associations(context).isEmpty());
    }

    @Test public void eitherPendingChangedOrUnownedWidgetInvalidatesAccess() {
        RoutineAccess.Snapshot old = RoutineAccess.snapshot(context);
        slot(AlarmAction.DISARM).edit().putInt("pending", 12).commit();
        assertFalse(RoutineAccess.stillValid(context, old));
        slot(AlarmAction.DISARM).edit().putInt("pending", -1).putLong("revision", 7).commit();
        assertNull(RoutineAccess.snapshot(context));
        slot(AlarmAction.DISARM).edit().putLong("revision", 6).commit();
        assertNotNull(RoutineAccess.snapshot(context));
        slot(AlarmAction.ARM_STAY).edit().putStringSet("owned", Collections.emptySet()).commit();
        assertNull(RoutineAccess.snapshot(context));
    }

    @Test public void insecurePhoneDisabledFlagMalformedNodeAndProviderChangeFailClosed() throws Exception {
        Shadows.shadowOf(context.getSystemService(KeyguardManager.class)).setIsDeviceSecure(false);
        assertNull(RoutineAccess.snapshot(context));
        Shadows.shadowOf(context.getSystemService(KeyguardManager.class)).setIsDeviceSecure(true);
        access().edit().putBoolean("enabled", false).commit(); assertNull(RoutineAccess.snapshot(context));
        access().edit().putBoolean("enabled", true).putString("node", "bad\nnode").commit(); assertNull(RoutineAccess.snapshot(context));
        access().edit().putString("node", NODE).commit(); assertNotNull(RoutineAccess.snapshot(context));
        PackageInfo updated = context.getPackageManager().getPackageInfo("com.adtuk.adtukalarm", 0);
        updated.versionCode = 2308; Shadows.shadowOf(context.getPackageManager()).installPackage(updated);
        assertNull(RoutineAccess.snapshot(context));
    }

    @Test public void reviewedEnableRequiresVisibleUnlockedPhoneAndUnchangedReview() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        Activity activity = controller.get();
        try {
            View decor = activity.getWindow().getDecorView();
            Object attach = ReflectionHelpers.getField(decor, "mAttachInfo");
            assertNotNull("Attached window fixture", attach);
            ReflectionHelpers.setField(attach, "mWindowVisibility", View.VISIBLE);
            Shadows.shadowOf(activity.getSystemService(KeyguardManager.class)).setKeyguardLocked(false);
            Shadows.shadowOf(activity.getSystemService(KeyguardManager.class)).setIsDeviceLocked(false);
            RoutineAccess.Review review = RoutineAccess.prepareReview(activity);
            assertNotNull(review);
            assertTrue(RoutineAccess.visibleUnlocked(activity));
            assertTrue(RoutineAccess.enableReviewed(activity, review, NODE, ASSOCIATION));
            assertEquals(2, RoutineAccess.snapshot(activity).revision);
            assertFalse("Consent for an earlier access revision is stale", RoutineAccess.enableReviewed(activity, review, NODE, ASSOCIATION));
            review = RoutineAccess.prepareReview(activity);
            Shadows.shadowOf(activity.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
            assertFalse(RoutineAccess.enableReviewed(activity, review, NODE, ASSOCIATION));
            Shadows.shadowOf(activity.getSystemService(KeyguardManager.class)).setKeyguardLocked(false);
            slot(AlarmAction.ARM_STAY).edit().putLong("revision", 6).commit();
            assertFalse(RoutineAccess.enableReviewed(activity, review, NODE, ASSOCIATION));
        } finally { controller.pause().stop().destroy(); }
    }

    @Test public void displayNamesNeverExposeRawAddressesAndAreNotSaved() {
        assertNull(RoutineAccess.safeName("02:00:00:00:00:17"));
        assertEquals("Watch name", RoutineAccess.safeName("Watch\nname"));
        assertEquals(1, RoutineAccess.associations(context).size());
        assertFalse(access().getAll().values().contains("Inert associated watch"));
    }

    private SharedPreferences access() { return context.getSharedPreferences(RoutineAccess.PREFERENCES, Context.MODE_PRIVATE); }
    private SharedPreferences slot(AlarmAction action) {
        return context.getSharedPreferences(AlarmWidgetSlot.preferences(action), Context.MODE_PRIVATE);
    }

    @Implements(AppWidgetHost.class)
    public static class HostShadow extends ShadowAppWidgetHost {
        @Implementation protected int[] getAppWidgetIds() {
            if (getHostId() == AlarmWidgetSlot.hostId(AlarmAction.ARM_STAY)) return new int[] {7};
            if (getHostId() == AlarmWidgetSlot.hostId(AlarmAction.DISARM)) return new int[] {9};
            return new int[0];
        }
        @Override @Implementation protected int allocateAppWidgetId() { throw new AssertionError("Routine access must not allocate"); }
        @Implementation protected void deleteAppWidgetId(int id) { throw new AssertionError("Routine access must not delete"); }
    }
}
