package dev.personal.adtprobe;

import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

/** Runs the plain-Java protocol checks in both Gradle application test suites. */
public final class ArmExperimentProtocolJUnitTest {
    private static final String REQUEST = "12345678-1234-4234-8234-123456789abc";
    private static final String OTHER = "12345678-1234-4234-8234-123456789abd";
    private static final String CHALLENGE = "abcdef12-1234-4234-8234-123456789abc";
    private static final String PHONE = "test-phone";

    @Test public void closedOneShotProtocol() { ArmExperimentProtocolTest.main(new String[0]); }

    @Test public void resultReasonsRoundTripAsClosedMetadataAndRequestedHasNone() {
        for (AlarmAction action : AlarmAction.values()) {
            for (AlarmStateProtocol.DeclineReason reason : AlarmStateProtocol.DeclineReason.values()) {
                byte[] encoded = ArmExperimentProtocol.encodeResult(action, REQUEST, CHALLENGE,
                    ArmExperimentProtocol.Outcome.REJECTED, reason);
                assertTrue(text(encoded).startsWith("ADT-ALARM/3\nRESULT\n"));
                ArmExperimentProtocol.Message parsed = ArmExperimentProtocol.parse(encoded);
                assertNotNull(parsed);
                assertEquals(action, parsed.action);
                assertEquals(REQUEST, parsed.requestId);
                assertEquals(CHALLENGE, parsed.challengeId);
                assertEquals(ArmExperimentProtocol.Outcome.REJECTED, parsed.outcome);
                assertEquals(reason, parsed.rejectionReason);
                assertTrue(encoded.length <= ArmExperimentProtocol.MAX_PAYLOAD_BYTES);
                assertNull(ArmExperimentProtocol.parse(encoded, ArmExperimentProtocol.Kind.COMMIT));
            }
            byte[] requested = ArmExperimentProtocol.encodeResult(action, REQUEST, CHALLENGE,
                ArmExperimentProtocol.Outcome.REQUESTED, null);
            assertTrue(text(requested).endsWith("\nREQUESTED\n-"));
            assertNull(ArmExperimentProtocol.parse(requested).rejectionReason);
            assertEquals(AlarmStateProtocol.DeclineReason.UNAVAILABLE, ArmExperimentProtocol.parse(
                ArmExperimentProtocol.encodeResult(action, REQUEST, CHALLENGE, ArmExperimentProtocol.Outcome.REJECTED))
                .rejectionReason);
        }
    }

    @Test public void malformedReasonsAndReasonsOnRequestedCannotBeAccepted() {
        String rejected = text(ArmExperimentProtocol.encodeResult(AlarmAction.ARM_STAY, REQUEST, CHALLENGE,
            ArmExperimentProtocol.Outcome.REJECTED, AlarmStateProtocol.DeclineReason.WIDGET_CHANGED));
        for (String invalid : new String[] {"", "-", "widget_changed", "WIDGET_CHANGED ", "UNRECOGNIZED",
                "WIDGET_CHANGED\n", "WIDGET_CHANGED\nPHONE_BUSY", "WIDGET_CHANGED\u0000"}) {
            assertNull(invalid, ArmExperimentProtocol.parse(ascii(rejected.replace("WIDGET_CHANGED", invalid))));
        }
        assertNull(ArmExperimentProtocol.parse(ascii(rejected.replace("REJECTED", "REQUESTED"))));
        assertNull(ArmExperimentProtocol.parse(ascii(rejected.replace("ADT-ALARM/3", "ADT-ALARM/2"))));
        for (byte[] executable : new byte[][] {
                ArmExperimentProtocol.encodePrepare(AlarmAction.ARM_STAY, REQUEST),
                ArmExperimentProtocol.encodeChallenge(AlarmAction.ARM_STAY, REQUEST, CHALLENGE),
                ArmExperimentProtocol.encodeCommit(AlarmAction.ARM_STAY, REQUEST, CHALLENGE)}) {
            assertTrue(text(executable).startsWith("ADT-ALARM/2\n"));
            assertNull("Only results use the extended format", ArmExperimentProtocol.parse(
                ascii(text(executable).replace("ADT-ALARM/2", "ADT-ALARM/3"))));
        }
        try {
            ArmExperimentProtocol.encodeResult(AlarmAction.ARM_STAY, REQUEST, CHALLENGE,
                ArmExperimentProtocol.Outcome.REQUESTED, AlarmStateProtocol.DeclineReason.WIDGET_CHANGED);
            fail("REQUESTED cannot carry a refusal reason");
        } catch (IllegalArgumentException expected) { }
        try {
            ArmExperimentProtocol.encodeResult(AlarmAction.ARM_STAY, REQUEST, CHALLENGE,
                ArmExperimentProtocol.Outcome.REJECTED, null);
            fail("REJECTED must carry a closed reason");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void oldResultMetadataDoesNotInventASpecificReason() {
        String prefix = "ADT-ALARM/2\nRESULT\nARM_STAY\n" + REQUEST + "\n" + CHALLENGE + "\n";
        assertNull(ArmExperimentProtocol.parse(ascii(prefix + "REQUESTED")).rejectionReason);
        assertEquals(AlarmStateProtocol.DeclineReason.UNAVAILABLE,
            ArmExperimentProtocol.parse(ascii(prefix + "REJECTED")).rejectionReason);
    }

    @Test public void refusalMetadataCannotCrossAnyExistingResultAdmissionGate() {
        byte[] valid = ArmExperimentProtocol.encodeResult(AlarmAction.ARM_STAY, REQUEST, CHALLENGE,
            ArmExperimentProtocol.Outcome.REJECTED, AlarmStateProtocol.DeclineReason.WIDGET_CHANGED);
        ArmExperimentProtocol.Attempt active = ready();
        assertNotNull(active.commit(104));
        assertFalse(active.acceptResult(valid, "other-phone", 105));
        assertNull(active.rejectionReason());
        assertFalse(active.acceptResult(ArmExperimentProtocol.encodeResult(AlarmAction.DISARM, REQUEST, CHALLENGE,
            ArmExperimentProtocol.Outcome.REJECTED, AlarmStateProtocol.DeclineReason.PHONE_UNLOCKED), PHONE, 105));
        assertNull(active.rejectionReason());
        for (byte[] mismatch : new byte[][] {
                ArmExperimentProtocol.encodeResult(AlarmAction.ARM_STAY, OTHER, CHALLENGE,
                    ArmExperimentProtocol.Outcome.REJECTED, AlarmStateProtocol.DeclineReason.PHONE_UNLOCKED),
                ArmExperimentProtocol.encodeResult(AlarmAction.ARM_STAY, REQUEST, OTHER,
                    ArmExperimentProtocol.Outcome.REJECTED, AlarmStateProtocol.DeclineReason.PHONE_UNLOCKED)}) {
            assertFalse(active.acceptResult(mismatch, PHONE, 105));
            assertNull(active.rejectionReason());
        }
        assertTrue(active.acceptResult(valid, PHONE, 106));
        assertEquals(AlarmStateProtocol.DeclineReason.WIDGET_CHANGED, active.rejectionReason());
        assertFalse(active.acceptResult(ArmExperimentProtocol.encodeResult(AlarmAction.ARM_STAY, REQUEST, CHALLENGE,
            ArmExperimentProtocol.Outcome.REJECTED, AlarmStateProtocol.DeclineReason.PHONE_UNLOCKED), PHONE, 107));
        assertEquals(AlarmStateProtocol.DeclineReason.WIDGET_CHANGED, active.rejectionReason());

        active = ready();
        assertNotNull(active.commit(104));
        assertFalse(active.acceptResult(valid, PHONE, 104 + ArmExperimentProtocol.RESULT_TIMEOUT_MS));
        assertNull(active.rejectionReason());
        assertEquals(ArmExperimentProtocol.Phase.EXPIRED, active.phase());
        active = ready(); active.cancel();
        assertFalse(active.acceptResult(valid, PHONE, 104));
        assertNull(active.rejectionReason());
    }

    @Test public void aMatchedPreflightRefusalPreservesItsReasonWithoutACommit() {
        ArmExperimentProtocol.Attempt active = new ArmExperimentProtocol.Attempt(AlarmAction.ARM_STAY, REQUEST, 100);
        assertTrue(active.selectTarget(PHONE, 101));
        assertNotNull(active.prepare(102));
        byte[] refusal = new AlarmStateProtocol.Declined(AlarmAction.ARM_STAY, REQUEST,
            AlarmStateProtocol.DeclineReason.SIGN_IN_REQUIRED).encode();
        assertFalse(active.acceptDeclined(refusal, "other-phone", 103));
        assertNull(active.rejectionReason());
        assertTrue(active.acceptDeclined(refusal, PHONE, 104));
        assertEquals(AlarmStateProtocol.DeclineReason.SIGN_IN_REQUIRED, active.rejectionReason());
        assertNull(active.commit(105));
    }

    private static ArmExperimentProtocol.Attempt ready() {
        ArmExperimentProtocol.Attempt active = new ArmExperimentProtocol.Attempt(AlarmAction.ARM_STAY, REQUEST, 100);
        assertTrue(active.selectTarget(PHONE, 101));
        assertNotNull(active.prepare(102));
        assertTrue(active.acceptChallenge(ArmExperimentProtocol.encodeChallenge(AlarmAction.ARM_STAY, REQUEST, CHALLENGE),
            PHONE, 103));
        return active;
    }
    private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
    private static String text(byte[] value) { return new String(value, StandardCharsets.US_ASCII); }
}
