package com.loco.favorites;

import static org.assertj.core.api.Assertions.assertThat;

import com.loco.compat.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
class KakaoRateLimitComparisonTest extends SubmissionTestSupport {
  @Override
  protected boolean verifiesRateLimit() {
    return true;
  }

  @Test
  void authenticatedRequest121IsBlockedBeforeUpstreamAndDatabaseIsUnchanged() throws Exception {
    seed();
    environment().reserveV2Requests("GET", "/api/places/search", 120);
    var errors = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
    for (var side : SharedHarness.Side.values()) {
      environment().kakaoControl(Map.of("variant", "normal"));
      try (var db = environment().connect(side)) {
        var before = DatabaseSnapshot.capture(db, rules());
        for (int i = 0; i < 121; i++) {
          var response =
              request(
                  side == SharedHarness.Side.V2 ? v2Url() : v3Url(),
                  "/api/places/search?query=cafe",
                  HttpMethod.GET,
                  environment().token("valid", user(1), "ignored@example.test"),
                  null,
                  true);
          assertThat(response.getStatusCode().value()).isEqualTo(i < 120 ? 200 : 429);
          if (i < 120)
            assertThat(response.getHeaders().getFirst("X-RateLimit-Remaining"))
                .isEqualTo(Integer.toString(119 - i));
          else {
            errors.add(response.getBody());
            assertThat(Long.parseLong(response.getHeaders().getFirst("Retry-After")))
                .isBetween(59L, 60L);
          }
        }
        assertThat(environment().kakaoControl(null).path("requests").size()).isEqualTo(120);
        before.assertUnchanged(DatabaseSnapshot.capture(db, rules()));
      }
    }
    assertThat(errors.get(0)).isEqualTo(errors.get(1));
  }
}
