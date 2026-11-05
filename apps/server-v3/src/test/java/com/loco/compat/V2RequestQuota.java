package com.loco.compat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;

/** v2가 반환한 실제 handler 키와 잔여 횟수로 보호한다. 케이스 중간에는 재시작하지 않는다. */
final class V2RequestQuota {
  private static final long WINDOW = Duration.ofSeconds(61).toNanos();
  private final Map<String, Window> windows = new HashMap<>();
  private final ArrayList<Route> routes = new ArrayList<>();
  private final Consumer<String> restart;
  private final LongSupplier clock;
  private final Sleeper sleeper;
  private boolean caseStarted;
  private int inFlight;
  private long waitedNanos;

  private static final class Window {
    int limit, remaining;
    long expires;
  }

  private record Route(String method, String template, Pattern pattern, String handler) {}

  record Reservation(Window window, boolean reserved) {}

  interface Sleeper {
    void sleep(long nanos) throws InterruptedException;
  }

  V2RequestQuota(Consumer<String> restart) {
    this.restart = restart;
    clock = System::nanoTime;
    sleeper = nanos -> java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(this, nanos);
  }

  V2RequestQuota(Consumer<String> restart, LongSupplier clock, Sleeper sleeper) {
    this.restart = restart;
    this.clock = clock;
    this.sleeper = sleeper;
  }

  synchronized void beginCase() {
    if (inFlight != 0) throw new IllegalStateException("이전 케이스 요청이 아직 진행 중");
    caseStarted = false;
  }

  synchronized void reserveBeforeScenario(String method, String path, int count) {
    if (caseStarted || inFlight != 0) throw new IllegalStateException("요청 예약은 시나리오 시작 전에만 가능");
    var window = find(method, path);
    if (window != null && remaining(window) < count)
      reset("reserve " + method + " " + path + " slots=" + count);
  }

  synchronized Reservation before(String method, String path, boolean probe)
      throws InterruptedException {
    var window = find(method, path);
    if (!caseStarted
        && window != null
        && (probe ? remaining(window) < window.limit : remaining(window) == 0)) {
      reset((probe ? "probe " : "exhausted ") + method + " " + path);
    }
    caseStarted = true;
    if (!probe && window != null) {
      while (remaining(window) == 0) {
        long start = clock.getAsLong();
        sleeper.sleep(Math.max(1, window.expires - start));
        waitedNanos += clock.getAsLong() - start;
      }
      window.remaining--;
    }
    inFlight++;
    return new Reservation(window, !probe && window != null);
  }

  synchronized void observed(String method, HttpHeaders headers, int status) {
    observed(method, headers, status, null);
  }

  synchronized void observed(
      String method, HttpHeaders headers, int status, Reservation reservation) {
    String handler = headers.getFirst("X-Harness-Handler"),
        template = headers.getFirst("X-Harness-Route");
    String limit = headers.getFirst("X-RateLimit-Limit"),
        remaining = headers.getFirst("X-RateLimit-Remaining");
    if (reservation != null
        && reservation.reserved
        && (handler == null || windows.get(handler) != reservation.window)) {
      reservation.window.remaining =
          Math.min(reservation.window.limit, reservation.window.remaining + 1);
    }
    if (handler == null || template == null) return;
    Window window = windows.get(handler);
    if (window == null && limit == null) throw new AssertionError("관측하지 않은 핸들러에서 429 발생");
    if (window == null) {
      window = new Window();
      window.limit = Integer.parseInt(limit);
      window.remaining = window.limit;
      windows.put(handler, window);
    }
    if (routes.stream().noneMatch(r -> r.method.equals(method) && r.template.equals(template))) {
      String expression =
          java.util.Arrays.stream(template.split("/", -1))
              .map(part -> part.startsWith(":") ? "[^/]+" : Pattern.quote(part))
              .collect(java.util.stream.Collectors.joining("/"));
      routes.add(
          new Route(
              method,
              template,
              Pattern.compile("^" + expression + "/?$", Pattern.CASE_INSENSITIVE),
              handler));
      routes.sort(
          java.util.Comparator.comparingInt(
                  (Route r) -> r.template.replaceAll(":[^/]+", "").length())
              .reversed());
    }
    // 응답 순서가 뒤집혀도 이미 예약한 동시 요청의 슬롯을 되돌리지 않는다.
    int observed = status == 429 ? 0 : Integer.parseInt(remaining);
    window.remaining = Math.min(remaining(window), observed);
    window.expires = clock.getAsLong() + WINDOW;
  }

  synchronized void complete() {
    inFlight--;
    notifyAll();
  }

  synchronized long waitedMillis() {
    return Duration.ofNanos(waitedNanos).toMillis();
  }

  private Window find(String method, String path) {
    String clean = path.split("\\?", 2)[0];
    return routes.stream()
        .filter(r -> r.method.equals(method) && r.pattern.matcher(clean).matches())
        .findFirst()
        .map(r -> windows.get(r.handler))
        .orElse(null);
  }

  private int remaining(Window window) {
    if (clock.getAsLong() >= window.expires) {
      window.remaining = window.limit;
      window.expires = clock.getAsLong() + WINDOW;
    }
    return window.remaining;
  }

  private void reset(String reason) {
    if (inFlight != 0) throw new IllegalStateException("진행 중인 요청이 있는데 재시작 시도");
    restart.accept(reason);
    for (var window : windows.values()) {
      window.remaining = window.limit;
      window.expires = 0;
    }
  }
}
