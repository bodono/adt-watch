package dev.personal.adtprobe;

import java.util.LinkedHashSet;
import java.util.Set;

/** Local bookkeeping only; no Android calls or provider identifiers. */
final class WidgetSetupState {
    static final int NONE = 0, BIND = 1, READY = 2, CONFIGURE = 3, CONFIGURED = 4;
    static final long MAX_AGE_MS = 15 * 60_000L;
    final Set<Integer> owned = new LinkedHashSet<>();
    int active = -1;
    int pending = -1;
    int stage;
    int request;
    int nextRequest = 1000;
    String token = "";
    long elapsed;
    long wall;

    void begin(int id, String newToken, long nowElapsed, long nowWall) {
        if (pending > 0 || id <= 0 || owned.contains(id) || newToken.isEmpty())
            throw new IllegalStateException("Widget setup already pending or allocation invalid");
        owned.add(id); pending = id; token = newToken;
        elapsed = nowElapsed; wall = nowWall; stage = BIND; request = 0;
    }

    int expect(int expectedStage) {
        if (pending <= 0 || !owned.contains(pending)
                || !((expectedStage == BIND && stage == BIND)
                    || (expectedStage == CONFIGURE && stage == READY)))
            throw new IllegalStateException("Unexpected widget setup stage");
        if (nextRequest < 1000 || nextRequest >= 65000) nextRequest = 1000;
        request = ++nextRequest; stage = expectedStage;
        return request;
    }

    boolean fresh(long nowElapsed, long nowWall) {
        return pending > 0 && owned.contains(pending) && pending != active && !token.isEmpty()
            && nowElapsed >= elapsed && nowElapsed - elapsed <= MAX_AGE_MS
            && nowWall >= wall && nowWall - wall <= MAX_AGE_MS;
    }

    boolean accept(int resultRequest, int resultId, long nowElapsed, long nowWall) {
        if (!fresh(nowElapsed, nowWall) || request == 0 || resultRequest != request
                || resultId != pending || (stage != BIND && stage != CONFIGURE)) return false;
        stage = stage == BIND ? READY : CONFIGURED;
        request = 0;
        return true;
    }

    void boundDirectly() {
        if (pending <= 0 || stage != BIND || request != 0)
            throw new IllegalStateException("Unexpected direct widget bind");
        stage = READY;
    }

    int commit(long nowElapsed, long nowWall) {
        if (stage != CONFIGURED || !fresh(nowElapsed, nowWall))
            throw new IllegalStateException("Widget setup was not freshly configured");
        int previous = active;
        active = pending;
        clearPending();
        return previous;
    }

    int cancel() {
        int previous = pending;
        clearPending();
        return previous;
    }

    private void clearPending() {
        pending = -1; stage = NONE; request = 0; token = ""; elapsed = wall = 0;
    }

    boolean mayDelete(int id) {
        return id > 0 && owned.contains(id) && id != active && id != pending;
    }
}
