package com.loco.favorites;

import static org.assertj.core.api.Assertions.assertThat;

import com.loco.compat.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
class KakaoResilienceComparisonTest extends SubmissionTestSupport {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "delay",
        "slow-body",
        "drip",
        "connection-failure",
        "error-body-drop",
        "503",
        "redirect"
      })
  void socketTimeoutAndNoRetryUseRealFakeServer(String variant) throws Exception {
    seed();
    environment().reserveV2Requests("GET", "/api/places/search", 1);
    for (var side : SharedHarness.Side.values()) {
      environment().kakaoControl(Map.of("variant", variant, "delayMillis", 6200));
      try (var db = environment().connect(side)) {
        var before = DatabaseSnapshot.capture(db, rules());
        long started = System.nanoTime();
        var response =
            request(
                side == SharedHarness.Side.V2 ? v2Url() : v3Url(),
                "/api/places/search?query=cafe",
                HttpMethod.GET,
                environment().token("valid", user(1), "ignored@example.test"),
                null);
        double seconds = (System.nanoTime() - started) / 1e9;
        boolean timeout =
            side == SharedHarness.Side.V3 && Set.of("delay", "slow-body").contains(variant);
        boolean success =
            variant.equals("drip")
                || (side == SharedHarness.Side.V2
                    && Set.of("delay", "slow-body").contains(variant));
        assertThat(response.getStatusCode().value()).isEqualTo(success ? 200 : 500);
        if (side == SharedHarness.Side.V3 && !success)
          assertThat(response.getBody()).isEqualTo(KakaoComparisonTest.fixedFailure());
        if (success)
          assertThat(response.getBody().at("/documents/0/placeName").asText()).isEqualTo("Cafe");
        if (timeout) assertThat(seconds).isBetween(4.5, 6.15);
        if (variant.equals("drip")) assertThat(seconds).isGreaterThan(6.0);
        assertThat(environment().kakaoControl(null).path("requests").size()).isEqualTo(1);
        before.assertUnchanged(DatabaseSnapshot.capture(db, rules()));
      }
    }
    if (Set.of("delay", "slow-body").contains(variant)) {
      var difference =
          Map.<String, Object>of(
              "case",
              "kakao.socket-timeout",
              "kind",
              "intentional",
              "reason",
              "8-2 B: connect/TLS 2s, response socket inactivity 5s, no overall deadline or retry",
              "v2",
              "6200ms idle -> 200",
              "v3",
              "~5000ms idle -> fixed 500");
      if (!environment().knownDifferences.contains(difference))
        environment().knownDifferences.add(difference);
    }
  }
}
