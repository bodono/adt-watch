package dev.personal.adtprobe;

import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public final class AlarmStateProtocolTest {
    private static final String REQUEST = "00000000-0000-0000-0000-000000000001";
    private static final String REVISION = "00000000-0000-0000-0000-000000000002";
    @Test public void queryAndTapKeepTheirIndependentIdentities() {
        assertEquals(REQUEST, AlarmStateProtocol.parseQuery(AlarmStateProtocol.query(REQUEST)));
        AlarmStateProtocol.Tap tap = AlarmStateProtocol.parseTap(
            new AlarmStateProtocol.Tap(AlarmAction.DISARM, REQUEST, REVISION).encode());
        assertEquals(AlarmAction.DISARM, tap.action);
        assertEquals(REQUEST, tap.request); assertEquals(REVISION, tap.revision);
        assertNull(AlarmStateProtocol.parseTap(ArmExperimentProtocol.encodePrepare(AlarmAction.DISARM, REQUEST)));
    }
    @Test public void onlyRecognizedReportedStatesProduceAnAction() {
        assertNull(AlarmStateProtocol.action(AlarmStateProtocol.State.UNKNOWN));
        assertEquals(AlarmAction.ARM_STAY, AlarmStateProtocol.action(AlarmStateProtocol.State.DISARMED));
        assertEquals(AlarmAction.DISARM, AlarmStateProtocol.action(AlarmStateProtocol.State.ARMED_STAY));
        assertEquals(AlarmAction.DISARM, AlarmStateProtocol.action(AlarmStateProtocol.State.ARMED_AWAY));
    }
    @Test public void unknownOldOrMissingRevisionCannotBeReady() {
        for (String bad : new String[] {
            "UNKNOWN\nREADY\n" + REVISION + "\n0",
            "DISARMED\nREADY\n-\n0",
            "DISARMED\nREADY\n" + REVISION + "\n86400001",
            "DISARMED\nREADY\n" + REVISION + "\n-1",
            "DISARMED\nREADY\n" + REVISION + "\n9999999999999999999"})
            assertNull(AlarmStateProtocol.parseReport(ascii("ADT-STATE/1\nSTATE\n" + REQUEST + "\n" + bad)));
    }
    @Test public void oversizedNonCanonicalAndInjectedFieldsAreRejected() {
        byte[] valid = new AlarmStateProtocol.Report(REQUEST, AlarmStateProtocol.State.DISARMED,
            AlarmStateProtocol.Availability.READY, REVISION, 0).encode();
        assertNotNull(AlarmStateProtocol.parseReport(valid));
        assertNull(AlarmStateProtocol.parseReport(ascii(new String(valid, StandardCharsets.US_ASCII) + "\n")));
        assertNull(AlarmStateProtocol.parseReport(new byte[385]));
        valid[0] = 0; assertNull(AlarmStateProtocol.parseReport(valid));
        assertNull(AlarmStateProtocol.parseQuery(ascii("ADT-STATE/1\nQUERY\n" + REQUEST + "\nDISARM")));
    }
    private static byte[] ascii(String text) { return text.getBytes(StandardCharsets.US_ASCII); }
}
