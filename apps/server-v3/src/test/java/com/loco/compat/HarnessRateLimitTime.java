package com.loco.compat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import com.loco.security.RequestRateLimiter;

// 제한 시계만 이동한다. JWT 발급·검증과 JPA 감사 시계는 실제 UTC 시각을 유지한다.
public final class HarnessRateLimitTime {
    private final AtomicLong offset = new AtomicLong();
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.offset(Clock.system(zone), java.time.Duration.ofMillis(offset.get())); }
        @Override public Instant instant() { return Instant.now().plusMillis(offset.get()); }
    };
    public RequestRateLimiter limiter() { return new RequestRateLimiter(clock); }
    public void nextWindow() { offset.addAndGet(61000); }
}
