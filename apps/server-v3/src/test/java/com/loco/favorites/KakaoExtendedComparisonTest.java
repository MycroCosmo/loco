package com.loco.favorites;

import static org.assertj.core.api.Assertions.assertThat;

import com.loco.compat.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
class KakaoExtendedComparisonTest extends SubmissionTestSupport {
  @ParameterizedTest
  @ValueSource(
      strings = {
        ".5",
        "-.5",
        "+1.5",
        "0.0000001",
        "0.000001",
        "1000000000000000128",
        "1000000000000000000000",
        "99999999999999999999999",
        "0001.50",
        "1e2",
        "1,5",
        "",
        "1."
      })
  void allNumericParametersCompareWireValuesAndResponses(String value) throws Exception {
    seed();
    environment().reserveV2Requests("GET", "/api/places/search", 5);
    for (String key : List.of("x", "y", "radius", "page", "size")) {
      var results = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
      for (var side : SharedHarness.Side.values()) {
        environment().kakaoControl(Map.of("variant", "normal"));
        try (var db = environment().connect(side)) {
          var before = DatabaseSnapshot.capture(db, rules());
          var response =
              request(
                  side == SharedHarness.Side.V2 ? v2Url() : v3Url(),
                  "/api/places/search?query=cafe&"
                      + key
                      + "="
                      + java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8),
                  HttpMethod.GET,
                  environment().token("valid", user(1), "ignored@example.test"),
                  null);
          var actual = mapper.createObjectNode().put("status", response.getStatusCode().value());
          actual.set("body", response.getBody());
          actual.set("requests", environment().kakaoControl(null).path("requests"));
          results.add(actual);
          before.assertUnchanged(DatabaseSnapshot.capture(db, rules()));
        }
      }
      assertThat(results.get(1)).as("%s=%s", key, value).isEqualTo(results.get(0));
    }
  }

  @Test
  void looseMappingRetainsTypesAndUsesJavascriptFloatCoercion() throws Exception {
    seed();
    var raw =
        mapper.readTree(
            "{\"meta\":{\"total_count\":null,\"is_end\":[]},\"documents\":[{\"id\":{},\"place_name\":[],\"x\":[\"127.25\",\"ignored\"],\"y\":-0.0000001},{\"x\":true,\"y\":{}},{\"x\":\"  +1.25e2trailing\",\"y\":\"-0\"}]}");
    var bodies = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
    for (var side : SharedHarness.Side.values()) {
      environment().kakaoControl(Map.of("variant", "custom", "body", raw));
      try (var db = environment().connect(side)) {
        var before = DatabaseSnapshot.capture(db, rules());
        var response =
            request(
                side == SharedHarness.Side.V2 ? v2Url() : v3Url(),
                "/api/places/search?query=cafe",
                HttpMethod.GET,
                environment().token("valid", user(1), "ignored@example.test"),
                null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        bodies.add(response.getBody());
        assertThat(environment().kakaoControl(null).path("requests").size()).isEqualTo(1);
        before.assertUnchanged(DatabaseSnapshot.capture(db, rules()));
      }
    }
    JsonComparison.compare(bodies.get(0), bodies.get(1), Map.of());
  }
}
