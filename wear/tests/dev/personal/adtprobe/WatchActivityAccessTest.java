package dev.personal.adtprobe;

import android.app.KeyguardManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowKeyguardManager;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Calls the production access guard before onCreate: no GMS clients or transport are started. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class WatchActivityAccessTest {
    @Test public void anUnlockedWatchDoesNotRequireAConfiguredPinOrPattern() {
        WatchActivity activity = Robolectric.buildActivity(WatchActivity.class).get();
        ShadowKeyguardManager keyguard = Shadows.shadowOf(activity.getSystemService(KeyguardManager.class));
        keyguard.setIsDeviceLocked(false);
        keyguard.setKeyguardLocked(false);
        keyguard.setIsDeviceSecure(false);
        assertTrue("An unlocked watch without a PIN or pattern is permitted", unlocked(activity));
        keyguard.setIsDeviceSecure(true);
        assertTrue("Adding a screen lock does not block an already unlocked watch", unlocked(activity));
        keyguard.setIsDeviceSecure(false);
        assertTrue("Removing the configured screen lock keeps an unlocked watch permitted", unlocked(activity));
    }

    @Test public void eitherLockSignalImmediatelyClosesAccess() {
        WatchActivity activity = Robolectric.buildActivity(WatchActivity.class).get();
        ShadowKeyguardManager keyguard = Shadows.shadowOf(activity.getSystemService(KeyguardManager.class));
        for (boolean secure : new boolean[] {false, true}) {
            keyguard.setIsDeviceSecure(secure);
            for (boolean deviceLocked : new boolean[] {false, true}) {
                for (boolean keyguardLocked : new boolean[] {false, true}) {
                    keyguard.setIsDeviceLocked(deviceLocked);
                    keyguard.setKeyguardLocked(keyguardLocked);
                    assertEquals("Actual lock signals govern access regardless of configured credentials",
                        !deviceLocked && !keyguardLocked, unlocked(activity));
                }
            }
        }
    }

    private boolean unlocked(WatchActivity activity) {
        return ReflectionHelpers.callInstanceMethod(activity, "watchUnlocked");
    }
}
