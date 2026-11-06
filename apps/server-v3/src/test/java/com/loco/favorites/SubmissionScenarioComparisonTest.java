package com.loco.favorites;

import static org.assertj.core.api.Assertions.assertThat;

import com.loco.compat.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
class SubmissionScenarioComparisonTest extends SubmissionTestSupport {
  @Test
  void privateNonMemberFavoriteExposesRoomAndCountsThenDisappearsOnDelete() throws Exception {
    seed();
    var results = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
    String token = environment().token("valid", user(1), "ignored@example.test");
    for (var side : SharedHarness.Side.values()) {
      String base = side == SharedHarness.Side.V2 ? v2Url() : v3Url();
      var added = request(base, "/api/favorites", HttpMethod.POST, token, Map.of("mapId", map(3)));
      assertThat(added.getStatusCode().value()).isEqualTo(201);
      var list = request(base, "/api/favorites", HttpMethod.GET, token, null);
      assertThat(list.getStatusCode().value()).isEqualTo(200);
      assertThat(list.getBody().size()).isEqualTo(3);
      var first = list.getBody().get(0);
      assertThat(first.path("id").asText()).isEqualTo(added.getBody().path("id").asText());
      assertThat(first.at("/map/id").asText()).isEqualTo(map(3));
      assertThat(first.at("/map/visibility").asText()).isEqualTo("PRIVATE");
      assertThat(first.at("/map/inviteCode").asText()).isNotBlank();
      assertThat(first.at("/map/_count/favorites").asInt()).isEqualTo(1);
      results.add(normalize(list.getBody()));
      var deleted =
          request(
              base,
              "/api/favorites/" + added.getBody().path("id").asText(),
              HttpMethod.DELETE,
              token,
              null);
      assertThat(deleted.getStatusCode().value()).isEqualTo(200);
      var after = request(base, "/api/favorites", HttpMethod.GET, token, null);
      assertThat(after.getStatusCode().value()).isEqualTo(200);
      assertThat(after.getBody().size()).isEqualTo(2);
      try (var db = environment().connect(side);
          var q = db.prepareStatement("SELECT count(*) FROM favorites WHERE \"mapId\"=?")) {
        q.setString(1, map(3));
        try (var r = q.executeQuery()) {
          r.next();
          assertThat(r.getInt(1)).isZero();
        }
      }
    }
    assertThat(results.get(0)).isEqualTo(results.get(1));
  }
}
