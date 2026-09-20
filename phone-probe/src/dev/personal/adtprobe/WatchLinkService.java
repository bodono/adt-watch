package dev.personal.adtprobe;

import android.content.Context;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

/** Answers status queries and routes action-bound requests to the bounded widget service. */
public final class WatchLinkService extends WearableListenerService {
    @Override public void onMessageReceived(MessageEvent event) {
        if (event != null && AlarmStateProtocol.QUERY_PATH.equals(event.getPath())) {
            AlarmStateProtocol.Query request = AlarmStateProtocol.parseQuery(event.getData());
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
                    || !WatchProtocol.validNodeId(event.getSourceNodeId())) return;
            AlarmStateProtocol.Tap tap = AlarmStateProtocol.parseTap(event.getData());
            if (tap == null) return;
            RoutineAccess.Snapshot permission = RoutineAccess.snapshot(context);
            if (permission == null) {
                // Like the SETUP status reply, any node learns that no watch is approved here.
                ArmExperimentService.declineTap(context, event.getSourceNodeId(), tap.action, tap.request,
                    AlarmStateProtocol.DeclineReason.UNAVAILABLE);
                return;
            }
            if (!permission.nodeId.equals(event.getSourceNodeId())) return;
            // An unlocked phone, a session still closing or open widget setup is declined here on
            // the worker, without an ADT read. Forwarding such a tap instead would let the main
            // thread create readiness from the cached state if the phone locked in the meantime.
            // A phone-prepared diagnostic session waiting for this very tap is the exception: it
            // gets the tap's own read below and adopts the tap only if that read still matches.
            if (ArmExperimentService.cannotStartReadiness(context) && !ArmExperimentService.canAdoptWatchTap(tap.action)) {
                ArmExperimentService.declineTap(context, event.getSourceNodeId(), tap.action, tap.request,
                    AlarmStateProtocol.DeclineReason.UNAVAILABLE);
                return;
            }
            // A command's preflight is its own read, never the passive three-second reuse: a read
            // from before the tap could hide a state change the already-satisfied and changed-state
            // checks must see. Only a read that began after the tap arrived is shared. A tap whose
            // read did not complete (lock wait over, read failed) is declined; the display keeps
            // whatever observation it had.
            PhoneAlarmState.Snapshot state = PhoneAlarmState.refresh(context, 0, AlarmStateProtocol.QueryIntent.USER);
            if (!state.verified) {
                ArmExperimentService.declineTap(context, event.getSourceNodeId(), tap.action, tap.request,
                    AlarmStateProtocol.DeclineReason.UNAVAILABLE);
                PhoneStateLink.publish(context);
                return;
            }
            // receive rechecks setup after the network read and again immediately before activation.
            ArmExperimentService.receive(context, event);
        } catch (RuntimeException ignored) { /* No native grant exists if preflight cannot finish. */ }
    }
}
