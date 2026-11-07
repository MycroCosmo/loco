package com.loco.favorites;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.loco.compat.*;
import com.loco.maps.SqlQueryCounter;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
@Import(SqlQueryCounter.Configuration.class)
class KakaoComparisonTest extends SubmissionTestSupport {
  static java.util.stream.Stream<JsonNode> cases() throws Exception {
    return KakaoPreloadTest.cases();
  }

  static JsonNode fixedFailure() throws Exception {
    return new com.fasterxml.jackson.databind.ObjectMapper()
        .readTree(
            "{\"statusCode\":500,\"message\":\"카카오 검색 API 호출 실패\",\"error\":\"Internal Server Error\"}");
  }

  @ParameterizedTest
  @MethodSource("cases")
  void comparesBothApisAndAllBusinessRows(JsonNode row) throws Exception {
    seed();
    environment().reserveV2Requests("GET", "/api/places/search", 1);
    for (var side : SharedHarness.Side.values()) {
      environment().kakaoControl(Map.of("variant", row.path("variant").asText()));
      try (var db = environment().connect(side)) {
        var before = DatabaseSnapshot.capture(db, rules());
        SqlQueryCounter.reset();
        var response =
            request(
                side == SharedHarness.Side.V2 ? v2Url() : v3Url(),
                "/api/places/search?" + row.path("query").asText(),
                HttpMethod.GET,
                row.path("authenticated").asBoolean()
                    ? environment().token("valid", user(1), "ignored@example.test")
                    : null,
                null);
        assertThat(response.getStatusCode().value()).isEqualTo(row.path("status").asInt());
        JsonNode expected =
            side == SharedHarness.Side.V3 && row.path("status").asInt() == 500
                ? fixedFailure()
                : row.path("body");
        JsonComparison.compare(expected, response.getBody(), Map.of());
        var transport = environment().kakaoControl(null);
        assertThat(transport.path("requests")).isEqualTo(row.path("upstream"));
        assertThat(transport.path("blocked").asInt()).isZero();
        if (side == SharedHarness.Side.V3) {
          assertThat(SqlQueryCounter.statements())
              .hasSize(row.path("authenticated").asBoolean() ? 1 : 0);
          assertThat(transport.path("rewrites").asInt()).isZero();
        }
        before.assertUnchanged(DatabaseSnapshot.capture(db, rules()));
      }
    }
    var difference =
        Map.<String, Object>of(
            "case",
            "kakao.failure-message",
            "kind",
            "intentional",
            "reason",
            "8-3 B: upstream details stay out of client responses; all external/parsing failures use fixed 500",
            "v2",
            "upstream body or generic Internal server error",
            "v3",
            "카카오 검색 API 호출 실패");
    if (row.path("status").asInt() == 500 && !environment().knownDifferences.contains(difference))
      environment().knownDifferences.add(difference);
  }
}
