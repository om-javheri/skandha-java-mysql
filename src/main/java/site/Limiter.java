package site;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/** At most perMinute hits a minute per key (a visitor's address), kept in memory. */
public final class Limiter {
    private final int perMinute;
    private final Map<String, Deque<Long>> hits = new HashMap<>();

    public Limiter(int perMinute) {
        this.perMinute = perMinute;
    }

    public synchronized boolean allow(String key) {
        long now = System.currentTimeMillis();
        Deque<Long> recent = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        while (!recent.isEmpty() && now - recent.peekFirst() >= 60_000) recent.pollFirst();
        boolean allowed = recent.size() < perMinute;
        if (allowed) recent.addLast(now);
        if (hits.size() > 10_000) hits.values().removeIf(times -> times.isEmpty() || now - times.peekLast() >= 60_000);
        return allowed;
    }
}
