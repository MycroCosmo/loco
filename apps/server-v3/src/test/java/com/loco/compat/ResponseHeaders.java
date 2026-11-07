package com.loco.compat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpResponse;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Compare the complete end-to-end header set, including absence, not a security-header allowlist.
 */
final class ResponseHeaders {
  private static final Set<String> TRANSPORT =
      Set.of("connection", "keep-alive", "transfer-encoding", "content-length");
  private static final Set<String> INSTRUMENTATION =
      Set.of("x-harness-handler", "x-harness-route", "x-harness-image-nanos");
  private static final Set<String> VARIABLE =
      Set.of("date", "etag", "last-modified", "x-ratelimit-remaining", "x-ratelimit-reset");

  private ResponseHeaders() {}

  static void compare(JsonNode expected, HttpResponse<byte[]> actual, String method) {
    var headers = actual.headers().map();
    assertThat(headers).doesNotContainKey("x-powered-by");
    if (method.equals("HEAD") && expected.has("content-length"))
      assertThat(actual.headers().firstValueAsLong("content-length").orElseThrow())
          .isEqualTo(Long.parseLong(expected.path("content-length").asText()));
    if (!method.equals("HEAD") && actual.statusCode() != 304)
      actual
          .headers()
          .firstValueAsLong("content-length")
          .ifPresent(n -> assertThat(n).isEqualTo(actual.body().length));
    var baseline = new TreeMap<String, String>();
    expected.properties().forEach(e -> baseline.put(e.getKey(), e.getValue().asText()));
    baseline.remove(
        "x-powered-by"); // 10-1 A: independently registered and checked intentional difference.
    var observed = new TreeMap<String, String>();
    headers.forEach(
        (key, values) -> observed.put(key.toLowerCase(Locale.ROOT), String.join(", ", values)));
    for (var entry : observed.entrySet()) {
      String key = entry.getKey(), value = entry.getValue();
      if (key.equals("date") || key.equals("last-modified"))
        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
      if (key.equals("etag")) assertThat(value).matches("(?:W/)?\"[^\"]+\"");
      if (key.equals("x-ratelimit-remaining") || key.equals("x-ratelimit-reset"))
        assertThat(Long.parseLong(value)).isNotNegative();
    }
    normalize(baseline);
    normalize(observed);
    assertThat(observed).as("complete response headers").isEqualTo(baseline);
  }

  private static void normalize(Map<String, String> headers) {
    TRANSPORT.forEach(headers::remove);
    INSTRUMENTATION.forEach(headers::remove);
    headers.replaceAll(
        (key, value) -> {
          if (VARIABLE.contains(key)) return "$variable";
          if (key.equals("content-type")) return value.toLowerCase(Locale.ROOT).replace("; ", ";");
          if (key.equals("cache-control") || key.equals("vary"))
            return String.join(
                ",", Arrays.stream(value.split(",")).map(String::trim).sorted().toList());
          return value;
        });
  }
}
