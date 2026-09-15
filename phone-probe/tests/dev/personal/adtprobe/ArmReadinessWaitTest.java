package dev.personal.adtprobe;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ArmReadinessWaitTest {
    @Test public void firstRequestCannotBeReplacedOrExtended() {
        ArmReadinessWait wait = new ArmReadinessWait();
        assertTrue(wait.offer("watch-a", "request-a", 100, 120_000));
        assertFalse(wait.offer("watch-b", "request-b", 4_999, 120_000));
        assertFalse(wait.offer("watch-a", "request-a", 5_099, 120_000));
        assertEquals("watch-a", wait.source());
        assertEquals("request-a", wait.requestId());
        assertTrue(wait.isFresh(5_099));
        assertFalse(wait.isFresh(5_100));
        assertFalse(wait.hasRequest());
        assertFalse(wait.offer("watch-a", "request-c", 5_101, 120_000));
    }

    @Test public void exactSelectedNodeAndEveryReadinessGuardAreRequired() {
        ArmReadinessWait wait = new ArmReadinessWait();
        assertTrue(wait.offer("watch-a", "request-a", 100, 120_000));
        assertFalse(wait.canProceed(null, true, true, 101));
        assertFalse(wait.canProceed("watch-b", true, true, 102));
        assertFalse(wait.canProceed("watch-a", false, true, 103));
        assertFalse(wait.canProceed("watch-a", true, false, 104));
        assertFalse(wait.canProceed("watch-a", true, true, 105));
        assertFalse(wait.canProceed("watch-a", true, true, 1_604));
        assertTrue(wait.canProceed("watch-a", true, true, 1_605));
        // A node verification callback after the same deadline must not issue a challenge.
        assertFalse(wait.canProceed("watch-a", true, true, 5_100));
    }

    @Test public void briefInitialReadinessAndGuardDipsMustSettleAgain() {
        ArmReadinessWait wait = new ArmReadinessWait();
        assertTrue(wait.offer("watch-a", "request-a", 100, 120_000));
        assertFalse(wait.canProceed("watch-a", true, true, 100));
        assertFalse(wait.canProceed("watch-a", true, true, 1_200));
        assertFalse(wait.canProceed("watch-a", true, false, 1_300));
        assertFalse(wait.canProceed("watch-a", true, true, 1_400));
        assertFalse(wait.canProceed("watch-a", true, true, 2_899));
        assertTrue(wait.canProceed("watch-a", true, true, 2_900));
        assertFalse(wait.canProceed("watch-b", true, true, 2_901));
        assertFalse(wait.canProceed("watch-a", true, true, 2_902));
        assertFalse(wait.canProceed("watch-a", false, true, 3_000));
        assertFalse(wait.canProceed("watch-a", true, true, 3_100));
        assertFalse(wait.canProceed("watch-a", true, true, 4_599));
        assertTrue(wait.canProceed("watch-a", true, true, 4_600));
    }

    @Test public void stabilityCannotExtendTheOriginalOrSessionDeadline() {
        ArmReadinessWait wait = new ArmReadinessWait();
        assertTrue(wait.offer("watch-a", "request-a", 100, 120_000));
        assertFalse(wait.canProceed("watch-a", true, true, 4_000));
        assertFalse(wait.canProceed("watch-a", true, true, 5_099));
        assertFalse(wait.canProceed("watch-a", true, true, 5_100));
        assertFalse(wait.hasRequest());
        wait = new ArmReadinessWait();
        assertTrue(wait.offer("watch-a", "request-a", 100, 1_600));
        assertFalse(wait.canProceed("watch-a", true, true, 100));
        // Stability and session expiry coincide: expiry wins.
        assertFalse(wait.canProceed("watch-a", true, true, 1_600));
    }

    @Test public void sessionExpiryAndBackwardTimeCannotExtendReadiness() {
        ArmReadinessWait wait = new ArmReadinessWait();
        assertTrue(wait.offer("watch-a", "request-a", 100, 150));
        assertTrue(wait.isFresh(149));
        assertFalse(wait.isFresh(150));
        wait = new ArmReadinessWait();
        assertTrue(wait.offer("watch-a", "request-a", 100, 120_000));
        assertTrue(wait.isFresh(200));
        assertFalse(wait.isFresh(199));
        assertFalse(wait.isFresh(201));
    }

    @Test public void cancellationClearsIdentityWithoutReopeningTheSlot() {
        ArmReadinessWait wait = new ArmReadinessWait();
        assertFalse(wait.offer("watch-a", "request-a", 100, 100));
        assertTrue(wait.offer("watch-a", "request-a", 100, 120_000));
        wait.clear();
        assertFalse(wait.hasRequest());
        assertNull(wait.source());
        assertNull(wait.requestId());
        assertFalse(wait.canProceed("watch-a", true, true, 101));
        assertFalse(wait.offer("watch-a", "request-b", 102, 120_000));
    }
}
