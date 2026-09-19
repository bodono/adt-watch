package dev.personal.adtprobe;

import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public final class AlarmStateProtocolTest {
    private static final String REQUEST = "00000000-0000-0000-0000-000000000001";
    private static final String REVISION = "00000000-0000-0000-0000-000000000002";
    private static final String COMPLETED = "abcdef12-1234-4234-8234-123456789abc";
    private static final String OBSERVATION = "abcdef12-1234-4234-8234-123456789def";

    @Test public void v3SeparatesFreshObservationFromStableStateAndCommandIdentities() {
        for (long age : new long[] {0, 59_999, 60_000}) {
            AlarmStateProtocol.Report original = new AlarmStateProtocol.Report(REQUEST, AlarmStateProtocol.State.DISARMED,
                AlarmStateProtocol.Availability.READY, REVISION, age, COMPLETED, AlarmStateProtocol.Evidence.ADT_QUERY, OBSERVATION);
            byte[] bytes = original.encode();
            assertTrue(new String(bytes, StandardCharsets.US_ASCII).startsWith("ADT-STATE/3\n"));
            assertEquals(10, new String(bytes, StandardCharsets.US_ASCII).split("\n", -1).length);
            AlarmStateProtocol.Report parsed = AlarmStateProtocol.parseReport(bytes);
            assertNotNull(parsed); assertEquals(OBSERVATION, parsed.observationId);
            assertEquals(REVISION, parsed.revision); assertEquals(COMPLETED, parsed.completedRequest);
            assertEquals(REQUEST, parsed.request); assertEquals(age, parsed.ageMillis);
            assertEquals(AlarmStateProtocol.Evidence.ADT_QUERY, parsed.evidence);
            assertArrayEquals(bytes, parsed.encode());
            assertNull(AlarmStateProtocol.parseTap(bytes));
        }
    }

    @Test public void queryAuthorityRequiresANewSchemaValidObservationAndShortFreshness() {
        String base = "ADT-STATE/3\nSTATE\n" + REQUEST + "\nDISARMED\nREADY\n" + REVISION + "\n0\n-\nADT_QUERY\n";
        for (String bad : new String[] {"-", "", OBSERVATION.toUpperCase(), "not-an-observation", OBSERVATION + "\n"})
            assertNull(AlarmStateProtocol.parseReport(ascii(base + bad)));
        assertNull(AlarmStateProtocol.parseReport(ascii((base + OBSERVATION).replace("\n0\n-", "\n60001\n-"))));
        assertNull(AlarmStateProtocol.parseReport(ascii(base.replace("ADT-STATE/3", "ADT-STATE/2") + OBSERVATION)));
        assertNull(AlarmStateProtocol.parseReport(ascii(base.substring(0, base.length() - 1).replace("ADT-STATE/3", "ADT-STATE/2"))));
        assertNull(AlarmStateProtocol.parseReport(ascii((base + OBSERVATION).replace("ADT_QUERY", "PHONE_CHECK"))));
        try {
            new AlarmStateProtocol.Report(REQUEST, AlarmStateProtocol.State.DISARMED, AlarmStateProtocol.Availability.READY,
                REVISION, 0, "-", AlarmStateProtocol.Evidence.ADT_QUERY);
            fail("Query authority requires a separate observation identity");
        } catch (IllegalArgumentException expected) { }
        AlarmStateProtocol.Report unavailable = new AlarmStateProtocol.Report(REQUEST, AlarmStateProtocol.State.UNKNOWN,
            AlarmStateProtocol.Availability.OFFLINE, "-", 0, "-", AlarmStateProtocol.Evidence.ADT_QUERY, "-");
        assertNotNull(AlarmStateProtocol.parseReport(unavailable.encode()));
    }
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

    @Test public void v2ReportPreservesCompletedCommandSeparatelyFromQueryAndStateRevision() {
        byte[] encoded = new AlarmStateProtocol.Report(REQUEST, AlarmStateProtocol.State.DISARMED,
            AlarmStateProtocol.Availability.READY, REVISION, 1_234, COMPLETED).encode();
        assertTrue(new String(encoded, StandardCharsets.US_ASCII).startsWith("ADT-STATE/2\nSTATE\n"));
        assertEquals(9, new String(encoded, StandardCharsets.US_ASCII).split("\n", -1).length);
        AlarmStateProtocol.Report parsed = AlarmStateProtocol.parseReport(encoded);
        assertNotNull(parsed);
        assertEquals(REQUEST, parsed.request);
        assertEquals(REVISION, parsed.revision);
        assertEquals(COMPLETED, parsed.completedRequest);
        assertEquals(1_234, parsed.ageMillis);
        assertEquals(AlarmStateProtocol.State.DISARMED, parsed.state);
        assertEquals(AlarmStateProtocol.Availability.READY, parsed.availability);
        assertEquals(AlarmStateProtocol.Evidence.ADT_NOTIFICATION, parsed.evidence);
        assertArrayEquals(encoded, parsed.encode());
        assertNull("Completion metadata cannot itself be an executable tap", AlarmStateProtocol.parseTap(encoded));
        assertNull(AlarmStateProtocol.parseDeclined(encoded));
    }

    @Test public void legacyReportsHaveNoInventedCompletionAndCanBeReencodedAsV2() {
        AlarmStateProtocol.Report legacy = AlarmStateProtocol.parseReport(ascii(
            "ADT-STATE/1\nSTATE\n" + REQUEST + "\nARMED_STAY\nREADY\n" + REVISION + "\n500"));
        assertNotNull(legacy);
        assertEquals("-", legacy.completedRequest);
        assertEquals(AlarmStateProtocol.Evidence.ADT_NOTIFICATION, legacy.evidence);
        assertEquals(AlarmStateProtocol.State.ARMED_STAY, legacy.state);
        assertEquals(500, legacy.ageMillis);
        AlarmStateProtocol.Report upgraded = AlarmStateProtocol.parseReport(legacy.encode());
        assertNotNull(upgraded);
        assertEquals("-", upgraded.completedRequest);
        assertEquals(AlarmStateProtocol.Evidence.ADT_NOTIFICATION, upgraded.evidence);
        assertEquals(legacy.request, upgraded.request);
        assertEquals(legacy.revision, upgraded.revision);
        assertEquals("-", AlarmStateProtocol.parseReport(new AlarmStateProtocol.Report("-",
            AlarmStateProtocol.State.UNKNOWN, AlarmStateProtocol.Availability.UNCONFIRMED, "-", 0).encode()).completedRequest);
    }

    @Test public void reportMetadataRequiresTheExactSchemaAndACanonicalCompletionIdentity() {
        String base = "STATE\n" + REQUEST + "\nDISARMED\nREADY\n" + REVISION + "\n0";
        for (String bad : new String[] {"", "not-a-uuid", "1-1-1-1-1", COMPLETED.toUpperCase(),
                COMPLETED + " ", COMPLETED + "\n" + REQUEST}) {
            assertNull(bad, AlarmStateProtocol.parseReport(ascii("ADT-STATE/2\n" + base + "\n" + bad + "\nADT_NOTIFICATION")));
        }
        assertNull(AlarmStateProtocol.parseReport(ascii("ADT-STATE/2\n" + base)));
        assertNull("V2 requires explicit evidence", AlarmStateProtocol.parseReport(ascii("ADT-STATE/2\n" + base + "\n" + COMPLETED)));
        assertNull(AlarmStateProtocol.parseReport(ascii("ADT-STATE/1\n" + base + "\n" + COMPLETED + "\nADT_NOTIFICATION")));
        assertNull(AlarmStateProtocol.parseReport(ascii("ADT-STATE/3\n" + base + "\n" + COMPLETED + "\nADT_NOTIFICATION")));
        assertNull("A completion UUID cannot make an unknown state READY", AlarmStateProtocol.parseReport(ascii(
            "ADT-STATE/2\n" + base.replace("DISARMED", "UNKNOWN") + "\n" + COMPLETED + "\nADT_NOTIFICATION")));
        for (String bad : new String[] {null, "", COMPLETED.toUpperCase(), "-\n" + COMPLETED}) {
            try {
                new AlarmStateProtocol.Report(REQUEST, AlarmStateProtocol.State.DISARMED,
                    AlarmStateProtocol.Availability.READY, REVISION, 0, bad);
                fail("Encoder accepted invalid completed-request metadata");
            } catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void phoneCheckEvidenceIsPreservedAndCannotUseTheNotificationAgeLimit() {
        for (long age : new long[] {0, 299_999, 300_000}) {
            AlarmStateProtocol.Report parsed = AlarmStateProtocol.parseReport(new AlarmStateProtocol.Report(
                REQUEST, AlarmStateProtocol.State.DISARMED, AlarmStateProtocol.Availability.READY,
                REVISION, age, COMPLETED, AlarmStateProtocol.Evidence.PHONE_CHECK).encode());
            assertNotNull(parsed);
            assertEquals(AlarmStateProtocol.Evidence.PHONE_CHECK, parsed.evidence);
            assertEquals(age, parsed.ageMillis);
            assertEquals(COMPLETED, parsed.completedRequest);
        }
        for (long age : new long[] {300_001, AlarmStateProtocol.MAX_STATE_AGE_MS}) {
            String fields = "\nSTATE\n" + REQUEST + "\nDISARMED\nREADY\n" + REVISION + "\n" + age;
            assertNull(AlarmStateProtocol.parseReport(ascii("ADT-STATE/2" + fields + "\n" + COMPLETED + "\nPHONE_CHECK")));
            assertNotNull("Real ADT notification reports retain their separate age limit",
                AlarmStateProtocol.parseReport(ascii("ADT-STATE/2" + fields + "\n" + COMPLETED + "\nADT_NOTIFICATION")));
            AlarmStateProtocol.Report legacy = AlarmStateProtocol.parseReport(ascii("ADT-STATE/1" + fields));
            assertNotNull(legacy);
            assertEquals(AlarmStateProtocol.Evidence.ADT_NOTIFICATION, legacy.evidence);
            try {
                new AlarmStateProtocol.Report(REQUEST, AlarmStateProtocol.State.DISARMED,
                    AlarmStateProtocol.Availability.READY, REVISION, age, COMPLETED, AlarmStateProtocol.Evidence.PHONE_CHECK);
                fail("Encoder accepted an expired phone check as READY");
            } catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void evidenceCannotBeOmittedUnknownOrSilentlyChangedToAnAdtReport() {
        String base = "ADT-STATE/2\nSTATE\n" + REQUEST + "\nDISARMED\nREADY\n" + REVISION + "\n0\n" + COMPLETED;
        for (String evidence : new String[] {"", "-", "PHONE", "phone_check", "ADT_NOTIFICATION ", "PHONE_CHECK\nADT_NOTIFICATION"})
            assertNull(evidence, AlarmStateProtocol.parseReport(ascii(base + "\n" + evidence)));
        try {
            new AlarmStateProtocol.Report(REQUEST, AlarmStateProtocol.State.DISARMED,
                AlarmStateProtocol.Availability.READY, REVISION, 0, COMPLETED, null);
            fail("Encoder accepted absent evidence");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void declinedMessagesPreserveOnlyTheirExactActionRequestAndReason() {
        for (AlarmAction action : AlarmAction.values()) {
            for (AlarmStateProtocol.DeclineReason reason : AlarmStateProtocol.DeclineReason.values()) {
                byte[] encoded = new AlarmStateProtocol.Declined(action, REQUEST, reason).encode();
                AlarmStateProtocol.Declined parsed = AlarmStateProtocol.parseDeclined(encoded);
                assertNotNull(parsed);
                assertEquals(action, parsed.action);
                assertEquals(REQUEST, parsed.request);
                assertEquals(reason, parsed.reason);
                assertArrayEquals(encoded, parsed.encode());
                assertNull(AlarmStateProtocol.parseTap(encoded));
                assertNull(AlarmStateProtocol.parseReport(encoded));
                assertNull(ArmExperimentProtocol.parse(encoded));
            }
        }
    }

    @Test public void malformedDeclinesCannotBeInterpretedAsARefusal() {
        String valid = "ADT-DECLINED/1\nDISARM\n" + REQUEST + "\nSTATE_CHANGED";
        for (String bad : new String[] {valid + "\n", valid + "\n" + REVISION,
                valid.replace("ADT-DECLINED/1", "ADT-DECLINED/2"),
                valid.replace("DISARM", "ARM_AWAY"), valid.replace("DISARM", "disarm"),
                valid.replace("STATE_CHANGED", "ARMED"), valid.replace("STATE_CHANGED", ""),
                valid.replace(REQUEST, "-"), valid.replace(REQUEST, COMPLETED.toUpperCase()),
                valid.replace("\n", "\r\n"), valid.replace("STATE_CHANGED", "STATE_CHANGED\u0000")}) {
            assertNull(bad, AlarmStateProtocol.parseDeclined(ascii(bad)));
        }
        assertNull(AlarmStateProtocol.parseDeclined(null));
        assertNull(AlarmStateProtocol.parseDeclined(new byte[0]));
        assertNull(AlarmStateProtocol.parseDeclined(new byte[385]));
        byte[] nonAscii = ascii(valid); nonAscii[0] = (byte) 0x80;
        assertNull(AlarmStateProtocol.parseDeclined(nonAscii));
        assertNull(AlarmStateProtocol.parseDeclined(new AlarmStateProtocol.Tap(
            AlarmAction.DISARM, REQUEST, REVISION).encode()));
    }
    private static byte[] ascii(String text) { return text.getBytes(StandardCharsets.US_ASCII); }
}
