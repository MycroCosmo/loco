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
class PlaceCreateComparisonTest extends CompatibilityTestBase {
  static java.util.stream.Stream<JsonNode> cases() throws Exception {
    try (var input =
        PlaceCreateComparisonTest.class.getResourceAsStream("/places/create-v2.json")) {
      return java.util.stream.StreamSupport.stream(
          new com.fasterxml.jackson.databind.ObjectMapper().readTree(input).spliterator(), false);
    }
  }

  @ParameterizedTest
  @MethodSource("cases")
  void responseCellsAndDatabaseMatch(JsonNode expected) throws Exception {
    environment().reset("places/seed.sql");
    var rules = PlaceWriteSupport.rules();
    DatabaseSnapshot before;
    try (var db = environment().connect(SharedHarness.Side.V2)) {
      before = DatabaseSnapshot.capture(db, rules);
    }
    for (var side : SharedHarness.Side.values()) {
      SqlQueryCounter.reset();
      var response =
          request(
              side == SharedHarness.Side.V2 ? v2Url() : v3Url(),
              expected.path("path").asText(),
              HttpMethod.POST,
              PlaceWriteSupport.token(environment()),
              mapper.readTree(expected.path("input").asText()));
      assertThat(response.getStatusCode().value())
          .as(expected.toString())
          .isEqualTo(expected.path("status").asInt());
      assertThat(PlaceWriteSupport.normalize(response.getBody())).isEqualTo(expected.get("body"));
      if (response.getBody().has("id"))
        try (var db = environment().connect(side);
            var q =
                db.prepareStatement("SELECT \"createdAt\",\"updatedAt\" FROM places WHERE id=?")) {
          q.setString(1, response.getBody().path("id").asText());
          try (var row = q.executeQuery()) {
            assertThat(row.next()).isTrue();
            for (String key : java.util.List.of("createdAt", "updatedAt"))
              assertThat(response.getBody().path(key).asText())
                  .isEqualTo(
                      com.loco.users.UserResponses.timestamp(
                          row.getTimestamp(key).toLocalDateTime()));
          }
        }
      if (side == SharedHarness.Side.V3) {
        int status = response.getStatusCode().value();
        int budget =
            status == 400
                ? 1
                : status == 404
                    ? 2
                    : status == 403
                        ? 3
                        : expected.path("path").asText().equals(PlaceWriteSupport.createPath(2))
                            ? 8
                            : 7;
        assertThat(SqlQueryCounter.statements()).as("create SQL").hasSize(budget);
      }
      if (response.getStatusCode().isError())
        try (var db = environment().connect(side)) {
          before.assertUnchanged(DatabaseSnapshot.capture(db, rules));
        }
    }
    try (var left = environment().connect(SharedHarness.Side.V2);
        var right = environment().connect(SharedHarness.Side.V3)) {
      PlaceWriteSupport.compareCoordinateBoundary(
          DatabaseSnapshot.capture(left, rules),
          DatabaseSnapshot.capture(right, rules),
          before.seedIds(),
          expected);
    }
  }
}
