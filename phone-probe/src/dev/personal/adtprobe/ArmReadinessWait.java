package dev.personal.adtprobe;

/** One in-memory readiness request. Clearing it never permits a second request in this session. */
final class ArmReadinessWait {
    static final long MAX_WAIT_MS = 5_000;
    /**
     * How long the reviewed widget must stay idle, and unchanged, before a challenge is issued. It
     * lets the provider's re-render after updateAppWidgetOptions land first; commit() still rejects
     * any render after the challenge, so this trades a little latency for fewer spurious rejections.
     */
    static final long STABLE_READY_MS = 500;
    private boolean offered;
    private String source;
    private String request;
    private long started;
    private long lastElapsed;
    private long until;
    private long readySince = -1;
    private long readyGeneration = -1;

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

    /** The stable window restarts whenever the widget's render generation changes, not only when readiness dips. */
    boolean canProceed(String node, boolean widgetReady, long generation, boolean fullyLocked, long now) {
        if (!isFresh(now)) return false;
        if (!matchesNode(node) || !widgetReady || !fullyLocked) {
            readySince = -1;
            return false;
        }
        if (readySince < 0 || generation != readyGeneration) {
            readySince = now;
            readyGeneration = generation;
        }
        return now - readySince >= STABLE_READY_MS;
    }

    void clear() { source = request = null; readySince = -1; }
}
