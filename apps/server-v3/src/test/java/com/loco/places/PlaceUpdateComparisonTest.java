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
class PlaceUpdateComparisonTest extends CompatibilityTestBase {
  static java.util.stream.Stream<JsonNode> cases() throws Exception {
    try (var input =
        PlaceUpdateComparisonTest.class.getResourceAsStream("/places/update-v2.json")) {
      return java.util.stream.StreamSupport.stream(
          new com.fasterxml.jackson.databind.ObjectMapper().readTree(input).spliterator(), false);
    }
  }

  @ParameterizedTest
  @MethodSource("cases")
  void responseOldAndNewCellsAndDatabaseMatch(JsonNode expected) throws Exception {
    environment().reset("places/seed.sql");
    var rules = PlaceWriteSupport.rules();
    DatabaseSnapshot before;
    try (var db = environment().connect(SharedHarness.Side.V2)) {
      before = DatabaseSnapshot.capture(db, rules);
    }
    for (var side : SharedHarness.Side.values()) {
      SqlQueryCounter.reset();
      var input = mapper.readTree(expected.path("input").asText());
      var response =
          request(
              side == SharedHarness.Side.V2 ? v2Url() : v3Url(),
              expected.path("path").asText(),
              HttpMethod.PUT,
              PlaceWriteSupport.token(environment()),
              input);
      int status = response.getStatusCode().value();
      assertThat(status).as(expected.toString()).isEqualTo(expected.path("status").asInt());
      assertThat(PlaceWriteSupport.normalize(response.getBody())).isEqualTo(expected.get("body"));
      if (side == SharedHarness.Side.V3) {
        int budget =
            status == 400
                ? 1
                : status != 200 ? 2 : 6 + (expected.path("updated").asBoolean() ? 1 : 0);
        assertThat(SqlQueryCounter.statements()).as("update SQL").hasSize(budget);
      }
      try (var db = environment().connect(side)) {
        if (status >= 400) before.assertUnchanged(DatabaseSnapshot.capture(db, rules));
        else
          try (var q = db.prepareStatement("SELECT \"updatedAt\" FROM places WHERE id=?")) {
            q.setString(1, response.getBody().path("id").asText());
            try (var row = q.executeQuery()) {
              assertThat(row.next()).isTrue();
              assertThat(
                      row.getTimestamp(1)
                          .toLocalDateTime()
                          .isAfter(java.time.LocalDateTime.of(2026, 1, 1, 0, 0)))
                  .isEqualTo(expected.path("updated").asBoolean());
              assertThat(response.getBody().path("updatedAt").asText())
                  .isEqualTo(
                      com.loco.users.UserResponses.timestamp(
                          row.getTimestamp(1).toLocalDateTime()));
            }
          }
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
