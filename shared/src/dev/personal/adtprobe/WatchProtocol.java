package dev.personal.adtprobe;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** A deliberately closed, harmless ping protocol. It has no alarm command type. */
public final class WatchProtocol {
    public static final String PHONE_CAPABILITY = "adt_probe_phone_v1";
    public static final String PING_PATH = "/adt-probe/v1/ping";
    public static final String ACK_PATH = "/adt-probe/v1/ack";
    public static final int MAX_PAYLOAD_BYTES = 128;
    public static final long TIMEOUT_MS = 10_000L;
    private static final String HEADER = "ADT-PROBE/1";

    private WatchProtocol() { }

    public static final class Message {
        public final String kind;
        public final String requestId;
        public final long sentAtMillis;

        private Message(String kind, String requestId, long sentAtMillis) {
            this.kind = kind;
            this.requestId = requestId;
            this.sentAtMillis = sentAtMillis;
        }
    }

    /** Rejects extra fields, non-ASCII input, unknown message kinds and oversized payloads. */
    public static Message parse(byte[] bytes, String expectedKind) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_PAYLOAD_BYTES)
            throw new IllegalArgumentException("Invalid message size");
        for (byte value : bytes) {
            int unsigned = value & 0xff;
            if (unsigned != '\n' && (unsigned < 32 || unsigned > 126))
                throw new IllegalArgumentException("Invalid message encoding");
        }
        String[] fields = new String(bytes, StandardCharsets.US_ASCII).split("\n", -1);
        if (fields.length != 4 || !HEADER.equals(fields[0])
                || !("PING".equals(expectedKind) || "ACK".equals(expectedKind))
                || !expectedKind.equals(fields[1]) || !validRequestId(fields[2])
                || !fields[3].matches("[1-9][0-9]{0,18}"))
            throw new IllegalArgumentException("Invalid message schema");
        long timestamp;
        try { timestamp = Long.parseLong(fields[3]); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("Invalid timestamp"); }
        return new Message(fields[1], fields[2], timestamp);
    }

    private static boolean validRequestId(String value) {
        return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    public static boolean validNodeId(String value) {
        return value != null && !value.isEmpty() && value.length() <= 256
            && value.indexOf('\n') < 0 && value.indexOf('\r') < 0;
    }

    private static byte[] encode(String kind, String requestId, long sentAtMillis) {
        if (!validRequestId(requestId) || sentAtMillis <= 0)
            throw new IllegalArgumentException("Invalid request");
        return (HEADER + "\n" + kind + "\n" + requestId + "\n" + sentAtMillis)
            .getBytes(StandardCharsets.US_ASCII);
    }

    public static byte[] acknowledgement(Message ping) {
        if (ping == null || !"PING".equals(ping.kind))
            throw new IllegalArgumentException("Expected ping");
        return encode("ACK", ping.requestId, ping.sentAtMillis);
    }

    /**
     * Monotonic lifetime on the watch; one chosen node and one accepted reply at most.
     * The wire timestamp is an exact-match echo field, not a shared clock or expiry source.
     */
    public static final class Attempt {
        private final String requestId;
        private final long sentAtMillis;
        private final long startedElapsedMillis;
        private String targetNodeId;
        private boolean finished;

        public Attempt(long nowMillis, long elapsedMillis) {
            this(UUID.randomUUID().toString(), nowMillis, elapsedMillis);
        }

        Attempt(String id, long nowMillis, long elapsedMillis) {
            if (!validRequestId(id) || nowMillis <= 0 || elapsedMillis < 0)
                throw new IllegalArgumentException("Invalid attempt");
            requestId = id;
            sentAtMillis = nowMillis;
            startedElapsedMillis = elapsedMillis;
        }

        public synchronized boolean isActive(long nowElapsedMillis) {
            return !finished && nowElapsedMillis >= startedElapsedMillis
                && nowElapsedMillis - startedElapsedMillis < TIMEOUT_MS;
        }

        public synchronized boolean selectTarget(String nodeId, long nowElapsedMillis) {
            if (!validNodeId(nodeId) || targetNodeId != null || !isActive(nowElapsedMillis)) return false;
            targetNodeId = nodeId;
            return true;
        }

        public byte[] ping() { return encode("PING", requestId, sentAtMillis); }

        public synchronized boolean acceptAcknowledgement(byte[] payload, String sourceNodeId,
                long ignoredWallClockMillis, long nowElapsedMillis) {
            // Keep the caller signature stable, but deliberately ignore wall time: device clocks
            // can differ or change during a request. Only this watch's monotonic deadline expires it.
            if (!isActive(nowElapsedMillis) || targetNodeId == null
                    || !targetNodeId.equals(sourceNodeId)) return false;
            final Message reply;
            try { reply = parse(payload, "ACK"); }
            catch (IllegalArgumentException error) { return false; }
            if (!requestId.equals(reply.requestId)
                    || sentAtMillis != reply.sentAtMillis) return false;
            finished = true;
            return true;
        }

        public synchronized void cancel() { finished = true; }
    }

    /**
     * Bounded, memory-only suppression of repeated harmless pings during this process.
     * A late ping can receive an inert echo after this cache expires; the watch still rejects
     * replies outside its current attempt's monotonic deadline. This is not an alarm protocol.
     */
    public static final class ReplayGuard {
        private static final int CAPACITY = 32;
        private final LinkedHashMap<String, Long> received = new LinkedHashMap<>();

        public synchronized boolean admit(String nodeId, Message message, long nowElapsedMillis) {
            if (!validNodeId(nodeId) || message == null || !"PING".equals(message.kind)
                    || nowElapsedMillis < 0) return false;
            Iterator<Map.Entry<String, Long>> entries = received.entrySet().iterator();
            while (entries.hasNext()) {
                long seenAt = entries.next().getValue();
                if (nowElapsedMillis < seenAt || nowElapsedMillis - seenAt >= TIMEOUT_MS) entries.remove();
            }
            String key = nodeId + "\n" + message.requestId;
            if (received.containsKey(key)) return false;
            if (received.size() >= CAPACITY) return false;
            received.put(key, nowElapsedMillis);
            return true;
        }
    }
}
