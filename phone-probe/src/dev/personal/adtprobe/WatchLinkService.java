package dev.personal.adtprobe;

import android.content.Context;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

/** Answers status queries and routes action-bound requests to the bounded widget service. */
public final class WatchLinkService extends WearableListenerService {
    @Override public void onMessageReceived(MessageEvent event) {
        if (event != null && AlarmStateProtocol.QUERY_PATH.equals(event.getPath())) {
            String request = AlarmStateProtocol.parseQuery(event.getData());
            if (request != null) PhoneStateLink.reply(this, event.getSourceNodeId(), request);
            return;
        }
        if (event != null && AlarmStateProtocol.TOGGLE_PATH.equals(event.getPath())) {
            receiveToggle(this, event);
            return;
        }
        if (event != null && (ArmExperimentProtocol.PREPARE_PATH.equals(event.getPath())
                || ArmExperimentProtocol.COMMIT_PATH.equals(event.getPath()))) {
            ArmExperimentService.receive(this, event);
        }
    }

    /** The listener worker reads ADT before any toggle can acquire a native-action grant. */
    static void receiveToggle(Context context, MessageEvent event) {
        if (context == null || event == null) return;
        try {
            if (!AlarmStateProtocol.TOGGLE_PATH.equals(event.getPath())
                    || !WatchProtocol.validNodeId(event.getSourceNodeId())
                    || AlarmStateProtocol.parseTap(event.getData()) == null) return;
            RoutineAccess.Snapshot permission = RoutineAccess.snapshot(context);
            if (permission == null || !permission.nodeId.equals(event.getSourceNodeId())) return;
            PhoneAlarmState.refresh(context);
            // receive rechecks setup after the network read and again immediately before activation.
            ArmExperimentService.receive(context, event);
        } catch (RuntimeException ignored) { /* No native grant exists if preflight cannot finish. */ }
    }
}
