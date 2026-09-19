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
        assertFalse(wait.canProceed(null, true, 1, true, 101));
        assertFalse(wait.canProceed("watch-b", true, 1, true, 102));
        assertFalse(wait.canProceed("watch-a", false, 1, true, 103));
        assertFalse(wait.canProceed("watch-a", true, 1, false, 104));
        assertFalse(wait.canProceed("watch-a", true, 1, true, 105));
        assertFalse(wait.canProceed("watch-a", true, 1, true, 604));
        assertTrue(wait.canProceed("watch-a", true, 1, true, 605));
        // A node verification callback after the same deadline must not issue a challenge.
        assertFalse(wait.canProceed("watch-a", true, 1, true, 5_100));
    }

    @Test public void briefInitialReadinessAndGuardDipsMustSettleAgain() {
        ArmReadinessWait wait = new ArmReadinessWait();
        assertTrue(wait.offer("watch-a", "request-a", 100, 120_000));
        assertFalse(wait.canProceed("watch-a", true, 1, true, 100));
        assertFalse(wait.canProceed("watch-a", true, 1, true, 400));
        assertFalse(wait.canProceed("watch-a", true, 1, false, 500));
        assertFalse(wait.canProceed("watch-a", true, 1, true, 600));
        assertFalse(wait.canProceed("watch-a", true, 1, true, 1_099));
        assertTrue(wait.canProceed("watch-a", true, 1, true, 1_100));
        assertFalse(wait.canProceed("watch-b", true, 1, true, 1_101));
        assertFalse(wait.canProceed("watch-a", true, 1, true, 1_102));
        assertFalse(wait.canProceed("watch-a", false, 1, true, 1_200));
        assertFalse(wait.canProceed("watch-a", true, 1, true, 1_300));
        assertFalse(wait.canProceed("watch-a", true, 1, true, 1_799));
        assertTrue(wait.canProceed("watch-a", true, 1, true, 1_800));
    }

    @Test public void aWidgetRenderRestartsTheStableWindowEvenWhenReadinessNeverDipped() {
        ArmReadinessWait wait = new ArmReadinessWait();
        assertTrue(wait.offer("watch-a", "request-a", 100, 120_000));
        assertFalse(wait.canProceed("watch-a", true, 1, true, 100));
        // The provider re-rendered between ticks: the widget looked ready at both, but its generation moved.
        assertFalse(wait.canProceed("watch-a", true, 2, true, 400));
        assertFalse(wait.canProceed("watch-a", true, 2, true, 899));
        assertTrue(wait.canProceed("watch-a", true, 2, true, 900));
        assertFalse(wait.canProceed("watch-a", true, 3, true, 901));
        assertFalse(wait.canProceed("watch-a", true, 3, true, 1_400));
        assertTrue(wait.canProceed("watch-a", true, 3, true, 1_401));
        assertTrue("An unchanged generation keeps the settled window", wait.canProceed("watch-a", true, 3, true, 1_402));
    }

    @Test public void stabilityCannotExtendTheOriginalOrSessionDeadline() {
        ArmReadinessWait wait = new ArmReadinessWait();
        assertTrue(wait.offer("watch-a", "request-a", 100, 120_000));
        assertFalse(wait.canProceed("watch-a", true, 1, true, 4_700));
        assertFalse(wait.canProceed("watch-a", true, 1, true, 5_099));
        assertFalse(wait.canProceed("watch-a", true, 1, true, 5_100));
        assertFalse(wait.hasRequest());
        wait = new ArmReadinessWait();
        assertTrue(wait.offer("watch-a", "request-a", 100, 1_600));
        assertFalse(wait.canProceed("watch-a", true, 1, true, 1_100));
        // Stability and session expiry coincide: expiry wins.
        assertFalse(wait.canProceed("watch-a", true, 1, true, 1_600));
        assertFalse(wait.hasRequest());
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
        assertFalse(wait.canProceed("watch-a", true, 1, true, 101));
        assertFalse(wait.offer("watch-a", "request-b", 102, 120_000));
    }
}
