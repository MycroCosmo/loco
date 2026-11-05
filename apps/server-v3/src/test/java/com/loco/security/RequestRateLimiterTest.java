package com.loco.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class RequestRateLimiterTest {
  private final TestClock time = new TestClock();
  private final RequestRateLimiter limiter = new RequestRateLimiter(time);

  @Test
  void exactLimitThenBlockAndRecovery() {
    assertThat(limiter.check("login", "ip", 2).remaining()).isEqualTo(1);
    assertThat(limiter.check("login", "ip", 2).remaining()).isZero();
    assertThat(limiter.check("login", "ip", 2).retryAfterSeconds()).isEqualTo(60);
    time.millis += 59000;
    assertThat(limiter.check("login", "ip", 2).retryAfterSeconds()).isEqualTo(1);
    time.millis += 1000;
    var recovered = limiter.check("login", "ip", 2);
    assertThat(recovered.allowed()).isTrue();
    assertThat(recovered.remaining()).isEqualTo(1);
  }

  @Test
  void blockIsNotExtendedByRejectedRequests() {
    limiter.check("login", "ip", 1);
    limiter.check("login", "ip", 1);
    for (int i = 0; i < 5; i++) {
      time.millis += 10000;
      assertThat(limiter.check("login", "ip", 1).allowed()).isFalse();
    }
    time.millis += 10000;
    assertThat(limiter.check("login", "ip", 1).allowed()).isTrue();
  }

  @Test
  void individualHitsExpireAfterOneMinute() {
    limiter.check("login", "ip", 2);
    time.millis += 30000;
    limiter.check("login", "ip", 2);
    time.millis += 30000;
    var next = limiter.check("login", "ip", 2);
    assertThat(next.allowed()).isTrue();
    assertThat(next.remaining()).isZero();
  }

  @Test
  void methodsAndIpsHaveIndependentBudgets() {
    limiter.check("login", "a", 1);
    assertThat(limiter.check("login", "a", 1).allowed()).isFalse();
    assertThat(limiter.check("register", "a", 1).allowed()).isTrue();
    assertThat(limiter.check("login", "b", 1).allowed()).isTrue();
  }

  @Test
  void concurrentRequestsCannotOverrunLimit() throws Exception {
    try (var pool = Executors.newFixedThreadPool(16)) {
      var requests = new ArrayList<java.util.concurrent.Callable<Boolean>>();
      for (int i = 0; i < 40; i++) requests.add(() -> limiter.check("login", "ip", 10).allowed());
      int allowed = 0;
      for (var result : pool.invokeAll(requests)) if (result.get()) allowed++;
      assertThat(allowed).isEqualTo(10);
    }
  }

  private static final class TestClock extends Clock {
    long millis = 1000000;

    @Override
    public long millis() {
      return millis;
    }

    @Override
    public Instant instant() {
      return Instant.ofEpochMilli(millis);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}
