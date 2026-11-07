package com.loco.favorites;

import static org.assertj.core.api.Assertions.assertThat;

import com.loco.compat.*;
import com.loco.maps.SqlQueryCounter;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
@Import(SqlQueryCounter.Configuration.class)
class KakaoProtectionComparisonTest extends SubmissionTestSupport {
  @Test
  void sixAuthenticationFailuresNeverCallUpstreamOrChangeBusinessRows() throws Exception {
    seed();
    String[] tokens = {
      null,
      environment().token("expired", user(1), "ignored@example.test"),
      environment().token("wrong-signature", user(1), "ignored@example.test"),
      "malformed",
      environment().token("valid", user(4), "ignored@example.test"),
      environment().token("valid", user(9), "ignored@example.test")
    };
    environment().reserveV2Requests("GET", "/api/places/search", 6);
    for (var side : SharedHarness.Side.values()) {
      environment().kakaoControl(Map.of("variant", "normal"));
      try (var db = environment().connect(side)) {
        var before = DatabaseSnapshot.capture(db, rules());
        for (int i = 0; i < tokens.length; i++) {
          SqlQueryCounter.reset();
          var response =
              request(
                  side == SharedHarness.Side.V2 ? v2Url() : v3Url(),
                  "/api/places/search?unknown=invalid",
                  HttpMethod.GET,
                  tokens[i],
                  null);
          assertThat(response.getStatusCode().value()).isEqualTo(401);
          assertThat(response.getBody())
              .isEqualTo(mapper.readTree("{\"statusCode\":401,\"message\":\"Unauthorized\"}"));
          if (side == SharedHarness.Side.V3)
            assertThat(SqlQueryCounter.statements()).hasSize(i >= 4 ? 1 : 0);
        }
        assertThat(environment().kakaoControl(null).path("requests").size()).isZero();
        before.assertUnchanged(DatabaseSnapshot.capture(db, rules()));
      }
    }
  }
}
