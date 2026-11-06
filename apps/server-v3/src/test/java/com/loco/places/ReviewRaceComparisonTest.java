package com.loco.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.loco.compat.*;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
class ReviewRaceComparisonTest extends CompatibilityTestBase {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void bothWritersSucceedAndOneReviewRemains(boolean existing) throws Exception {
    ReviewTestSupport.seed(environment());
    var rules = PlaceWriteSupport.rules();
    DatabaseSnapshot before;
    try (var db = environment().connect(SharedHarness.Side.V2)) {
      before = DatabaseSnapshot.capture(db, rules);
    }
    String place = PlaceObservationTest.place(existing ? 2 : 1),
        token = PlaceWriteSupport.token(environment());
    for (var side : SharedHarness.Side.values()) {
      String base = side == SharedHarness.Side.V2 ? v2Url() : v3Url();
      var responses =
          DatabaseRequestRace.run(
              environment(),
              side,
              existing ? "place_reviews" : "places",
              existing ? "eeeeeeee-eeee-4eee-8eee-000000000001" : place,
              "place_reviews",
              () ->
                  request(
                      base,
                      "/api/places/" + place + "/reviews",
                      HttpMethod.POST,
                      token,
                      Map.of("rating", 4, "content", "race review")));
      assertThat(responses)
          .allSatisfy(
              r -> {
                assertThat(r.getStatusCode().value()).isEqualTo(201);
                assertThat(r.getBody().path("reviewCount").asInt()).isEqualTo(existing ? 2 : 1);
                assertThat(r.getBody().path("averageRating").asDouble())
                    .isEqualTo(existing ? 4.5 : 4);
              });
      try (var db = environment().connect(side);
          var q =
              db.prepareStatement(
                  "SELECT id,\"createdAt\",rating,content FROM place_reviews WHERE \"placeId\"=? AND \"userId\"=?")) {
        q.setString(1, place);
        q.setString(2, ReviewTestSupport.user(1));
        try (var r = q.executeQuery()) {
          assertThat(r.next()).isTrue();
          if (existing) {
            assertThat(r.getString(1)).isEqualTo("eeeeeeee-eeee-4eee-8eee-000000000001");
            assertThat(r.getTimestamp(2).toLocalDateTime())
                .isEqualTo(java.time.LocalDateTime.parse("2026-01-01T00:00:00"));
          } else assertThat(java.util.UUID.fromString(r.getString(1)).version()).isEqualTo(4);
          assertThat(r.getInt(3)).isEqualTo(4);
          assertThat(r.getString(4)).isEqualTo("race review");
          assertThat(r.next()).isFalse();
        }
      }
    }
    try (var left = environment().connect(SharedHarness.Side.V2);
        var right = environment().connect(SharedHarness.Side.V3)) {
      DatabaseSnapshot.compare(
          DatabaseSnapshot.capture(left, rules),
          DatabaseSnapshot.capture(right, rules),
          before.seedIds(),
          rules);
    }
  }
}
