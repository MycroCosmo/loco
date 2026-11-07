package com.loco.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.loco.compat.*;
import com.loco.maps.SqlQueryCounter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
@Import(SqlQueryCounter.Configuration.class)
class ReviewComparisonTest extends CompatibilityTestBase {
  static java.util.stream.Stream<JsonNode> cases() throws Exception {
    try (var in = ReviewComparisonTest.class.getResourceAsStream("/reviews/http-v2.json")) {
      return java.util.stream.StreamSupport.stream(
          new com.fasterxml.jackson.databind.ObjectMapper().readTree(in).spliterator(), false);
    }
  }

  @ParameterizedTest
  @MethodSource("cases")
  void responseAndEntireDatabaseMatch(JsonNode expected) throws Exception {
    ReviewTestSupport.seed(environment());
    var rules = PlaceWriteSupport.rules();
    DatabaseSnapshot before;
    try (var db = environment().connect(SharedHarness.Side.V2)) {
      before = DatabaseSnapshot.capture(db, rules);
    }
    String path = expected.path("path").asText(), method = expected.path("method").asText();
    int user = expected.path("user").asInt(), status = expected.path("status").asInt();
    for (var side : SharedHarness.Side.values()) {
      SqlQueryCounter.reset();
      var response =
          request(
              side == SharedHarness.Side.V2 ? v2Url() : v3Url(),
              path,
              HttpMethod.valueOf(method),
              environment().token("valid", ReviewTestSupport.user(user), "ignored@example.test"),
              expected.get("input").isNull()
                  ? null
                  : mapper.readTree(expected.path("input").asText()));
      assertThat(response.getStatusCode().value()).as(expected.toString()).isEqualTo(status);
      assertThat(ReviewTestSupport.normalize(response.getBody())).isEqualTo(expected.get("body"));
      if (side == SharedHarness.Side.V3 && !method.equals("GET")) {
        boolean privateNonOwner =
            (path.contains(PlaceObservationTest.place(6))
                    || path.contains(PlaceObservationTest.place(7)))
                && user != 2;
        boolean owner =
            path.contains(PlaceObservationTest.place(6))
                    || path.contains(PlaceObservationTest.place(7))
                ? user == 2
                : user == 1;
        int budget =
            status == 400
                ? 1
                : status == 404
                    ? 2
                    : status == 403
                        ? 3
                        : 6 + (privateNonOwner ? 2 : method.equals("POST") && !owner ? 1 : 0);
        assertThat(SqlQueryCounter.statements()).as(path + " SQL").hasSize(budget);
      }
      try (var db = environment().connect(side)) {
        var after = DatabaseSnapshot.capture(db, rules);
        if (status >= 400 || method.equals("GET")) before.assertUnchanged(after);
        else {
          var oldRows = before.normalized(before.seedIds(), rules);
          var newRows = after.normalized(before.seedIds(), rules);
          oldRows.remove("place_reviews");
          newRows.remove("place_reviews");
          assertThat(newRows).isEqualTo(oldRows);
          try (var q = db.createStatement();
              var rows = q.executeQuery("SELECT id,\"createdAt\",\"updatedAt\" FROM places")) {
            while (rows.next())
              assertThat(rows.getTimestamp("updatedAt").toLocalDateTime())
                  .isEqualTo(java.time.LocalDateTime.parse("2026-01-01T00:00:00"));
          }
          if (response.getBody().has("reviews"))
            for (var review : response.getBody().get("reviews")) {
              try (var q =
                  db.prepareStatement(
                      "SELECT \"createdAt\",\"updatedAt\" FROM place_reviews WHERE id=?")) {
                q.setString(1, review.path("id").asText());
                try (var row = q.executeQuery()) {
                  assertThat(row.next()).isTrue();
                  for (String key : java.util.List.of("createdAt", "updatedAt"))
                    assertThat(review.path(key).asText())
                        .isEqualTo(
                            com.loco.users.UserResponses.timestamp(
                                row.getTimestamp(key).toLocalDateTime()));
                  if (review.path("id").asText().startsWith("eeeeeeee-"))
                    assertThat(row.getTimestamp("createdAt").toLocalDateTime())
                        .isEqualTo(java.time.LocalDateTime.parse("2026-01-01T00:00:00"));
                }
              }
            }
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
