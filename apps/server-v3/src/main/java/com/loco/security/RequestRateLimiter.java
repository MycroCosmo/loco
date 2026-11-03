package com.loco.security;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RequestRateLimiter {
    private static final long WINDOW = 60000;
    public record Decision(boolean allowed, int remaining, long resetSeconds, long retryAfterSeconds) { }
    private record Key(Object handler, String ip) { }
    private static final class Window {
        final ArrayDeque<Long> hits = new ArrayDeque<>();
        long resetAt;
        long blockedUntil;
    }
    private final Map<Key, Window> windows = new HashMap<>();
    private final Clock clock;
    private long cleanupAt;
    public RequestRateLimiter(Clock clock) { this.clock = clock; }

    public synchronized Decision check(Object handler, String ip, int limit) {
        long now = clock.millis();
        if (now >= cleanupAt) {
            windows.values().removeIf(window -> { expire(window, now); return window.hits.isEmpty() && window.blockedUntil <= now; });
            cleanupAt = now + WINDOW;
        }
        var window = windows.computeIfAbsent(new Key(handler, ip), ignored -> new Window());
        expire(window, now);
        if (window.resetAt <= now) window.resetAt = now + WINDOW;
        if (window.blockedUntil > now) return new Decision(false, 0, seconds(window.resetAt - now), seconds(window.blockedUntil - now));
        if (window.blockedUntil != 0) { window.hits.clear(); window.blockedUntil = 0; }
        window.hits.addLast(now);
        if (window.hits.size() > limit) {
            window.blockedUntil = now + WINDOW;
            return new Decision(false, 0, seconds(window.resetAt - now), 60);
        }
        return new Decision(true, limit - window.hits.size(), seconds(window.resetAt - now), 0);
    }
    private static void expire(Window window, long now) {
        while (!window.hits.isEmpty() && window.hits.peekFirst() + WINDOW <= now) window.hits.removeFirst();
    }
    private static long seconds(long millis) { return Math.max(0, (millis + 999) / 1000); }
}
