package dev.personal.adtprobe;

/** One in-memory readiness request. Clearing it never permits a second request in this session. */
final class ArmReadinessWait {
    static final long MAX_WAIT_MS = 5_000;
    static final long STABLE_READY_MS = 1_500;
    private boolean offered;
    private String source;
    private String request;
    private long started;
    private long lastElapsed;
    private long until;
    private long readySince = -1;

    boolean offer(String source, String request, long now, long sessionUntil) {
        if (offered || source == null || source.isEmpty() || request == null || request.isEmpty()
                || now < 0 || sessionUntil <= now) return false;
        offered = true;
        this.source = source;
        this.request = request;
        started = lastElapsed = now;
        until = now + Math.min(MAX_WAIT_MS, sessionUntil - now);
        return true;
    }

    boolean hasRequest() { return request != null; }
    String source() { return source; }
    String requestId() { return request; }
    boolean matchesNode(String node) { return source != null && source.equals(node); }

    boolean isFresh(long now) {
        if (!hasRequest()) return false;
        if (now < started || now < lastElapsed || now >= until) {
            clear();
            return false;
        }
        lastElapsed = now;
        return true;
    }

    boolean canProceed(String node, boolean widgetReady, boolean fullyLocked, long now) {
        if (!isFresh(now)) return false;
        if (!matchesNode(node) || !widgetReady || !fullyLocked) {
            readySince = -1;
            return false;
        }
        if (readySince < 0) readySince = now;
        return now - readySince >= STABLE_READY_MS;
    }

    void clear() { source = request = null; readySince = -1; }
}
