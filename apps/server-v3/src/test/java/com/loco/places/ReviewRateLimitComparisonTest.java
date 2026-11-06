package com.loco.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.loco.compat.*;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
class ReviewRateLimitComparisonTest extends CompatibilityTestBase {
  @Override
  protected boolean verifiesRateLimit() {
    return true;
  }

  @Test
  void allFourHandlersHaveIndependent120RequestWindows() throws Exception {
    environment().reset("places/seed.sql");
    var routes = ReviewProtectionComparisonTest.routes().map(a -> a.get()).toList();
    for (var route : routes)
      environment().reserveV2Requests((String) route[0], (String) route[1], 120);
    for (var route : routes) {
      var method = HttpMethod.valueOf((String) route[0]);
      String path = (String) route[1];
      for (int i = 0; i < 121; i++) {
        var left = request(v2Url(), path, method, null, Map.of(), true);
        var right = request(v3Url(), path, method, null, Map.of(), true);
        int status = i < 120 ? 401 : 429;
        assertThat(left.getStatusCode().value())
            .as("v2 %s %s #%s", method, path, i + 1)
            .isEqualTo(status);
        assertThat(right.getStatusCode().value()).isEqualTo(status);
        JsonComparison.compare(left.getBody(), right.getBody(), Map.of());
        for (var response : java.util.List.of(left, right)) {
          if (i < 120) {
            assertThat(response.getHeaders().getFirst("X-RateLimit-Limit")).isEqualTo("120");
            assertThat(response.getHeaders().getFirst("X-RateLimit-Remaining"))
                .isEqualTo(Integer.toString(119 - i));
          } else
            assertThat(Long.parseLong(response.getHeaders().getFirst("Retry-After")))
                .isBetween(59L, 60L);
        }
      }
    }
  }
}
