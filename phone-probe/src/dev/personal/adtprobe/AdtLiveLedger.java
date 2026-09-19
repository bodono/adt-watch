package dev.personal.adtprobe;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Pure state model for authenticated, read-only ADT queries. It never dispatches commands. */
public final class AdtLiveLedger {
    public static final long QUERY_LIMIT_MS = 10_000;
    public static final long CACHE_LIMIT_MS = 60_000;
    public static final long PENDING_LIMIT_MS = 30_000;
    public enum Outcome { NONE, PENDING, CONFIRMED, UNCONFIRMED }

    /** Times belong to the querying phone's elapsed clock; boot is captured when the query starts. */
    public static final class Sample {
        public final AlarmStateProtocol.State state;
        public final String systemId, partitionId;
        public final long queryStartedElapsed, receivedElapsed;
        public final int boot;
        public final boolean providerBusy;

        public Sample(AlarmStateProtocol.State state, String systemId, String partitionId,
                long queryStartedElapsed, long receivedElapsed, int boot, boolean providerBusy) {
            this.state = state; this.systemId = systemId; this.partitionId = partitionId;
            this.queryStartedElapsed = queryStartedElapsed; this.receivedElapsed = receivedElapsed;
            this.boot = boot; this.providerBusy = providerBusy;
        }
    }

    /** Last reported state and command outcome are independent: UNCONFIRMED does not hide fresh ADT data. */
    public static final class Snapshot {
        public final AlarmStateProtocol.State state;
        public final AlarmStateProtocol.Availability availability;
        public final String revision, observationId, requestId, completedRequest;
        public final long observationAgeMillis, requestStartedElapsed;
        public final boolean fresh, providerBusy, pending, enabled;
        public final Outcome outcome;
        public final AlarmAction action;

        private Snapshot(AdtLiveLedger ledger, long now, boolean clockValid) {
            state = ledger.hasObservation ? ledger.state : AlarmStateProtocol.State.UNKNOWN;
            revision = ledger.revision; observationId = ledger.observationId;
            observationAgeMillis = clockValid && ledger.hasObservation
                ? now - ledger.queryStartedElapsed : Long.MAX_VALUE;
            fresh = clockValid && ledger.hasObservation && observationAgeMillis < CACHE_LIMIT_MS;
            providerBusy = fresh && ledger.providerBusy;
            outcome = ledger.outcome; pending = outcome == Outcome.PENDING;
            requestId = ledger.requestId; completedRequest = ledger.completedRequest;
            requestStartedElapsed = ledger.requestStartedElapsed;
            availability = !ledger.hasObservation ? AlarmStateProtocol.Availability.NO_STATE
                : !fresh ? AlarmStateProtocol.Availability.STALE
                : providerBusy || pending ? AlarmStateProtocol.Availability.BUSY
                : AlarmStateProtocol.action(state) == null ? AlarmStateProtocol.Availability.NO_STATE
                : AlarmStateProtocol.Availability.READY;
            enabled = availability == AlarmStateProtocol.Availability.READY;
            action = enabled ? AlarmStateProtocol.action(state) : null;
        }
    }

    private final String systemId, partitionId;
    private int clockBoot = -1, observationBoot = -1, requestBoot = -1;
    private long lastNow = -1, queryStartedElapsed = -1, receivedElapsed = -1, requestStartedElapsed = -1;
    private boolean clockFault, hasObservation, providerBusy;
    private AlarmStateProtocol.State state = AlarmStateProtocol.State.UNKNOWN;
    private String revision = "-", observationId = "-", requestId = "-", completedRequest = "-", beginRevision = "-";
    private AlarmAction requestAction;
    private Outcome outcome = Outcome.NONE;

    /** A binding is explicit and cannot silently switch to another system or partition. */
    public AdtLiveLedger(String systemId, String partitionId) {
        if (!validId(systemId) || !validId(partitionId)) throw new IllegalArgumentException("Invalid ADT binding");
        this.systemId = systemId; this.partitionId = partitionId;
    }

    /** Accept only a successful authenticated response; HTTP/login/parse failures are not samples. */
    public synchronized boolean observe(Sample sample) {
        if (sample == null || sample.state == null || !systemId.equals(sample.systemId)
                || !partitionId.equals(sample.partitionId) || sample.boot < 0
                || sample.queryStartedElapsed < 0 || sample.receivedElapsed < sample.queryStartedElapsed
                || sample.receivedElapsed - sample.queryStartedElapsed > QUERY_LIMIT_MS
                || sample.boot < clockBoot) return false;
        if (sample.boot == clockBoot && (sample.receivedElapsed < lastNow
                || sample.queryStartedElapsed <= queryStartedElapsed
                || clockFault && sample.queryStartedElapsed < lastNow)) return false;
        // An old callback must not invalidate a newer accepted observation. A new boot does invalidate it.
        if (!advanceClock(sample.receivedElapsed, sample.boot, true)) return false;
        expirePending(sample.receivedElapsed, sample.boot);
        if (!AlarmStateProtocol.uuid(revision) || state != sample.state) revision = uuid();
        state = sample.state; observationId = uuid(); hasObservation = true;
        queryStartedElapsed = sample.queryStartedElapsed; receivedElapsed = sample.receivedElapsed;
        observationBoot = sample.boot; providerBusy = sample.providerBusy;
        if (outcome == Outcome.PENDING && sample.boot == requestBoot
                && sample.queryStartedElapsed > requestStartedElapsed && !sample.providerBusy
                && isTarget(sample.state, requestAction)) {
            outcome = Outcome.CONFIRMED;
            completedRequest = requestId;
        }
        return true;
    }

    /** Persist save() after true, before the foreign click. Calling this never performs that click. */
    public synchronized boolean begin(String expectedRevision, AlarmAction action, String request,
            long now, int boot) {
        Snapshot current = snapshot(now, boot);
        if (!current.enabled || !revision.equals(expectedRevision) || action == null || current.action != action
                || !AlarmStateProtocol.uuid(request) || request.equals(requestId)) return false;
        requestId = request; requestAction = action; requestStartedElapsed = now; requestBoot = boot;
        beginRevision = revision; completedRequest = "-"; outcome = Outcome.PENDING;
        return true;
    }

    /** Call with the current elapsed clock and boot count, including after process recreation. */
    public synchronized Snapshot snapshot(long now, int boot) {
        boolean valid = advanceClock(now, boot, false);
        if (valid) expirePending(now, boot);
        return new Snapshot(this, now, valid && !clockFault && observationBoot == boot);
    }

    private boolean advanceClock(long now, int boot, boolean newObservation) {
        if (now < 0 || boot < 0 || boot < clockBoot) return false;
        if (boot != clockBoot) {
            invalidateObservation();
            clockBoot = boot; lastNow = now; clockFault = false;
            return true;
        }
        if (now < lastNow) {
            invalidateObservation(); clockFault = true;
            return false;
        }
        if (clockFault && !newObservation) return false;
        lastNow = now;
        if (newObservation) clockFault = false;
        return true;
    }

    private void invalidateObservation() {
        hasObservation = false; providerBusy = false; observationBoot = -1;
        queryStartedElapsed = -1; receivedElapsed = -1; observationId = "-";
        if (outcome == Outcome.PENDING) outcome = Outcome.UNCONFIRMED;
    }

    private void expirePending(long now, int boot) {
        if (outcome == Outcome.PENDING && (boot != requestBoot || now < requestStartedElapsed
                || now - requestStartedElapsed >= PENDING_LIMIT_MS)) outcome = Outcome.UNCONFIRMED;
    }

    private static boolean isTarget(AlarmStateProtocol.State state, AlarmAction action) {
        return action == AlarmAction.DISARM && state == AlarmStateProtocol.State.DISARMED
            || action == AlarmAction.ARM_STAY && state == AlarmStateProtocol.State.ARMED_STAY;
    }

    /** Private persistence data, never diagnostic output. Store atomically in app-private storage. */
    public synchronized Map<String, String> save() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("version", "1"); values.put("system", systemId); values.put("partition", partitionId);
        values.put("clockBoot", Integer.toString(clockBoot)); values.put("lastNow", Long.toString(lastNow));
        values.put("clockFault", Boolean.toString(clockFault)); values.put("hasObservation", Boolean.toString(hasObservation));
        values.put("state", state.name()); values.put("revision", revision); values.put("observationId", observationId);
        values.put("queryStarted", Long.toString(queryStartedElapsed)); values.put("received", Long.toString(receivedElapsed));
        values.put("observationBoot", Integer.toString(observationBoot)); values.put("providerBusy", Boolean.toString(providerBusy));
        values.put("request", requestId); values.put("requestAction", requestAction == null ? "-" : requestAction.name());
        values.put("requestStarted", Long.toString(requestStartedElapsed)); values.put("requestBoot", Integer.toString(requestBoot));
        values.put("beginRevision", beginRevision); values.put("outcome", outcome.name()); values.put("completedRequest", completedRequest);
        return Collections.unmodifiableMap(values);
    }

    /** Invalid or differently bound saved data produces an empty ledger requiring a new ADT query. */
    public static AdtLiveLedger restore(String systemId, String partitionId, Map<String, String> values) {
        AdtLiveLedger ledger = new AdtLiveLedger(systemId, partitionId);
        if (values == null || !"1".equals(values.get("version")) || !systemId.equals(values.get("system"))
                || !partitionId.equals(values.get("partition"))) return ledger;
        try {
            ledger.clockBoot = Integer.parseInt(values.get("clockBoot")); ledger.lastNow = Long.parseLong(values.get("lastNow"));
            ledger.clockFault = bool(values, "clockFault"); ledger.hasObservation = bool(values, "hasObservation");
            ledger.state = AlarmStateProtocol.State.valueOf(values.get("state")); ledger.revision = values.get("revision");
            ledger.observationId = values.get("observationId"); ledger.queryStartedElapsed = Long.parseLong(values.get("queryStarted"));
            ledger.receivedElapsed = Long.parseLong(values.get("received")); ledger.observationBoot = Integer.parseInt(values.get("observationBoot"));
            ledger.providerBusy = bool(values, "providerBusy"); ledger.requestId = values.get("request");
            ledger.requestAction = "-".equals(values.get("requestAction")) ? null : AlarmAction.valueOf(values.get("requestAction"));
            ledger.requestStartedElapsed = Long.parseLong(values.get("requestStarted")); ledger.requestBoot = Integer.parseInt(values.get("requestBoot"));
            ledger.beginRevision = values.get("beginRevision"); ledger.outcome = Outcome.valueOf(values.get("outcome"));
            ledger.completedRequest = values.get("completedRequest");
            if (!ledger.validSavedState()) throw new IllegalArgumentException("Invalid saved ADT ledger");
            return ledger;
        } catch (IllegalArgumentException | NullPointerException error) {
            return new AdtLiveLedger(systemId, partitionId);
        }
    }

    private boolean validSavedState() {
        if (clockBoot < -1 || lastNow < -1 || (clockBoot == -1) != (lastNow == -1)
                || !uuidOrEmpty(revision) || !uuidOrEmpty(observationId) || !uuidOrEmpty(requestId)
                || !uuidOrEmpty(completedRequest) || !uuidOrEmpty(beginRevision)) return false;
        if (hasObservation && (clockFault || observationBoot != clockBoot || observationBoot < 0
                || !AlarmStateProtocol.uuid(revision) || !AlarmStateProtocol.uuid(observationId)
                || queryStartedElapsed < 0 || receivedElapsed < queryStartedElapsed || receivedElapsed > lastNow
                || receivedElapsed - queryStartedElapsed > QUERY_LIMIT_MS)) return false;
        if (!hasObservation && (observationBoot != -1 || queryStartedElapsed != -1 || receivedElapsed != -1
                || !"-".equals(observationId) || providerBusy)) return false;
        if (outcome == Outcome.NONE) return "-".equals(requestId) && requestAction == null
            && requestStartedElapsed == -1 && requestBoot == -1 && "-".equals(beginRevision) && "-".equals(completedRequest);
        if (!AlarmStateProtocol.uuid(requestId) || requestAction == null || requestStartedElapsed < 0
                || requestBoot < 0 || requestBoot > clockBoot || !AlarmStateProtocol.uuid(beginRevision)
                || requestBoot == clockBoot && requestStartedElapsed > lastNow) return false;
        if (outcome == Outcome.PENDING && (clockFault || requestBoot != clockBoot)) return false;
        return outcome == Outcome.CONFIRMED ? requestId.equals(completedRequest) : "-".equals(completedRequest);
    }

    private static boolean bool(Map<String, String> values, String key) {
        String value = values.get(key);
        if (!"true".equals(value) && !"false".equals(value)) throw new IllegalArgumentException("Invalid saved flag");
        return Boolean.parseBoolean(value);
    }
    private static boolean uuidOrEmpty(String value) { return "-".equals(value) || AlarmStateProtocol.uuid(value); }
    private static String uuid() { return UUID.randomUUID().toString(); }
    private static boolean validId(String value) {
        if (value == null || value.isEmpty() || value.length() > 256) return false;
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return false;
        return true;
    }
}
