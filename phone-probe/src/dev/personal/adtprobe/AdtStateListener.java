package dev.personal.adtprobe;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/** User-enabled Android notification access. Only exact ADT panel reports are processed. */
public final class AdtStateListener extends NotificationListenerService {
    @Override public void onListenerConnected() {
        super.onListenerConnected();
        PhoneAlarmState.listenerConnecting(this);
        try { PhoneAlarmState.reconcile(this, getActiveNotifications()); }
        catch (RuntimeException failure) { PhoneAlarmState.listenerDisconnected(this); }
    }

    @Override public void onNotificationPosted(StatusBarNotification notification) {
        if (notification != null && PhoneAlarmState.ADT_PACKAGE.equals(notification.getPackageName()))
            PhoneAlarmState.posted(this, notification);
    }

    @Override public void onListenerDisconnected() {
        PhoneAlarmState.listenerDisconnected(this);
        super.onListenerDisconnected();
    }

    @Override public void onDestroy() {
        PhoneAlarmState.listenerDisconnected(this);
        super.onDestroy();
    }
    // Removal/dismissal is not a state transition and must never infer an opposite state.
}
