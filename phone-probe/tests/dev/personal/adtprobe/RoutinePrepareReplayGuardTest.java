package dev.personal.adtprobe;

import org.junit.Test;
import static org.junit.Assert.*;

public final class RoutinePrepareReplayGuardTest {
    @Test public void duplicatesDoNotExtendRetentionAndDistinctActionsRemainIndependent() {
        RoutinePrepareReplayGuard guard = new RoutinePrepareReplayGuard();
        assertTrue(guard.admit("watch", AlarmAction.DISARM, "request", 100));
        assertFalse(guard.admit("watch", AlarmAction.DISARM, "request", 59_999));
        assertTrue(guard.admit("watch", AlarmAction.ARM_STAY, "request", 60_000));
        assertTrue(guard.admit("watch", AlarmAction.DISARM, "request", 60_100));
        assertFalse(guard.admit("watch", AlarmAction.DISARM, "request", 60_101));
    }

    @Test public void saturationRejectsNewRequestsWithoutEvictingRecentIdentity() {
        RoutinePrepareReplayGuard guard = new RoutinePrepareReplayGuard();
        for (int i = 0; i < 64; i++) assertTrue(guard.admit("watch", AlarmAction.DISARM, "r" + i, i));
        assertFalse(guard.admit("watch", AlarmAction.DISARM, "overflow", 100));
        assertFalse(guard.admit("watch", AlarmAction.DISARM, "r0", 101));
        assertTrue(guard.admit("watch", AlarmAction.DISARM, "fresh", 60_000));
    }
}
