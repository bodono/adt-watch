package dev.personal.adtprobe;

import java.nio.charset.StandardCharsets;

/** Small, closed messages for reported state and a state-bound user tap. */
public final class AlarmStateProtocol {
    public static final String QUERY_PATH = "/adt-probe/v3/state/query";
    public static final String STATE_PATH = "/adt-probe/v3/state/report";
    public static final String TOGGLE_PATH = "/adt-probe/v3/toggle/prepare";
    public static final String DECLINED_PATH = "/adt-probe/v3/toggle/declined";
    public static final long MAX_STATE_AGE_MS = 24 * 60 * 60 * 1000L;
    public static final long LINK_FRESH_MS = 60_000;
    public static final long PHONE_CHECK_FRESH_MS = 5 * 60_000;
    public static final long QUERY_FRESH_MS = 60_000;
    public enum State { UNKNOWN, DISARMED, ARMED_STAY, ARMED_AWAY }
    public enum Availability { READY, NO_ACCESS, NO_STATE, STALE, BUSY, SETUP, OFFLINE }
    public enum DeclineReason { STATE_CHANGED, UNAVAILABLE, ALREADY_SATISFIED }
    public enum Evidence { ADT_NOTIFICATION, PHONE_CHECK, ADT_QUERY }
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
        public final String request, revision, completedRequest, observationId;
        public final State state;
        public final Availability availability;
        public final long ageMillis;
        public final Evidence evidence;
        public Report(String request, State state, Availability availability, String revision, long ageMillis) {
            this(request, state, availability, revision, ageMillis, "-");
        }
        public Report(String request, State state, Availability availability, String revision, long ageMillis,
                String completedRequest) {
            this(request, state, availability, revision, ageMillis, completedRequest, Evidence.ADT_NOTIFICATION);
        }
        public Report(String request, State state, Availability availability, String revision, long ageMillis,
                String completedRequest, Evidence evidence) {
            this(request, state, availability, revision, ageMillis, completedRequest, evidence, "-");
        }
        public Report(String request, State state, Availability availability, String revision, long ageMillis,
                String completedRequest, Evidence evidence, String observationId) {
            if (!(uuid(request) || "-".equals(request)) || state == null || availability == null
                    || !(uuid(revision) || "-".equals(revision)) || ageMillis < 0
                    || !(uuid(completedRequest) || "-".equals(completedRequest)) || evidence == null
                    || !(uuid(observationId) || "-".equals(observationId))
                    || evidence != Evidence.ADT_QUERY && !"-".equals(observationId)
                    || availability == Availability.READY && (action(state) == null || !uuid(revision)
                        || evidence == Evidence.ADT_QUERY && !uuid(observationId)
                        || ageMillis > (evidence == Evidence.ADT_QUERY ? QUERY_FRESH_MS
                            : evidence == Evidence.PHONE_CHECK ? PHONE_CHECK_FRESH_MS : MAX_STATE_AGE_MS)))
                throw new IllegalArgumentException("Invalid state report");
            this.request = request; this.state = state; this.availability = availability;
            this.revision = revision; this.ageMillis = ageMillis;
            this.completedRequest = completedRequest;
            this.evidence = evidence;
            this.observationId = observationId;
        }
        public byte[] encode() {
            return bytes((evidence == Evidence.ADT_QUERY ? "ADT-STATE/3" : "ADT-STATE/2")
                    + "\nSTATE\n" + request + "\n" + state + "\n" + availability + "\n" + revision + "\n" + ageMillis
                    + "\n" + completedRequest + "\n" + evidence + (evidence == Evidence.ADT_QUERY ? "\n" + observationId : ""));
        }
    }
    public static Report parseReport(byte[] bytes) {
        String[] f = fields(bytes);
        if (f == null || !(f.length == 7 && "ADT-STATE/1".equals(f[0])
                    || f.length == 9 && "ADT-STATE/2".equals(f[0])
                    || f.length == 10 && "ADT-STATE/3".equals(f[0])) || !"STATE".equals(f[1])
                || !f[6].matches("0|[1-9][0-9]{0,18}")) return null;
        if (f.length == 10 && !Evidence.ADT_QUERY.name().equals(f[8])
                || f.length == 9 && Evidence.ADT_QUERY.name().equals(f[8])) return null;
        try { return new Report(f[2], State.valueOf(f[3]), Availability.valueOf(f[4]), f[5], Long.parseLong(f[6]),
                f.length >= 9 ? f[7] : "-", f.length >= 9 ? Evidence.valueOf(f[8]) : Evidence.ADT_NOTIFICATION,
                f.length == 10 ? f[9] : "-"); }
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

    /** A matched pre-commit refusal cannot authorize any alarm action. */
    public static final class Declined {
        public final AlarmAction action;
        public final String request;
        public final DeclineReason reason;
        public Declined(AlarmAction action, String request, DeclineReason reason) {
            if (action == null || !uuid(request) || reason == null) throw new IllegalArgumentException("Invalid refusal");
            this.action = action; this.request = request; this.reason = reason;
        }
        public byte[] encode() { return bytes("ADT-DECLINED/1\n" + action + "\n" + request + "\n" + reason); }
    }
    public static Declined parseDeclined(byte[] payload) {
        String[] f = fields(payload);
        if (f == null || f.length != 4 || !"ADT-DECLINED/1".equals(f[0])) return null;
        try { return new Declined(AlarmAction.valueOf(f[1]), f[2], DeclineReason.valueOf(f[3])); }
        catch (IllegalArgumentException error) { return null; }
    }
}
