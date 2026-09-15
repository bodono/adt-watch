package dev.personal.adtprobe;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded, process-local readiness deduplication. It stores no executable command or challenge. */
final class RoutinePrepareReplayGuard {
    private static final int LIMIT = 64;
    private static final long RETAIN_MS = 60_000;
    private final LinkedHashMap<String, Long> recent = new LinkedHashMap<>();
    private long last = -1;

    boolean admit(String source, AlarmAction action, String request, long now) {
        if (!WatchProtocol.validNodeId(source) || action == null || request == null || now < 0) return false;
        if (now < last) recent.clear();
        last = now;
        Iterator<Map.Entry<String, Long>> entries = recent.entrySet().iterator();
        while (entries.hasNext()) if (now - entries.next().getValue() >= RETAIN_MS) entries.remove();
        String key = source + "\n" + action.name() + "\n" + request;
        if (recent.containsKey(key)) return false;
        if (recent.size() >= LIMIT) return false;
        recent.put(key, now);
        return true;
    }
}
