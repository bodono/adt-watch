package dev.personal.adtprobe;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.os.SystemClock;
import android.util.Log;

/** Optional refresh hints; notification text is never used as alarm state. */
public final class AdtStateListener extends NotificationListenerService {
    @Override public void onListenerConnected() {
        super.onListenerConnected();
        PhoneAlarmState.hint(this);
    }
    @Override public void onNotificationPosted(StatusBarNotification notification) {
        if (notification != null && PhoneAlarmState.ADT_PACKAGE.equals(notification.getPackageName())) {
            Log.i("AdtPhoneStatus", SystemClock.elapsedRealtime() + " ADT_NOTIFICATION");
            PhoneAlarmState.hint(this);
        }
    }
}
