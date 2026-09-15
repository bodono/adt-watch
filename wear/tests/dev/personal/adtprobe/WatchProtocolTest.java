package dev.personal.adtprobe;

import java.nio.charset.StandardCharsets;

/** JVM-only tests for malformed, expired, duplicated and misrouted connection messages. */
public final class WatchProtocolTest {
    private static final String ID = "11111111-1111-4111-8111-111111111111";
    private static final String OTHER_ID = "22222222-2222-4222-8222-222222222222";
    private static final long WALL = 1_800_000_000_000L;
    private static final long ELAPSED = 123_000L;
    private static int assertions;

    private static void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.US_ASCII); }

    private static void rejects(byte[] payload, String expectedKind, String label) {
        boolean rejected = false;
        try { WatchProtocol.parse(payload, expectedKind); }
        catch (IllegalArgumentException error) { rejected = true; }
        check(rejected, label);
    }

    private static WatchProtocol.Attempt attempt() {
        return new WatchProtocol.Attempt(ID, WALL, ELAPSED);
    }

    private static byte[] ack(WatchProtocol.Attempt value) {
        return WatchProtocol.acknowledgement(WatchProtocol.parse(value.ping(), "PING"));
    }

    private static void acceptsSkewedClock(long watchOffsetMillis) {
        WatchProtocol.Attempt request = new WatchProtocol.Attempt(ID, WALL + watchOffsetMillis, ELAPSED);
        check(request.selectTarget("phone-a", ELAPSED), "clock-skew target selected");
        // Mirror the receiver's schema/source/replay checks: its wall clock is not an input.
        WatchProtocol.Message received = WatchProtocol.parse(request.ping(), "PING");
        check(new WatchProtocol.ReplayGuard().admit("watch-a", received, ELAPSED + 500),
            "phone accepts harmless ping independently of device clock skew");
        byte[] reply = WatchProtocol.acknowledgement(received);
        check(WatchProtocol.parse(reply, "ACK").sentAtMillis == WALL + watchOffsetMillis,
            "phone echoes original timestamp unchanged");
        check(request.acceptAcknowledgement(reply, "phone-a", WALL, ELAPSED + 1_000),
            "current reply accepted despite clock skew or wall-clock adjustment");
        check(!request.acceptAcknowledgement(reply, "phone-a", WALL, ELAPSED + 1_001),
            "clock-skew reply still consumed only once");
    }

    public static void main(String[] args) {
        WatchProtocol.Attempt request = attempt();
        byte[] pingBytes = request.ping();
        WatchProtocol.Message ping = WatchProtocol.parse(pingBytes, "PING");
        check("PING".equals(ping.kind), "ping kind");
        check(ID.equals(ping.requestId), "request id retained");
        check(ping.sentAtMillis == WALL, "original timestamp retained");
        byte[] reply = WatchProtocol.acknowledgement(ping);
        check("ACK".equals(WatchProtocol.parse(reply, "ACK").kind), "ack kind");

        rejects(null, "PING", "null payload");
        rejects(new byte[0], "PING", "empty payload");
        rejects(new byte[WatchProtocol.MAX_PAYLOAD_BYTES + 1], "PING", "oversized payload");
        rejects(new byte[]{(byte) 0xff}, "PING", "non ASCII");
        rejects(bytes("ADT-PROBE/1\r\nPING\n" + ID + "\n" + WALL), "PING", "CRLF rejected");
        rejects(bytes("ADT-PROBE/1\nPING\n" + ID + "\n" + WALL + "\n"), "PING", "extra field rejected");
        rejects(bytes("ADT-PROBE/1\nPING\n" + ID + "\n" + WALL + "\nDISARM"), "PING", "command injection rejected");
        rejects(bytes("ADT-PROBE/2\nPING\n" + ID + "\n" + WALL), "PING", "unknown protocol rejected");
        rejects(bytes("ADT-PROBE/1\nDISARM\n" + ID + "\n" + WALL), "PING", "no alarm message kind");
        rejects(pingBytes, "DISARM", "invalid expected message kind");
        rejects(reply, "PING", "ack cannot act as ping");
        rejects(pingBytes, "ACK", "ping cannot act as ack");
        rejects(bytes("ADT-PROBE/1\nPING\nINVALID\n" + WALL), "PING", "invalid UUID");
        rejects(bytes("ADT-PROBE/1\nPING\n" + ID + "\n9223372036854775808"), "PING", "overflow timestamp");
        rejects(bytes("ADT-PROBE/1\nPING\n" + ID + "\n-1"), "PING", "negative timestamp");
        rejects(bytes("ADT-PROBE/1\nPING\n" + ID + "\n01"), "PING", "noncanonical timestamp");

        acceptsSkewedClock(-39_000L); // Reproduces the watch's observed offset from the phone.
        acceptsSkewedClock(39_000L);
        acceptsSkewedClock(-86_400_000L);
        acceptsSkewedClock(86_400_000L);

        check(!request.acceptAcknowledgement(reply, "phone-a", WALL, ELAPSED), "no reply before target selected");
        check(request.selectTarget("phone-a", ELAPSED + 1), "one target selected");
        check(!request.selectTarget("phone-b", ELAPSED + 2), "target cannot switch");
        check(!request.acceptAcknowledgement(reply, "phone-b", WALL + 10, ELAPSED + 10), "wrong source rejected");
        WatchProtocol.Attempt other = new WatchProtocol.Attempt(OTHER_ID, WALL, ELAPSED);
        check(!request.acceptAcknowledgement(ack(other), "phone-a", WALL + 10, ELAPSED + 10), "wrong request rejected");
        WatchProtocol.Attempt otherTime = new WatchProtocol.Attempt(ID, WALL + 1, ELAPSED);
        check(!request.acceptAcknowledgement(ack(otherTime), "phone-a", WALL + 10, ELAPSED + 10), "timestamp mismatch rejected");
        check(request.acceptAcknowledgement(reply, "phone-a", WALL + 20, ELAPSED + 20), "current matching ack accepted");
        check(!request.acceptAcknowledgement(reply, "phone-a", WALL + 21, ELAPSED + 21), "duplicate ack rejected");
        check(!request.isActive(ELAPSED + 22), "accepted attempt closes");

        WatchProtocol.Attempt expired = attempt();
        check(expired.selectTarget("phone-a", ELAPSED), "expiry test target");
        check(!expired.acceptAcknowledgement(reply, "phone-a", WALL - 86_400_000L, ELAPSED + WatchProtocol.TIMEOUT_MS),
            "monotonic expiry independent of wall clock");
        check(!expired.acceptAcknowledgement(reply, "phone-a", WALL + 86_400_000L, ELAPSED + WatchProtocol.TIMEOUT_MS + 1),
            "wall-clock changes cannot revive an expired request");
        check(!expired.isActive(ELAPSED - 1), "elapsed clock rollback fails closed");
        WatchProtocol.Attempt justInTime = attempt();
        justInTime.selectTarget("phone-a", ELAPSED);
        check(justInTime.acceptAcknowledgement(reply, "phone-a", WALL + 86_400_000L,
                ELAPSED + WatchProtocol.TIMEOUT_MS - 1), "reply immediately before local deadline accepted");
        WatchProtocol.Attempt cancelled = attempt();
        cancelled.selectTarget("phone-a", ELAPSED);
        cancelled.cancel();
        check(!cancelled.acceptAcknowledgement(reply, "phone-a", WALL + 1, ELAPSED + 1), "cancelled attempt cannot accept late reply");
        check(!cancelled.selectTarget("phone-a", ELAPSED + 1), "cancelled attempt cannot restart");

        WatchProtocol.ReplayGuard guard = new WatchProtocol.ReplayGuard();
        check(guard.admit("phone-a", ping, ELAPSED), "initial ping admitted");
        check(!guard.admit("phone-a", ping, ELAPSED + 1), "duplicate ping suppressed");
        check(guard.admit("phone-b", ping, ELAPSED + 1), "source scopes ping id");
        check(!guard.admit("phone-a", WatchProtocol.parse(reply, "ACK"), ELAPSED + 1), "guard rejects ack kind");
        check(!guard.admit("", ping, ELAPSED + 1), "empty source rejected");
        check(!WatchProtocol.validNodeId("one\ntwo"), "newline source rejected");
        check(guard.admit("phone-a", ping, ELAPSED + WatchProtocol.TIMEOUT_MS), "old dedupe record expires");
        check(!expired.acceptAcknowledgement(WatchProtocol.acknowledgement(ping), "phone-a", WALL,
            ELAPSED + WatchProtocol.TIMEOUT_MS), "late inert echo still rejected by the watch deadline");
        WatchProtocol.ReplayGuard full = new WatchProtocol.ReplayGuard();
        for (int n = 0; n < 32; n++)
            check(full.admit("extra-" + n, ping, ELAPSED), "bounded cache insertion");
        check(!full.admit("over-capacity", ping, ELAPSED), "full cache fails closed");

        System.out.println("WatchProtocol: " + assertions + " assertions passed");
    }
}
