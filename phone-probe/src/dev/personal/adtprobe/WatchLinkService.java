package dev.personal.adtprobe;

import android.os.SystemClock;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Replies to harmless pings and routes action-bound requests to the bounded widget service. */
public final class WatchLinkService extends WearableListenerService {
    private static final WatchProtocol.ReplayGuard RECENT = new WatchProtocol.ReplayGuard();

    @Override public void onMessageReceived(MessageEvent event) {
        if (event != null && AlarmStateProtocol.QUERY_PATH.equals(event.getPath())) {
            String request = AlarmStateProtocol.parseQuery(event.getData());
            if (request != null) PhoneStateLink.reply(this, event.getSourceNodeId(), request);
            return;
        }
        if (event != null && (ArmExperimentProtocol.PREPARE_PATH.equals(event.getPath())
                || AlarmStateProtocol.TOGGLE_PATH.equals(event.getPath())
                || ArmExperimentProtocol.COMMIT_PATH.equals(event.getPath()))) {
            ArmExperimentService.receive(this, event);
            return;
        }
        if (event == null || !WatchProtocol.PING_PATH.equals(event.getPath())
                || !WatchProtocol.validNodeId(event.getSourceNodeId())) return;
        final WatchProtocol.Message ping;
        try { ping = WatchProtocol.parse(event.getData(), "PING"); }
        catch (IllegalArgumentException error) { return; }
        // This service only echoes harmless pings. Do not compare the watch's wall clock with
        // the phone's; the watch enforces its own ten-second monotonic reply deadline.
        if (!RECENT.admit(event.getSourceNodeId(), ping, SystemClock.elapsedRealtime())) return;

        long ticket = WatchLink.received(this);
        boolean accepted = false;
        try {
            // WearableListenerService callbacks run on a background thread. Bound the wait so
            // the service callback keeps this process available briefly; no retry or FGS.
            Tasks.await(Wearable.getMessageClient(this).sendMessage(event.getSourceNodeId(),
                WatchProtocol.ACK_PATH, WatchProtocol.acknowledgement(ping)), 2, TimeUnit.SECONDS);
            accepted = true; // Transport submission only, never an alarm-state acknowledgement.
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | RuntimeException error) {
            // Do not log incoming payloads, node IDs, account data or exception messages.
        }
        WatchLink.acknowledged(this, ticket, accepted);
    }
}
