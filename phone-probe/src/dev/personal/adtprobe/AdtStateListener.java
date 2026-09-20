package dev.personal.adtprobe;

import android.app.Notification;
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
        if (!hintWorthy(notification)) return;
        Log.i("AdtPhoneStatus", SystemClock.elapsedRealtime() + " ADT_NOTIFICATION");
        PhoneAlarmState.hint(this);
    }

    /**
     * Only a discrete ADT notification is a hint. An ongoing (foreground-service) notification is
     * posted again on every content update and a group summary on every child, and neither says
     * anything happened to the alarm, while each hint costs a portal read. Only flags are read.
     */
    static boolean hintWorthy(StatusBarNotification notification) {
        if (notification == null || !PhoneAlarmState.ADT_PACKAGE.equals(notification.getPackageName())) return false;
        Notification content = notification.getNotification();
        return content != null && (content.flags & (Notification.FLAG_ONGOING_EVENT
            | Notification.FLAG_FOREGROUND_SERVICE | Notification.FLAG_GROUP_SUMMARY)) == 0;
    }
}
