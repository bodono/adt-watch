package dev.personal.adtprobe;

import java.nio.charset.StandardCharsets;

/** Small, closed messages for reported state and a state-bound user tap. */
public final class AlarmStateProtocol {
    public static final String QUERY_PATH = "/adt-probe/v3/state/query";
    public static final String STATE_PATH = "/adt-probe/v3/state/report";
    public static final String TOGGLE_PATH = "/adt-probe/v3/toggle/prepare";
    public static final long MAX_STATE_AGE_MS = 24 * 60 * 60 * 1000L;
    public static final long LINK_FRESH_MS = 60_000;
    public enum State { UNKNOWN, DISARMED, ARMED_STAY, ARMED_AWAY }
    public enum Availability { READY, NO_ACCESS, NO_STATE, STALE, BUSY, SETUP, OFFLINE }
    private AlarmStateProtocol() { }
    public static AlarmAction action(State state) {
        if (state == State.DISARMED) return AlarmAction.ARM_STAY;
        if (state == State.ARMED_STAY || state == State.ARMED_AWAY) return AlarmAction.DISARM;
        return null;
    }
    public static boolean uuid(String text) {
        return text != null && text.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
    private static String[] fields(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > 384) return null;
        for (byte b : bytes) if ((b & 255) != 10 && ((b & 255) < 32 || (b & 255) > 126)) return null;
        return new String(bytes, StandardCharsets.US_ASCII).split("\n", -1);
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
    public static byte[] query(String request) {
        if (!uuid(request)) throw new IllegalArgumentException("Invalid query");
        return bytes("ADT-STATE/1\nQUERY\n" + request);
    }
    public static String parseQuery(byte[] bytes) {
        String[] f = fields(bytes);
        return f != null && f.length == 3 && "ADT-STATE/1".equals(f[0]) && "QUERY".equals(f[1]) && uuid(f[2]) ? f[2] : null;
    }
    public static final class Report {
        public final String request, revision;
        public final State state;
        public final Availability availability;
        public final long ageMillis;
        public Report(String request, State state, Availability availability, String revision, long ageMillis) {
            if (!(uuid(request) || "-".equals(request)) || state == null || availability == null
                    || !(uuid(revision) || "-".equals(revision)) || ageMillis < 0
                    || availability == Availability.READY && (action(state) == null || !uuid(revision)
                        || ageMillis > MAX_STATE_AGE_MS)) throw new IllegalArgumentException("Invalid state report");
            this.request = request; this.state = state; this.availability = availability;
            this.revision = revision; this.ageMillis = ageMillis;
        }
        public byte[] encode() {
            return bytes("ADT-STATE/1\nSTATE\n" + request + "\n" + state + "\n" + availability + "\n" + revision + "\n" + ageMillis);
        }
    }
    public static Report parseReport(byte[] bytes) {
        String[] f = fields(bytes);
        if (f == null || f.length != 7 || !"ADT-STATE/1".equals(f[0]) || !"STATE".equals(f[1])
                || !f[6].matches("0|[1-9][0-9]{0,18}")) return null;
        try { return new Report(f[2], State.valueOf(f[3]), Availability.valueOf(f[4]), f[5], Long.parseLong(f[6])); }
        catch (IllegalArgumentException e) { return null; }
    }
    public static final class Tap {
        public final AlarmAction action;
        public final String request, revision;
        public Tap(AlarmAction action, String request, String revision) {
            if (action == null || !uuid(request) || !uuid(revision)) throw new IllegalArgumentException("Invalid tap");
            this.action = action; this.request = request; this.revision = revision;
        }
        public byte[] encode() { return bytes("ADT-TOGGLE/1\n" + action + "\n" + request + "\n" + revision); }
    }
    public static Tap parseTap(byte[] bytes) {
        String[] f = fields(bytes);
        if (f == null || f.length != 4 || !"ADT-TOGGLE/1".equals(f[0])) return null;
        try { return new Tap(AlarmAction.valueOf(f[1]), f[2], f[3]); }
        catch (IllegalArgumentException e) { return null; }
    }
}
