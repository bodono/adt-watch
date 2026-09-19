package dev.personal.adtprobe;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Closed, one-shot alarm experiment protocol. Action is immutable; no shared clock. */
public final class ArmExperimentProtocol {
    public static final String PREPARE_PATH = "/adt-probe/v2/alarm/prepare";
    public static final String CHALLENGE_PATH = "/adt-probe/v2/alarm/challenge";
    public static final String COMMIT_PATH = "/adt-probe/v2/alarm/commit";
    public static final String RESULT_PATH = "/adt-probe/v2/alarm/result";
    public static final int MAX_PAYLOAD_BYTES = 256;
    // From the tap: the phone's ADT preflight read, service start, widget render and stable
    // window, node check and two transport hops all fit inside it before a challenge arrives.
    public static final long READINESS_TIMEOUT_MS = 15_000L;
    public static final long TOTAL_TIMEOUT_MS = 30_000L;
    public static final long CONFIRM_TIMEOUT_MS = 20_000L;
    public static final long RESULT_TIMEOUT_MS = 10_000L;
    // The phone measures this independently from when it issues its challenge.
    public static final long PHONE_CHALLENGE_TIMEOUT_MS = 20_000L;
    private static final String HEADER = "ADT-ALARM/2";

    public enum Kind { PREPARE, CHALLENGE, COMMIT, RESULT }
    public enum Outcome { REQUESTED, REJECTED }
    public enum Phase {
        DISCOVERING, AWAITING_CHALLENGE, CONFIRMABLE, AWAITING_RESULT,
        FINISHED, CANCELLED, EXPIRED
    }

    private ArmExperimentProtocol() { }

    public static final class Message {
        public final Kind kind;
        public final AlarmAction action;
        public final String requestId;
        public final String challengeId;
        public final Outcome outcome;

        private Message(Kind kind, AlarmAction action, String requestId, String challengeId, Outcome outcome) {
            this.kind = kind;
            this.action = action;
            this.requestId = requestId;
            this.challengeId = challengeId;
            this.outcome = outcome;
        }
    }

    /** Returns null for oversized, non-ASCII, noncanonical, or unknown messages. */
    public static Message parse(byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) return null;
        for (byte value : payload) {
            int c = value & 0xff;
            if (c != '\n' && (c < 32 || c > 126)) return null;
        }
        String[] fields = new String(payload, StandardCharsets.US_ASCII).split("\n", -1);
        if (fields.length < 4 || !HEADER.equals(fields[0]) || !validUuid(fields[3])) return null;
        final Kind kind;
        final AlarmAction action;
        try {
            kind = Kind.valueOf(fields[1]);
            action = AlarmAction.valueOf(fields[2]);
        }
        catch (IllegalArgumentException error) { return null; }
        int count = kind == Kind.PREPARE ? 4 : kind == Kind.RESULT ? 6 : 5;
        if (fields.length != count) return null;
        String challenge = kind == Kind.PREPARE ? null : fields[4];
        if (challenge != null && !validUuid(challenge)) return null;
        Outcome outcome = null;
        if (kind == Kind.RESULT) {
            try { outcome = Outcome.valueOf(fields[5]); }
            catch (IllegalArgumentException error) { return null; }
        }
        return new Message(kind, action, fields[3], challenge, outcome);
    }

    public static Message parse(byte[] payload, Kind expected) {
        Message message = parse(payload);
        return expected != null && message != null && message.kind == expected ? message : null;
    }

    private static boolean validUuid(String value) {
        return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    private static byte[] encode(Kind kind, AlarmAction action, String request, String challenge, Outcome outcome) {
        if (kind == null || action == null || !validUuid(request)
                || (kind == Kind.PREPARE ? challenge != null : !validUuid(challenge))
                || (kind == Kind.RESULT ? outcome == null : outcome != null))
            throw new IllegalArgumentException("Invalid alarm experiment message");
        String text = HEADER + "\n" + kind.name() + "\n" + action.name() + "\n" + request;
        if (challenge != null) text += "\n" + challenge;
        if (outcome != null) text += "\n" + outcome.name();
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    public static byte[] encodePrepare(AlarmAction action, String request) {
        return encode(Kind.PREPARE, action, request, null, null);
    }
    public static byte[] encodeChallenge(AlarmAction action, String request, String challenge) {
        return encode(Kind.CHALLENGE, action, request, challenge, null);
    }
    public static byte[] encodeCommit(AlarmAction action, String request, String challenge) {
        return encode(Kind.COMMIT, action, request, challenge, null);
    }
    public static byte[] encodeResult(AlarmAction action, String request, String challenge, Outcome outcome) {
        return encode(Kind.RESULT, action, request, challenge, outcome);
    }

    // Source compatibility while phone fixtures migrate. These emit v2 only.
    public static byte[] encodePrepare(String request) {
        return encodePrepare(AlarmAction.ARM_STAY, request);
    }
    public static byte[] encodeChallenge(String request, String challenge) {
        return encodeChallenge(AlarmAction.ARM_STAY, request, challenge);
    }
    public static byte[] encodeCommit(String request, String challenge) {
        return encodeCommit(AlarmAction.ARM_STAY, request, challenge);
    }
    public static byte[] encodeResult(String request, String challenge, Outcome outcome) {
        return encodeResult(AlarmAction.ARM_STAY, request, challenge, outcome);
    }

    /** Watch-only memory state. Caller performs network sends after receiving encoded bytes. */
    public static final class Attempt {
        private final AlarmAction action;
        private final String request;
        private final long started;
        private long lastElapsed;
        private long challenged;
        private long committed;
        private String target;
        private String challenge;
        private Phase phase = Phase.DISCOVERING;
        private Outcome outcome;

        public Attempt(AlarmAction action, long elapsedNow) { this(action, UUID.randomUUID().toString(), elapsedNow); }

        public Attempt(long elapsedNow) { this(AlarmAction.ARM_STAY, elapsedNow); }

        // Stable local test identity; production callers generate a fresh UUID above.
        Attempt(String request, long elapsedNow) {
            this(AlarmAction.ARM_STAY, request, elapsedNow);
        }

        Attempt(AlarmAction action, String request, long elapsedNow) {
            if (action == null || !validUuid(request) || elapsedNow < 0) throw new IllegalArgumentException("Invalid attempt");
            this.action = action;
            this.request = request;
            started = lastElapsed = elapsedNow;
        }

        public AlarmAction action() { return action; }
        public synchronized String requestId() { return request; }
        public synchronized String challengeId() { return challenge; }
        public synchronized Phase phase() { return phase; }
        public synchronized Outcome outcome() { return outcome; }

        /** Expiry is terminal, including a backward/reset local elapsed clock. */
        public synchronized boolean isActive(long now) {
            if (phase == Phase.FINISHED || phase == Phase.CANCELLED || phase == Phase.EXPIRED) return false;
            if (now < started || now < lastElapsed || now - started >= TOTAL_TIMEOUT_MS
                    || ((phase == Phase.DISCOVERING || phase == Phase.AWAITING_CHALLENGE)
                        && now - started >= READINESS_TIMEOUT_MS)
                    || (phase == Phase.CONFIRMABLE && now - challenged >= CONFIRM_TIMEOUT_MS)
                    || (phase == Phase.AWAITING_RESULT && now - committed >= RESULT_TIMEOUT_MS)) {
                phase = Phase.EXPIRED;
                return false;
            }
            lastElapsed = now;
            return true;
        }

        public synchronized boolean selectTarget(String source, long now) {
            if (!isActive(now) || phase != Phase.DISCOVERING || target != null || !validNode(source)) return false;
            target = source;
            return true;
        }

        /** One PREPARE at most; null means expired, already emitted, or no chosen node. */
        public synchronized byte[] prepare(long now) {
            if (!isActive(now) || phase != Phase.DISCOVERING || target == null) return null;
            phase = Phase.AWAITING_CHALLENGE;
            return encodePrepare(action, request);
        }

        public synchronized boolean acceptChallenge(byte[] payload, String source, long now) {
            if (!isActive(now) || phase != Phase.AWAITING_CHALLENGE || !target.equals(source)) return false;
            Message message = parse(payload, Kind.CHALLENGE);
            if (message == null || action != message.action || !request.equals(message.requestId)) return false;
            challenge = message.challengeId;
            challenged = now;
            phase = Phase.CONFIRMABLE;
            return true;
        }

        public synchronized boolean acceptDeclined(byte[] payload, String source, long now) {
            if (!isActive(now) || phase != Phase.AWAITING_CHALLENGE || !target.equals(source)) return false;
            AlarmStateProtocol.Declined refusal = AlarmStateProtocol.parseDeclined(payload);
            if (refusal == null || refusal.action != action || !request.equals(refusal.request)) return false;
            outcome = Outcome.REJECTED;
            phase = Phase.FINISHED;
            return true;
        }

        public synchronized boolean canConfirm(long now) {
            return isActive(now) && phase == Phase.CONFIRMABLE;
        }

        /** Caller invokes this only for the user's positive confirmation. No retry is possible. */
        public synchronized byte[] commit(long now) {
            if (!canConfirm(now)) return null;
            committed = now;
            phase = Phase.AWAITING_RESULT;
            return encodeCommit(action, request, challenge);
        }

        public synchronized boolean acceptResult(byte[] payload, String source, long now) {
            if (!isActive(now) || (phase != Phase.AWAITING_RESULT && phase != Phase.CONFIRMABLE)
                    || !target.equals(source)) return false;
            Message message = parse(payload, Kind.RESULT);
            if (message == null || action != message.action || !request.equals(message.requestId)
                    || !challenge.equals(message.challengeId)) return false;
            // The phone may revoke an issued challenge before the watch confirms it.
            // Only rejection is meaningful then; REQUESTED still requires our prior commit.
            if (phase == Phase.CONFIRMABLE && message.outcome != Outcome.REJECTED) return false;
            outcome = message.outcome;
            phase = Phase.FINISHED;
            return true;
        }

        public synchronized void cancel() {
            if (phase != Phase.FINISHED && phase != Phase.EXPIRED) phase = Phase.CANCELLED;
        }

        private static boolean validNode(String value) {
            if (value == null || value.isEmpty() || value.length() > 256) return false;
            for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return false;
            return true;
        }
    }
}
