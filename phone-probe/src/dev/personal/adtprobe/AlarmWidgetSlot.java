package dev.personal.adtprobe;

import android.content.Intent;

/** Separate native widget namespaces; the existing Arm Stay allocation needs no migration. */
final class AlarmWidgetSlot {
    static final String ACTION_EXTRA = "dev.personal.adtprobe.SETUP_ACTION";

    private AlarmWidgetSlot() { }

    static int hostId(AlarmAction action) {
        if (action == null) throw new IllegalArgumentException("An action is required");
        return action == AlarmAction.ARM_STAY ? 0x41445401 : 0x41445402;
    }

    static String preferences(AlarmAction action) {
        if (action == null) throw new IllegalArgumentException("An action is required");
        return action == AlarmAction.ARM_STAY ? "widget_host" : "widget_host_disarm";
    }

    static AlarmAction fromIntent(Intent intent) {
        if (intent == null || !intent.hasExtra(ACTION_EXTRA)) return AlarmAction.ARM_STAY;
        try { return AlarmAction.valueOf(intent.getStringExtra(ACTION_EXTRA)); }
        catch (IllegalArgumentException | NullPointerException error) { return null; }
    }
}
