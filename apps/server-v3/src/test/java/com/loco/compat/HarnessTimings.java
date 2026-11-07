package com.loco.compat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class HarnessTimings {
  private static final Map<String, List<long[]>> INTERVALS = new HashMap<>();
  private static long remoteImageNanos;
  private static final Map<String, Map<String, Integer>> CASE_REQUESTS =
      new java.util.LinkedHashMap<>();
  private static Map<String, Integer> currentRequests;
  private static int caseNumber;

  static synchronized void beginCase(String name) {
    currentRequests = new java.util.LinkedHashMap<>();
    CASE_REQUESTS.put(++caseNumber + ":" + name, currentRequests);
  }

  static synchronized void observedRequest(String method, String route) {
    if (currentRequests != null && route != null)
      currentRequests.merge(method + " " + route, 1, Integer::sum);
  }

  private HarnessTimings() {}

  public static Scope measure(String category) {
    long start = System.nanoTime();
    return () -> record(category, start, System.nanoTime());
  }

  static synchronized void record(String category, long start, long end) {
    INTERVALS.computeIfAbsent(category, ignored -> new ArrayList<>()).add(new long[] {start, end});
  }

  static synchronized void remoteImage(long nanos) {
    remoteImageNanos += nanos;
  }

  public interface Scope extends AutoCloseable {
    @Override
    void close();
  }

  static long union(List<long[]> intervals) {
    long total = 0, end = Long.MIN_VALUE;
    for (var interval : intervals.stream().sorted(Comparator.comparingLong(a -> a[0])).toList()) {
      total += Math.max(0, interval[1] - Math.max(end, interval[0]));
      end = Math.max(end, interval[1]);
    }
    return total;
  }

  static synchronized Map<String, Object> report() {
    var times = new java.util.LinkedHashMap<String, Object>();
    long image = union(INTERVALS.getOrDefault("image", List.of())) + remoteImageNanos;
    long http = union(INTERVALS.getOrDefault("http", List.of()));
    if (image > http) throw new IllegalStateException("이미지 계측이 HTTP 구간을 벗어남");
    times.put("restartMillis", millis("restart"));
    times.put("preparationMillis", millis("preparation"));
    times.put("httpMillis", http / 1_000_000.0);
    times.put("requestExcludingImageMillis", (http - image) / 1_000_000.0);
    times.put("imageMillis", image / 1_000_000.0);
    times.put("v2ImageMillis", remoteImageNanos / 1_000_000.0);
    times.put("v3ImageMillis", millis("image"));
    times.put("restartSamples", INTERVALS.getOrDefault("restart", List.of()).size());
    times.put("preparationSamples", INTERVALS.getOrDefault("preparation", List.of()).size());
    times.put("requestSamples", INTERVALS.getOrDefault("http", List.of()).size());
    times.put("v2RequestsByCase", CASE_REQUESTS);
    return times;
  }

  private static double millis(String category) {
    return union(INTERVALS.getOrDefault(category, List.of())) / 1_000_000.0;
  }
}
