package dev.personal.adtprobe;

import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/** User-enabled Android notification access. Only exact ADT panel reports are processed. */
public final class AdtStateListener extends NotificationListenerService {
    private static final long RECONCILE_RETRY_MS = 2_000;
    private static final int RECONCILE_RETRIES = 5;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable retry = this::reconcile;
    private int retries;

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        PhoneAlarmState.listenerConnecting(this);
        retries = 0;
        reconcile();
    }

    /**
     * getActiveNotifications() returns null on a transient binder failure. Left alone, that kept
     * the ledger unreconciled (reported OFFLINE) and dropped every later posted report until the
     * listener happened to reconnect. Retry briefly, and again on the next posted notification.
     */
    private void reconcile() {
        handler.removeCallbacks(retry);
        if (!PhoneAlarmState.awaitingReconcile()) return;
        StatusBarNotification[] active;
        try { active = getActiveNotifications(); }
        catch (RuntimeException failure) { PhoneAlarmState.listenerDisconnected(this); return; }
        PhoneAlarmState.reconcile(this, active);
        if (active == null && retries++ < RECONCILE_RETRIES) handler.postDelayed(retry, RECONCILE_RETRY_MS);
    }

    @Override public void onNotificationPosted(StatusBarNotification notification) {
        if (PhoneAlarmState.awaitingReconcile()) reconcile();
        if (notification != null && PhoneAlarmState.ADT_PACKAGE.equals(notification.getPackageName()))
            PhoneAlarmState.posted(this, notification);
    }

    @Override public void onListenerDisconnected() {
        handler.removeCallbacks(retry);
        PhoneAlarmState.listenerDisconnected(this);
        super.onListenerDisconnected();
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(retry);
        PhoneAlarmState.listenerDisconnected(this);
        super.onDestroy();
    }
    // Removal/dismissal is not a state transition and must never infer an opposite state.
}
