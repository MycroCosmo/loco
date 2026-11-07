package com.loco.favorites;

import static org.assertj.core.api.Assertions.assertThat;

import com.loco.compat.*;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
class KakaoPreloadTest extends SubmissionTestSupport {
  @org.junit.jupiter.api.Test
  void preloadRejectsOtherFetchTargetsWithoutFallback() throws Exception {
    assertThat(
            environment()
                .kakaoControl(Map.of("variant", "normal", "probeBlocked", true))
                .path("blocked")
                .asBoolean())
        .isTrue();
    var state = environment().kakaoControl(null);
    assertThat(state.path("blocked").asInt()).isEqualTo(1);
    assertThat(state.path("requests").size()).isZero();
  }

  static java.util.stream.Stream<com.fasterxml.jackson.databind.JsonNode> cases() throws Exception {
    try (var in = KakaoPreloadTest.class.getResourceAsStream("/kakao/http-v2.json")) {
      var rows = new com.fasterxml.jackson.databind.ObjectMapper().readTree(in).path("rows");
      return java.util.stream.StreamSupport.stream(rows.spliterator(), false);
    }
  }

  @ParameterizedTest
  @MethodSource("cases")
  void originalV2UsesOnlyFakeServer(com.fasterxml.jackson.databind.JsonNode row) throws Exception {
    seed();
    environment().reserveV2Requests("GET", "/api/places/search", 1);
    environment().kakaoControl(Map.of("variant", row.path("variant").asText()));
    try (var db = environment().connect(SharedHarness.Side.V2)) {
      var before = DatabaseSnapshot.capture(db, rules());
      var response =
          request(
              v2Url(),
              "/api/places/search?" + row.path("query").asText(),
              HttpMethod.GET,
              row.path("authenticated").asBoolean()
                  ? environment().token("valid", user(1), "ignored@example.test")
                  : null,
              null);
      assertThat(response.getStatusCode().value()).isEqualTo(row.path("status").asInt());
      JsonComparison.compare(row.path("body"), response.getBody(), Map.of());
      var observed = environment().kakaoControl(null);
      assertThat(observed.path("requests")).isEqualTo(row.path("upstream"));
      assertThat(observed.path("blocked").asInt()).isZero();
      assertThat(observed.path("rewrites").asInt()).isEqualTo(row.path("upstream").size());
      before.assertUnchanged(DatabaseSnapshot.capture(db, rules()));
    }
  }
}
