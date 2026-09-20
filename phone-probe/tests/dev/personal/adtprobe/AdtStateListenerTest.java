package dev.personal.adtprobe;

import android.app.Notification;
import android.os.Process;
import android.service.notification.StatusBarNotification;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Flag-only classification of ADT notifications; no content is read and nothing is queried. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class AdtStateListenerTest {
    @Test public void onlyADiscreteAdtNotificationIsAHint() {
        assertFalse(AdtStateListener.hintWorthy(null));
        assertFalse("Other apps' notifications are ignored", AdtStateListener.hintWorthy(posted("com.other.app", 0)));
        assertTrue(AdtStateListener.hintWorthy(posted(PhoneAlarmState.ADT_PACKAGE, 0)));
        assertTrue(AdtStateListener.hintWorthy(posted(PhoneAlarmState.ADT_PACKAGE, Notification.FLAG_AUTO_CANCEL)));
        assertFalse("An ongoing notification is re-posted on every content update",
            AdtStateListener.hintWorthy(posted(PhoneAlarmState.ADT_PACKAGE, Notification.FLAG_ONGOING_EVENT)));
        assertFalse(AdtStateListener.hintWorthy(posted(PhoneAlarmState.ADT_PACKAGE, Notification.FLAG_FOREGROUND_SERVICE)));
        assertFalse("A group summary is re-posted for every child",
            AdtStateListener.hintWorthy(posted(PhoneAlarmState.ADT_PACKAGE, Notification.FLAG_GROUP_SUMMARY)));
    }

    @SuppressWarnings("deprecation")
    private static StatusBarNotification posted(String pkg, int flags) {
        Notification notification = new Notification();
        notification.flags = flags;
        return new StatusBarNotification(pkg, pkg, 1, null, 0, 0, 0, notification, Process.myUserHandle(), System.currentTimeMillis());
    }
}
