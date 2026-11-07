package com.loco.compat;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

public final class AuthRequestBudget {
  private static final Map<String, Integer> LIMITS =
      Map.of(
          "/api/auth/register",
          5,
          "/api/auth/login",
          10,
          "/api/auth/forgot-password",
          3,
          "/api/auth/reset-password",
          5,
          "/api/auth/oauth/:provider",
          10,
          "/api/auth/refresh",
          120,
          "/api/auth/logout",
          120);
  private static final long WINDOW = Duration.ofSeconds(61).toNanos();
  private final Map<String, ArrayDeque<Long>> hits = new HashMap<>();
  private final Map<String, Long> blockedUntil = new HashMap<>();
  private final LongSupplier clock;
  private final Sleeper sleeper;
  private long waitedNanos;

  public AuthRequestBudget() {
    this(System::nanoTime, nanos -> Thread.sleep(Duration.ofNanos(nanos)));
  }

  AuthRequestBudget(LongSupplier clock, Sleeper sleeper) {
    this.clock = clock;
    this.sleeper = sleeper;
  }

  @FunctionalInterface
  interface Sleeper {
    void sleep(long nanos) throws InterruptedException;
  }

  public synchronized void before(String method, String path) throws InterruptedException {
    path = key(path);
    if (!method.equals("POST") || !path.startsWith("/api/auth/")) return;
    Integer limit = LIMITS.get(path);
    if (limit == null) throw new IllegalArgumentException("인증 API의 요청 제한을 먼저 등록하세요: " + path);
    waitForBlock(path);
    var queue = hits.computeIfAbsent(path, ignored -> new ArrayDeque<>());
    purge(queue);
    if (queue.size() >= limit) {
      waitFor(queue.peekFirst() + WINDOW - clock.getAsLong());
      purge(queue);
    }
    queue.addLast(clock.getAsLong());
  }

  public synchronized void freshWindow(String path) throws InterruptedException {
    path = key(path);
    waitForBlock(path);
    var queue = hits.computeIfAbsent(path, ignored -> new ArrayDeque<>());
    if (!queue.isEmpty()) waitFor(queue.peekLast() + WINDOW - clock.getAsLong());
    purge(queue);
  }

  // 429 자체를 검증할 때만 제한 전 대기를 우회하고 요청 시각은 기록한다.
  public synchronized void recordUnchecked(String path) {
    path = key(path);
    hits.computeIfAbsent(path, ignored -> new ArrayDeque<>()).addLast(clock.getAsLong());
  }

  public synchronized void blocked(String path) {
    blockedUntil.put(key(path), clock.getAsLong() + WINDOW);
  }

  private static String key(String path) {
    String route = path.split("\\?", 2)[0];
    if (route.endsWith("/")) route = route.substring(0, route.length() - 1);
    return route.startsWith("/api/auth/oauth/") ? "/api/auth/oauth/:provider" : route;
  }

  public long waitedMillis() {
    return Duration.ofNanos(waitedNanos).toMillis();
  }

  public synchronized void clearWindow() {
    hits.clear();
    blockedUntil.clear();
  }

  private void purge(ArrayDeque<Long> queue) {
    while (!queue.isEmpty() && queue.peekFirst() + WINDOW <= clock.getAsLong()) queue.removeFirst();
  }

  private void waitFor(long nanos) throws InterruptedException {
    if (nanos <= 0) return;
    long started = clock.getAsLong();
    sleeper.sleep(nanos);
    waitedNanos += clock.getAsLong() - started;
  }

  private void waitForBlock(String path) throws InterruptedException {
    Long deadline = blockedUntil.get(path);
    if (deadline != null) waitFor(deadline - clock.getAsLong());
  }
}
