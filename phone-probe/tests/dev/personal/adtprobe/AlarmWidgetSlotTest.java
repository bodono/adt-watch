package dev.personal.adtprobe;

import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.appwidget.AppWidgetHost;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.After;
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
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowAppWidgetHost;
import static org.junit.Assert.*;

/** Real setup bookkeeping against an inert per-host allocation map; no ADT provider is installed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = AlarmWidgetSlotTest.NamespaceHostShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class AlarmWidgetSlotTest {
    private static final int FOREIGN_HOST = 0x11223344;
    private Context context;
    private ActivityController<WidgetSetupActivity> controller;

    @Before public void prepare() {
        context = RuntimeEnvironment.getApplication();
        NamespaceHostShadow.allocations.clear();
        NamespaceHostShadow.deleted.clear();
        seed(AlarmAction.ARM_STAY, 7);
        seed(AlarmAction.DISARM, 8);
        NamespaceHostShadow.allocations.put(FOREIGN_HOST, new LinkedHashSet<>(Arrays.asList(70, 80)));
        Shadows.shadowOf(context.getSystemService(KeyguardManager.class)).setKeyguardLocked(false);
        Shadows.shadowOf(context.getSystemService(KeyguardManager.class)).setIsDeviceLocked(false);
    }

    @After public void close() {
        if (controller != null) controller.pause().stop().destroy();
        NamespaceHostShadow.allocations.clear();
        NamespaceHostShadow.deleted.clear();
    }

    @Test public void defaultArmSlotKeepsItsEstablishedNamespaceAndExplicitDisarmIsSeparate() {
        assertEquals(0x41445401, AlarmWidgetSlot.hostId(AlarmAction.ARM_STAY));
        assertEquals("widget_host", AlarmWidgetSlot.preferences(AlarmAction.ARM_STAY));
        assertNotEquals(AlarmWidgetSlot.hostId(AlarmAction.ARM_STAY), AlarmWidgetSlot.hostId(AlarmAction.DISARM));
        assertNotEquals(AlarmWidgetSlot.preferences(AlarmAction.ARM_STAY), AlarmWidgetSlot.preferences(AlarmAction.DISARM));
        assertEquals(AlarmAction.ARM_STAY, AlarmWidgetSlot.fromIntent(null));
        assertEquals(AlarmAction.ARM_STAY, AlarmWidgetSlot.fromIntent(new Intent()));
        assertEquals(AlarmAction.DISARM, AlarmWidgetSlot.fromIntent(intent(AlarmAction.DISARM)));
        assertNull(AlarmWidgetSlot.fromIntent(new Intent().putExtra(AlarmWidgetSlot.ACTION_EXTRA, "ARM_AWAY")));
        assertNull(AlarmWidgetSlot.fromIntent(new Intent().putExtra(AlarmWidgetSlot.ACTION_EXTRA, "disarm")));
        assertNull(AlarmWidgetSlot.fromIntent(new Intent().putExtra(AlarmWidgetSlot.ACTION_EXTRA, (String) null)));
    }

    @Test public void interruptedDisarmSetupCancelsOnlyItsPendingAllocationAndPreservesArmPrefs() {
        Map<String, ?> armBefore = new HashMap<>(prefs(AlarmAction.ARM_STAY).getAll());
        ids(AlarmAction.DISARM).add(9);
        prefs(AlarmAction.DISARM).edit().putStringSet("owned", strings(8, 9))
            .putInt("pending", 9).putInt("stage", WidgetSetupState.CONFIGURE).putInt("request", 1001)
            .putString("token", "inert-interrupted-flow").putLong("elapsed", SystemClock.elapsedRealtime())
            .putLong("wall", System.currentTimeMillis()).commit();
        open(AlarmAction.DISARM);
        assertEquals(armBefore, prefs(AlarmAction.ARM_STAY).getAll());
        assertEquals(8, prefs(AlarmAction.DISARM).getInt("active", -1));
        assertEquals(-1, prefs(AlarmAction.DISARM).getInt("pending", -1));
        assertEquals(strings(8), prefs(AlarmAction.DISARM).getStringSet("owned", new HashSet<>()));
        assertEquals(Arrays.asList(AlarmWidgetSlot.hostId(AlarmAction.DISARM) + ":9"), NamespaceHostShadow.deleted);
        assertEquals(new LinkedHashSet<>(Arrays.asList(7)), ids(AlarmAction.ARM_STAY));
        assertForeignHostUntouched();
    }

    @Test public void orphanRecoveryInEitherSlotCannotDeleteTheOtherSlotsWidget() {
        for (AlarmAction selected : AlarmAction.values()) {
            AlarmAction other = selected == AlarmAction.ARM_STAY ? AlarmAction.DISARM : AlarmAction.ARM_STAY;
            Map<String, ?> otherBefore = new HashMap<>(prefs(other).getAll());
            Set<Integer> otherIdsBefore = new LinkedHashSet<>(ids(other));
            int orphan = selected == AlarmAction.ARM_STAY ? 17 : 18;
            ids(selected).add(orphan); // Allocation exists only in the selected Android host, not prefs.
            open(selected);
            assertEquals(otherBefore, prefs(other).getAll());
            assertEquals(otherIdsBefore, ids(other));
            assertFalse(ids(selected).contains(orphan));
            assertTrue(NamespaceHostShadow.deleted.contains(AlarmWidgetSlot.hostId(selected) + ":" + orphan));
            assertForeignHostUntouched();
            controller.pause().stop().destroy(); controller = null;
        }
        assertEquals(2, NamespaceHostShadow.deleted.size());
    }

    @Test public void foreignIdInDisarmBookkeepingIsRemovedWithoutDeletingItsArmAllocation() {
        Map<String, ?> armBefore = new HashMap<>(prefs(AlarmAction.ARM_STAY).getAll());
        prefs(AlarmAction.DISARM).edit().putStringSet("owned", strings(7, 8)).commit();
        open(AlarmAction.DISARM);
        assertEquals(armBefore, prefs(AlarmAction.ARM_STAY).getAll());
        assertEquals(strings(8), prefs(AlarmAction.DISARM).getStringSet("owned", new HashSet<>()));
        assertTrue(ids(AlarmAction.ARM_STAY).contains(7));
        assertTrue(NamespaceHostShadow.deleted.isEmpty());
        assertForeignHostUntouched();
    }

    @Test public void removingDisarmThroughItsNormalConfirmationLeavesArmAndForeignWidgetsUntouched() {
        Map<String, ?> armBefore = new HashMap<>(prefs(AlarmAction.ARM_STAY).getAll());
        open(AlarmAction.DISARM);
        Button remove = findButton(controller.get().getWindow().getDecorView(), "Remove this hosted widget…");
        assertNotNull(remove);
        assertTrue(remove.performClick());
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        // AlertDialog dispatches its positive listener through the main Handler.
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(-1, prefs(AlarmAction.DISARM).getInt("active", 0));
        assertTrue(ids(AlarmAction.DISARM).isEmpty());
        assertEquals(armBefore, prefs(AlarmAction.ARM_STAY).getAll());
        assertEquals(new LinkedHashSet<>(Arrays.asList(7)), ids(AlarmAction.ARM_STAY));
        assertEquals(Arrays.asList(AlarmWidgetSlot.hostId(AlarmAction.DISARM) + ":8"), NamespaceHostShadow.deleted);
        assertForeignHostUntouched();
    }

    private void seed(AlarmAction action, int id) {
        prefs(action).edit().clear().putInt("active", id).putInt("pending", -1).putLong("revision", 4)
            .putStringSet("owned", strings(id)).commit();
        NamespaceHostShadow.allocations.put(AlarmWidgetSlot.hostId(action), new LinkedHashSet<>(Arrays.asList(id)));
    }
    private SharedPreferences prefs(AlarmAction action) {
        return context.getSharedPreferences(AlarmWidgetSlot.preferences(action), Context.MODE_PRIVATE);
    }
    private static Set<String> strings(int... ids) {
        Set<String> result = new HashSet<>();
        for (int id : ids) result.add(Integer.toString(id));
        return result;
    }
    private Set<Integer> ids(AlarmAction action) { return NamespaceHostShadow.allocations.get(AlarmWidgetSlot.hostId(action)); }
    private Intent intent(AlarmAction action) {
        return new Intent(context, WidgetSetupActivity.class).putExtra(AlarmWidgetSlot.ACTION_EXTRA, action.name());
    }
    private void open(AlarmAction action) {
        controller = Robolectric.buildActivity(WidgetSetupActivity.class, intent(action)).setup();
        assertFalse(controller.get().isFinishing());
    }
    private void assertForeignHostUntouched() {
        assertEquals(new LinkedHashSet<>(Arrays.asList(70, 80)), NamespaceHostShadow.allocations.get(FOREIGN_HOST));
    }
    private static Button findButton(View view, String label) {
        if (view instanceof Button && label.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = findButton(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Implements(AppWidgetHost.class)
    public static final class NamespaceHostShadow extends ShadowAppWidgetHost {
        static final Map<Integer, LinkedHashSet<Integer>> allocations = new HashMap<>();
        static final List<String> deleted = new ArrayList<>();
        @Implementation protected int[] getAppWidgetIds() {
            Set<Integer> own = allocations.get(getHostId());
            if (own == null) return new int[0];
            int[] result = new int[own.size()]; int index = 0;
            for (int id : own) result[index++] = id;
            return result;
        }
        @Implementation protected void deleteAppWidgetId(int id) {
            Set<Integer> own = allocations.get(getHostId());
            if (own == null || !own.remove(id)) throw new AssertionError("Cannot delete another host's allocation");
            deleted.add(getHostId() + ":" + id);
        }
        @Override @Implementation protected int allocateAppWidgetId() { throw new AssertionError("Setup recovery must not allocate"); }
        @Implementation protected void deleteHost() { throw new AssertionError("Must not delete a host namespace"); }
    }
}
