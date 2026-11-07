package com.loco.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.loco.compat.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
class ReviewAggregationComparisonTest extends CompatibilityTestBase {
  @Test
  void createReplaceAndDeleteImmediatelyChangePlaceAggregates() throws Exception {
    ReviewTestSupport.seed(environment());
    String token = PlaceWriteSupport.token(environment()), place = PlaceObservationTest.place(1);
    for (int step = 0; step < 4; step++) {
      List<JsonNode> results = new ArrayList<>();
      for (var side : SharedHarness.Side.values()) {
        String base = side == SharedHarness.Side.V2 ? v2Url() : v3Url();
        var write =
            request(
                base,
                ReviewTestSupport.path(1) + (step >= 2 ? "/me" : ""),
                step >= 2 ? HttpMethod.DELETE : HttpMethod.POST,
                token,
                Map.of("rating", step == 0 ? 4 : 2, "content", "aggregate"));
        assertThat(write.getStatusCode().value()).isEqualTo(step >= 2 ? 200 : 201);
        assertThat(write.getBody().path("reviewCount").asInt()).isEqualTo(step >= 2 ? 0 : 1);
        assertThat(write.getBody().path("averageRating").asDouble())
            .isEqualTo(step >= 2 ? 0 : step == 0 ? 4 : 2);
        var readbacks = mapper.createArrayNode();
        for (String path :
            List.of(
                PlaceWriteSupport.createPath(1) + "?sort=reviews",
                "/api/places/" + place,
                "/api/places/me")) {
          var result = request(base, path, HttpMethod.GET, token, null);
          assertThat(result.getStatusCode().value()).isEqualTo(200);
          var body = result.getBody();
          JsonNode item = body;
          if (body.isArray() || body.has("items")) {
            var array = body.isArray() ? body : body.get("items");
            item =
                java.util.stream.StreamSupport.stream(array.spliterator(), false)
                    .filter(p -> p.path("id").asText().equals(place))
                    .findFirst()
                    .orElseThrow();
            if (body.has("items")) {
              assertThat(array.get(0).path("id").asText()).isEqualTo(PlaceObservationTest.place(2));
              assertThat(array.get(1).path("id").asText())
                  .isEqualTo(PlaceObservationTest.place(step >= 2 ? 3 : 1));
            }
          }
          assertThat(item.path("reviewCount").asInt()).isEqualTo(step >= 2 ? 0 : 1);
          assertThat(item.path("averageRating").asDouble())
              .isEqualTo(step >= 2 ? 0 : step == 0 ? 4 : 2);
          readbacks.add(body);
        }
        results.add(readbacks);
      }
      assertThat(results.get(1)).isEqualTo(results.get(0));
    }
  }
}
