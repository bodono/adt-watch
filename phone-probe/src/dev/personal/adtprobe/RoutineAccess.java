package dev.personal.adtprobe;

import android.app.Activity;
import android.app.KeyguardManager;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.companion.AssociationInfo;
import android.companion.CompanionDeviceManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.view.View;
import androidx.annotation.RequiresApi;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Revocable setup only. No command, request, challenge, deadline or PendingIntent is persisted. */
final class RoutineAccess {
    static final String PREFERENCES = "routine_watch_access";
    private static final ComponentName PROVIDER = new ComponentName("com.adtuk.adtukalarm",
        "com.alarm.alarmmobile.android.WidgetProvider");
    private static boolean storageFailed;

    private RoutineAccess() { }

    static final class Snapshot {
        final String nodeId;
        final long armRevision, disarmRevision, revision;
        final int associationId;
        private final int armWidgetId, disarmWidgetId;
        private Snapshot(String nodeId, int associationId, Slot arm, Slot disarm, long revision) {
            this.nodeId = nodeId; this.associationId = associationId;
            armRevision = arm.revision; disarmRevision = disarm.revision; this.revision = revision;
            armWidgetId = arm.id; disarmWidgetId = disarm.id;
        }
    }

    static final class Review {
        final long revision;
        private final Slot arm, disarm;
        private Review(long revision, Slot arm, Slot disarm) {
            this.revision = revision; this.arm = arm; this.disarm = disarm;
        }
    }

    static final class AssociationChoice {
        final int id;
        final String label; // Transient UI only, never stored or logged.
        AssociationChoice(int id, String label) { this.id = id; this.label = label; }
    }

    private static final class Slot {
        final int id;
        final long revision;
        Slot(int id, long revision) { this.id = id; this.revision = revision; }
        boolean same(Slot other) { return other != null && id == other.id && revision == other.revision; }
    }

    static synchronized Snapshot snapshot(Context context) {
        try {
            if (storageFailed || Build.VERSION.SDK_INT < 35 || !securePhone(context)) return null;
            SharedPreferences access = preferences(context);
            if (!access.getBoolean("enabled", false)) return null;
            String node = access.getString("node", "");
            int association = access.getInt("association", -1);
            long revision = access.getLong("revision", 0);
            if (!WatchProtocol.validNodeId(node) || revision <= 0 || !hasAssociation(context, association)) return null;
            Slot arm = slot(context, AlarmAction.ARM_STAY), disarm = slot(context, AlarmAction.DISARM);
            if (arm == null || disarm == null || arm.id == disarm.id
                    || arm.id != access.getInt("arm_widget", -1) || disarm.id != access.getInt("disarm_widget", -1)
                    || arm.revision != access.getLong("arm_revision", -1)
                    || disarm.revision != access.getLong("disarm_revision", -1)
                    || access.getLong("revision", 0) != revision || !access.getBoolean("enabled", false)
                    || !matchesPreferences(context, AlarmAction.ARM_STAY, arm)
                    || !matchesPreferences(context, AlarmAction.DISARM, disarm)) return null;
            return new Snapshot(node, association, arm, disarm, revision);
        } catch (RuntimeException ignored) { return null; }
    }

    static synchronized boolean stillValid(Context context, Snapshot previous) {
        if (previous == null) return false;
        Snapshot current = snapshot(context);
        return current != null && current.revision == previous.revision
            && current.nodeId.equals(previous.nodeId) && current.associationId == previous.associationId
            && current.armRevision == previous.armRevision && current.disarmRevision == previous.disarmRevision
            && current.armWidgetId == previous.armWidgetId && current.disarmWidgetId == previous.disarmWidgetId;
    }

    /** Revokes old grants even when already disabled. The caller also cancels the running service. */
    static synchronized boolean disable(Context context) {
        try {
            SharedPreferences access = preferences(context);
            long revision = access.getLong("revision", 0);
            long next = revision >= 0 && revision < Long.MAX_VALUE ? revision + 1 : Long.MAX_VALUE;
            boolean saved = access.edit().clear().putBoolean("enabled", false).putLong("revision", next).commit();
            storageFailed = !saved;
            return saved;
        } catch (RuntimeException ignored) { storageFailed = true; return false; }
    }

    static synchronized Review prepareReview(Context context) {
        try {
            if (Build.VERSION.SDK_INT < 35 || storageFailed || !securePhone(context)) return null;
            long revision = preferences(context).getLong("revision", 0);
            if (revision < 0 || revision == Long.MAX_VALUE) return null;
            Slot arm = slot(context, AlarmAction.ARM_STAY), disarm = slot(context, AlarmAction.DISARM);
            if (arm == null || disarm == null || arm.id == disarm.id
                    || preferences(context).getLong("revision", 0) != revision
                    || !matchesPreferences(context, AlarmAction.ARM_STAY, arm)
                    || !matchesPreferences(context, AlarmAction.DISARM, disarm)) return null;
            return new Review(revision, arm, disarm);
        } catch (RuntimeException ignored) { return null; }
    }

    /** Called only following the user's positive review and a fresh async single-node recheck. */
    static synchronized boolean enableReviewed(Activity activity, Review review, String node, int association) {
        if (review == null || !visibleUnlocked(activity) || !WatchProtocol.validNodeId(node)) return false;
        try {
            Review current = prepareReview(activity);
            if (current == null || current.revision != review.revision || !current.arm.same(review.arm)
                    || !current.disarm.same(review.disarm) || !hasAssociation(activity, association)) return false;
            boolean saved = preferences(activity).edit().clear().putBoolean("enabled", true)
                .putString("node", node).putInt("association", association)
                .putInt("arm_widget", current.arm.id).putInt("disarm_widget", current.disarm.id)
                .putLong("arm_revision", current.arm.revision).putLong("disarm_revision", current.disarm.revision)
                .putLong("revision", current.revision + 1).commit();
            storageFailed = !saved;
            if (!saved) return false;
            if (snapshot(activity) != null) return true;
            disable(activity); // A failed final validation must not become enabled later by itself.
            return false;
        } catch (RuntimeException ignored) { return false; }
    }

    static List<AssociationChoice> associations(Context context) {
        if (Build.VERSION.SDK_INT < 35) return Collections.emptyList();
        try { return Api35.choices(context); }
        catch (RuntimeException ignored) { return Collections.emptyList(); }
    }

    static boolean visibleUnlocked(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || !securePhone(activity)
                || activity.getWindow() == null) return false;
        KeyguardManager keyguard = activity.getSystemService(KeyguardManager.class);
        View decor = activity.getWindow().getDecorView();
        return !keyguard.isKeyguardLocked() && !keyguard.isDeviceLocked()
            && decor.getWindowVisibility() == View.VISIBLE && decor.isShown();
    }

    private static boolean securePhone(Context context) {
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return keyguard != null && keyguard.isDeviceSecure();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static boolean hasAssociation(Context context, int id) {
        return Build.VERSION.SDK_INT >= 35 && id > 0 && Api35.contains(context, id);
    }

    private static Slot slot(Context context, AlarmAction action) {
        try {
            SharedPreferences stored = context.getSharedPreferences(AlarmWidgetSlot.preferences(action), Context.MODE_PRIVATE);
            int id = stored.getInt("active", -1);
            long revision = stored.getLong("revision", 0);
            if (id <= 0 || revision <= 0 || stored.getInt("pending", -1) > 0
                    || stored.getInt("stage", WidgetSetupState.NONE) != WidgetSetupState.NONE
                    || !stored.getStringSet("owned", Collections.emptySet()).contains(Integer.toString(id))) return null;
            boolean owned = false;
            // Ownership query only: never start listening, allocate, bind, or remove a widget.
            for (int candidate : new AppWidgetHost(context.getApplicationContext(), AlarmWidgetSlot.hostId(action)).getAppWidgetIds())
                if (candidate == id) owned = true;
            if (!owned) return null;
            PackageInfo installed = context.getPackageManager().getPackageInfo(PROVIDER.getPackageName(), 0);
            if (installed.getLongVersionCode() != 2307 || installed.applicationInfo == null || !installed.applicationInfo.enabled)
                return null;
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            AppWidgetProviderInfo info = manager.getAppWidgetInfo(id);
            if (!allowed(info)) return null;
            boolean available = false;
            for (AppWidgetProviderInfo candidate : manager.getInstalledProvidersForProfile(Process.myUserHandle()))
                if (allowed(candidate)) available = true;
            return available && stored.getInt("active", -1) == id && stored.getLong("revision", 0) == revision
                && stored.getInt("pending", -1) <= 0 ? new Slot(id, revision) : null;
        } catch (Exception ignored) { return null; }
    }

    private static boolean allowed(AppWidgetProviderInfo info) {
        return info != null && PROVIDER.equals(info.provider) && Process.myUserHandle().equals(info.getProfile());
    }

    private static boolean matchesPreferences(Context context, AlarmAction action, Slot slot) {
        SharedPreferences stored = context.getSharedPreferences(AlarmWidgetSlot.preferences(action), Context.MODE_PRIVATE);
        return stored.getInt("active", -1) == slot.id && stored.getLong("revision", 0) == slot.revision
            && stored.getInt("pending", -1) <= 0 && stored.getInt("stage", WidgetSetupState.NONE) == WidgetSetupState.NONE
            && stored.getStringSet("owned", Collections.emptySet()).contains(Integer.toString(slot.id));
    }

    static String safeName(CharSequence raw) {
        if (raw == null) return null;
        String name = raw.toString().replaceAll("[\\p{Cntrl}\\u202a-\\u202e\\u2066-\\u2069]", " ").trim();
        if (name.isEmpty() || name.matches("(?i).*(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}.*")) return null;
        return name.length() <= 80 ? name : name.substring(0, 80);
    }

    @RequiresApi(35)
    private static final class Api35 {
        static boolean contains(Context context, int id) {
            CompanionDeviceManager manager = context.getSystemService(CompanionDeviceManager.class);
            if (manager == null) return false;
            for (AssociationInfo association : manager.getMyAssociations())
                if (association.getId() == id && !association.isSelfManaged()) return true;
            return false;
        }
        static List<AssociationChoice> choices(Context context) {
            List<AssociationChoice> choices = new ArrayList<>();
            CompanionDeviceManager manager = context.getSystemService(CompanionDeviceManager.class);
            if (manager == null) return choices;
            for (AssociationInfo association : manager.getMyAssociations()) {
                if (association.getId() <= 0 || association.isSelfManaged()) continue;
                String name = safeName(association.getDisplayName());
                if (name == null) name = bluetoothName(context, association);
                // An unidentifiable record is not silently approved or labeled as the user's watch.
                if (name != null) choices.add(new AssociationChoice(association.getId(), name));
            }
            return choices;
        }
        static String bluetoothName(Context context, AssociationInfo association) {
            if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                return null;
            try {
                BluetoothDevice device = association.getAssociatedDevice() == null ? null
                    : association.getAssociatedDevice().getBluetoothDevice();
                if (device == null && association.getDeviceMacAddress() != null) {
                    BluetoothManager bluetooth = context.getSystemService(BluetoothManager.class);
                    if (bluetooth != null && bluetooth.getAdapter() != null)
                        device = bluetooth.getAdapter().getRemoteDevice(association.getDeviceMacAddress().toString());
                }
                return device == null ? null : safeName(device.getName());
            } catch (SecurityException | IllegalArgumentException ignored) { return null; }
        }
    }
}
