package dev.personal.adtprobe;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure invented query fixtures: no Android services, ADT session, network or alarm commands. */
public final class AdtLiveLedgerTest {
    private static final String SYSTEM = "fixture-system", PARTITION = "fixture-partition";
    private static final int BOOT = 7;
    private static final AlarmStateProtocol.State DISARMED = AlarmStateProtocol.State.DISARMED;
    private static final AlarmStateProtocol.State STAY = AlarmStateProtocol.State.ARMED_STAY;

    @Test public void repeatedActualStateGetsFreshObservationWithoutInventingStateChange() {
        AdtLiveLedger ledger = ready(DISARMED);
        AdtLiveLedger.Snapshot first = ledger.snapshot(1_100, BOOT);
        assertTrue(ledger.observe(sample(DISARMED, 50_000, 50_100, false)));
        AdtLiveLedger.Snapshot second = ledger.snapshot(50_100, BOOT);
        assertEquals(first.revision, second.revision);
        assertNotEquals(first.observationId, second.observationId);
        assertTrue(AlarmStateProtocol.uuid(second.observationId));
        assertEquals(100, second.observationAgeMillis);
        assertTrue(ledger.snapshot(109_999, BOOT).enabled);
        assertEquals(AlarmStateProtocol.Availability.STALE, ledger.snapshot(110_000, BOOT).availability);
        assertFalse(ledger.snapshot(110_000, BOOT).enabled);
    }

    @Test public void changedStateChangesRevisionAndFixedAvailableAction() {
        AdtLiveLedger ledger = ready(DISARMED);
        String first = ledger.snapshot(1_100, BOOT).revision;
        assertTrue(ledger.observe(sample(STAY, 2_000, 2_100, false)));
        AdtLiveLedger.Snapshot armed = ledger.snapshot(2_100, BOOT);
        assertNotEquals(first, armed.revision);
        assertEquals(AlarmAction.DISARM, armed.action);
        assertTrue(ledger.observe(sample(AlarmStateProtocol.State.ARMED_AWAY, 3_000, 3_100, false)));
        assertNotEquals(armed.revision, ledger.snapshot(3_100, BOOT).revision);
        assertEquals(AlarmAction.DISARM, ledger.snapshot(3_100, BOOT).action);
    }

    @Test public void wrongSourceExpiredDuplicateAndOutOfOrderResponsesCannotReplaceFreshData() {
        AdtLiveLedger ledger = ready(DISARMED);
        assertTrue(ledger.observe(sample(STAY, 4_000, 4_100, false)));
        String observation = ledger.snapshot(4_100, BOOT).observationId;
        assertFalse(ledger.observe(new AdtLiveLedger.Sample(DISARMED, "different-system", PARTITION, 5_000, 5_100, BOOT, false)));
        assertFalse(ledger.observe(new AdtLiveLedger.Sample(DISARMED, SYSTEM, "different-partition", 5_000, 5_100, BOOT, false)));
        assertFalse(ledger.observe(sample(DISARMED, 5_000, 15_001, false)));
        assertFalse(ledger.observe(sample(DISARMED, 4_000, 4_200, false)));
        assertFalse(ledger.observe(sample(DISARMED, 3_000, 5_200, false)));
        assertFalse(ledger.observe(sample(DISARMED, 2_000, 2_100, false)));
        assertEquals(observation, ledger.snapshot(5_200, BOOT).observationId);
        assertEquals(STAY, ledger.snapshot(5_200, BOOT).state);
        assertTrue(ledger.snapshot(5_200, BOOT).enabled);
    }

    @Test public void boundedQueryTimingAndUnknownStateAreExplicit() {
        AdtLiveLedger ledger = new AdtLiveLedger(SYSTEM, PARTITION);
        assertFalse(ledger.observe(sample(DISARMED, -1, 100, false)));
        assertFalse(ledger.observe(sample(DISARMED, 200, 100, false)));
        assertFalse(ledger.observe(new AdtLiveLedger.Sample(DISARMED, SYSTEM, PARTITION, 0, 100, -1, false)));
        assertFalse(ledger.observe(sample(null, 0, 100, false)));
        assertTrue(ledger.observe(sample(DISARMED, 1_000, 11_000, false)));
        assertEquals(10_000, ledger.snapshot(11_000, BOOT).observationAgeMillis);
        assertTrue(ledger.observe(sample(AlarmStateProtocol.State.UNKNOWN, 12_000, 12_100, false)));
        assertEquals(AlarmStateProtocol.Availability.NO_STATE, ledger.snapshot(12_100, BOOT).availability);
        assertNull(ledger.snapshot(12_100, BOOT).action);
    }

    @Test public void beginRequiresCurrentFreshSteadyStateAndCannotReplacePendingRequest() {
        AdtLiveLedger ledger = ready(DISARMED);
        String revision = ledger.snapshot(1_100, BOOT).revision;
        assertFalse(ledger.begin(id(), AlarmAction.ARM_STAY, id(), 1_200, BOOT));
        assertFalse(ledger.begin(revision, AlarmAction.DISARM, id(), 1_200, BOOT));
        assertFalse(ledger.begin(revision, AlarmAction.ARM_STAY, "invalid", 1_200, BOOT));
        assertFalse(ledger.begin(revision, null, id(), 1_200, BOOT));
        assertEquals(AdtLiveLedger.Outcome.NONE, ledger.snapshot(1_200, BOOT).outcome);
        String request = id();
        assertTrue(ledger.begin(revision, AlarmAction.ARM_STAY, request, 2_000, BOOT));
        assertFalse(ledger.begin(revision, AlarmAction.ARM_STAY, request, 2_001, BOOT));
        assertFalse(ledger.begin(revision, AlarmAction.ARM_STAY, id(), 2_002, BOOT));
        assertEquals(request, ledger.snapshot(2_002, BOOT).requestId);
        assertEquals(revision, ledger.snapshot(2_002, BOOT).revision);
        assertEquals(AlarmStateProtocol.Availability.BUSY, ledger.snapshot(2_002, BOOT).availability);
        assertEquals("-", ledger.snapshot(2_002, BOOT).completedRequest);
    }

    @Test public void completionRequiresQueryStartedAfterBeginAndSteadyExactTarget() {
        AdtLiveLedger ledger = ready(DISARMED);
        String request = begin(ledger, AlarmAction.ARM_STAY, 2_000);
        // This response was already in flight at dispatch: useful state, not command confirmation.
        assertTrue(ledger.observe(sample(STAY, 1_500, 2_100, false)));
        assertTrue(ledger.snapshot(2_100, BOOT).pending);
        assertTrue(ledger.observe(sample(STAY, 2_000, 2_200, false)));
        assertTrue(ledger.snapshot(2_200, BOOT).pending);
        assertTrue(ledger.observe(sample(STAY, 2_300, 2_400, true)));
        assertTrue(ledger.snapshot(2_400, BOOT).providerBusy);
        assertTrue(ledger.snapshot(2_400, BOOT).pending);
        assertTrue(ledger.observe(sample(AlarmStateProtocol.State.ARMED_AWAY, 2_500, 2_600, false)));
        assertTrue(ledger.snapshot(2_600, BOOT).pending);
        assertTrue(ledger.observe(sample(STAY, 2_700, 2_800, false)));
        AdtLiveLedger.Snapshot complete = ledger.snapshot(2_800, BOOT);
        assertEquals(AdtLiveLedger.Outcome.CONFIRMED, complete.outcome);
        assertFalse(complete.pending); assertTrue(complete.enabled);
        assertEquals(request, complete.completedRequest);
        assertEquals(AlarmAction.DISARM, complete.action);
    }

    @Test public void busyIsTransientAndCannotAuthorizeNewCommand() {
        AdtLiveLedger ledger = ready(STAY);
        String revision = ledger.snapshot(1_100, BOOT).revision;
        assertTrue(ledger.observe(sample(STAY, 2_000, 2_100, true)));
        assertEquals(AlarmStateProtocol.Availability.BUSY, ledger.snapshot(2_100, BOOT).availability);
        assertFalse(ledger.begin(revision, AlarmAction.DISARM, id(), 2_200, BOOT));
        assertTrue(ledger.observe(sample(STAY, 3_000, 3_100, false)));
        assertEquals(revision, ledger.snapshot(3_100, BOOT).revision);
        assertTrue(ledger.snapshot(3_100, BOOT).enabled);
        String request = begin(ledger, AlarmAction.DISARM, 3_200);
        assertTrue(ledger.observe(sample(DISARMED, 3_300, 3_400, false)));
        assertEquals(request, ledger.snapshot(3_400, BOOT).completedRequest);
    }

    @Test public void timeoutStopsProgressWithoutHidingFreshUnchangedAdtState() {
        AdtLiveLedger ledger = ready(DISARMED);
        String request = begin(ledger, AlarmAction.ARM_STAY, 2_000);
        assertTrue(ledger.observe(sample(DISARMED, 31_800, 31_900, false)));
        assertTrue(ledger.snapshot(31_999, BOOT).pending);
        AdtLiveLedger.Snapshot expired = ledger.snapshot(32_000, BOOT);
        assertEquals(AdtLiveLedger.Outcome.UNCONFIRMED, expired.outcome);
        assertFalse(expired.pending); assertTrue(expired.enabled);
        assertEquals(DISARMED, expired.state);
        assertEquals(AlarmStateProtocol.Availability.READY, expired.availability);
        assertEquals("-", expired.completedRequest);
        assertFalse(ledger.begin(expired.revision, AlarmAction.ARM_STAY, request, 32_001, BOOT));
        assertTrue(ledger.begin(expired.revision, AlarmAction.ARM_STAY, id(), 32_002, BOOT));
    }

    @Test public void responseAtDeadlineUpdatesActualStateButCannotClaimRequestCompleted() {
        AdtLiveLedger ledger = ready(DISARMED);
        begin(ledger, AlarmAction.ARM_STAY, 2_000);
        assertTrue(ledger.observe(sample(STAY, 31_900, 32_000, false)));
        AdtLiveLedger.Snapshot current = ledger.snapshot(32_000, BOOT);
        assertEquals(AdtLiveLedger.Outcome.UNCONFIRMED, current.outcome);
        assertEquals("-", current.completedRequest);
        assertTrue(current.enabled); assertEquals(AlarmAction.DISARM, current.action);
        assertTrue(ledger.observe(sample(STAY, 40_000, 40_100, false)));
        assertEquals(AdtLiveLedger.Outcome.UNCONFIRMED, ledger.snapshot(40_100, BOOT).outcome);
    }

    @Test public void timeoutDoesNotMakeStaleOrProviderBusyDataActionable() {
        AdtLiveLedger ledger = ready(DISARMED);
        begin(ledger, AlarmAction.ARM_STAY, 2_000);
        assertFalse(ledger.snapshot(61_000, BOOT).enabled);
        assertEquals(AdtLiveLedger.Outcome.UNCONFIRMED, ledger.snapshot(61_000, BOOT).outcome);
        assertTrue(ledger.observe(sample(DISARMED, 61_100, 61_200, true)));
        assertFalse(ledger.snapshot(61_200, BOOT).enabled);
        assertTrue(ledger.observe(sample(DISARMED, 62_000, 62_100, false)));
        assertTrue(ledger.snapshot(62_100, BOOT).enabled);
        assertEquals(AdtLiveLedger.Outcome.UNCONFIRMED, ledger.snapshot(62_100, BOOT).outcome);
    }

    @Test public void processRecreationPreservesPendingIdentityDeadlineAndCompletedRequest() {
        AdtLiveLedger original = ready(DISARMED);
        String request = begin(original, AlarmAction.ARM_STAY, 2_000);
        AdtLiveLedger restored = AdtLiveLedger.restore(SYSTEM, PARTITION, original.save());
        assertEquals(request, restored.snapshot(2_100, BOOT).requestId);
        assertTrue(restored.snapshot(31_999, BOOT).pending);
        assertFalse(restored.snapshot(32_000, BOOT).pending);
        assertEquals(AdtLiveLedger.Outcome.UNCONFIRMED, restored.snapshot(32_000, BOOT).outcome);
        assertTrue(restored.snapshot(32_000, BOOT).enabled);
        AdtLiveLedger beforeDeadline = AdtLiveLedger.restore(SYSTEM, PARTITION, original.save());
        assertTrue(beforeDeadline.observe(sample(STAY, 3_000, 3_100, false)));
        AdtLiveLedger completed = AdtLiveLedger.restore(SYSTEM, PARTITION, beforeDeadline.save());
        assertEquals(request, completed.snapshot(3_200, BOOT).completedRequest);
        assertEquals(AdtLiveLedger.Outcome.CONFIRMED, completed.snapshot(3_200, BOOT).outcome);
        assertTrue(completed.snapshot(3_200, BOOT).enabled);
    }

    @Test public void rebootInvalidatesOldCacheAndPendingButFreshNewBootQueryRecovers() {
        AdtLiveLedger ledger = ready(DISARMED);
        begin(ledger, AlarmAction.ARM_STAY, 2_000);
        AdtLiveLedger.Snapshot rebooted = ledger.snapshot(100, BOOT + 1);
        assertFalse(rebooted.fresh); assertFalse(rebooted.pending); assertFalse(rebooted.enabled);
        assertEquals(AdtLiveLedger.Outcome.UNCONFIRMED, rebooted.outcome);
        assertFalse(ledger.observe(sample(STAY, 2_100, 2_200, false)));
        assertTrue(ledger.observe(new AdtLiveLedger.Sample(STAY, SYSTEM, PARTITION, 200, 300, BOOT + 1, false)));
        assertTrue(ledger.snapshot(300, BOOT + 1).enabled);
        assertEquals("-", ledger.snapshot(300, BOOT + 1).completedRequest);
        assertFalse(ledger.observe(sample(DISARMED, 4_000, 4_100, false)));
        assertEquals(STAY, ledger.snapshot(300, BOOT + 1).state);
    }

    @Test public void rollbackFailsClosedUntilNewQueryBeyondPreviouslyObservedClock() {
        AdtLiveLedger ledger = ready(DISARMED);
        begin(ledger, AlarmAction.ARM_STAY, 2_000);
        assertFalse(ledger.snapshot(1_900, BOOT).enabled);
        assertEquals(AdtLiveLedger.Outcome.UNCONFIRMED, ledger.snapshot(1_900, BOOT).outcome);
        assertFalse(ledger.snapshot(2_100, BOOT).enabled);
        assertFalse(ledger.observe(sample(STAY, 1_999, 2_200, false)));
        assertTrue(ledger.observe(sample(STAY, 2_200, 2_300, false)));
        assertTrue(ledger.snapshot(2_300, BOOT).enabled);
        assertEquals("-", ledger.snapshot(2_300, BOOT).completedRequest);
    }

    @Test public void malformedOrDifferentlyBoundPersistenceCannotSupplyReadyState() {
        AdtLiveLedger ledger = ready(DISARMED);
        begin(ledger, AlarmAction.ARM_STAY, 2_000);
        assertFalse(AdtLiveLedger.restore("different-system", PARTITION, ledger.save()).snapshot(2_100, BOOT).enabled);
        for (String[] corruption : new String[][] {
                {"request", "invalid"}, {"clockFault", "perhaps"}, {"received", "999999"},
                {"requestBoot", "99"}, {"completedRequest", id()}, {"queryStarted", "-10"}}) {
            Map<String, String> saved = new HashMap<>(ledger.save());
            saved.put(corruption[0], corruption[1]);
            AdtLiveLedger restored = AdtLiveLedger.restore(SYSTEM, PARTITION, saved);
            assertFalse(corruption[0], restored.snapshot(2_100, BOOT).enabled);
            assertEquals(corruption[0], AdtLiveLedger.Outcome.NONE, restored.snapshot(2_100, BOOT).outcome);
        }
        AdtLiveLedger empty = new AdtLiveLedger(SYSTEM, PARTITION);
        assertFalse(AdtLiveLedger.restore(SYSTEM, PARTITION, empty.save()).snapshot(0, BOOT).enabled);
    }

    private static AdtLiveLedger ready(AlarmStateProtocol.State state) {
        AdtLiveLedger ledger = new AdtLiveLedger(SYSTEM, PARTITION);
        assertTrue(ledger.observe(sample(state, 1_000, 1_100, false)));
        return ledger;
    }
    private static AdtLiveLedger.Sample sample(AlarmStateProtocol.State state, long start, long received, boolean busy) {
        return new AdtLiveLedger.Sample(state, SYSTEM, PARTITION, start, received, BOOT, busy);
    }
    private static String begin(AdtLiveLedger ledger, AlarmAction action, long now) {
        String request = id();
        assertTrue(ledger.begin(ledger.snapshot(now, BOOT).revision, action, request, now, BOOT));
        return request;
    }
    private static String id() { return UUID.randomUUID().toString(); }
}
