package dev.personal.adtprobe;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

/** State updates can refresh the tile while the Activity is closed. Never sends commands. */
public final class WatchStateListener extends WearableListenerService {
    @Override public void onMessageReceived(MessageEvent event) {
        WatchAlarmStore.receive(this, event);
    }
}
