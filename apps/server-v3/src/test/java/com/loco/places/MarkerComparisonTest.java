package com.loco.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.loco.compat.*;
import com.loco.maps.SqlQueryCounter;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
@Import(SqlQueryCounter.Configuration.class)
class MarkerComparisonTest extends MarkerTestSupport {
  static JsonNode fixture(String name) throws Exception {
    try (var in = MarkerComparisonTest.class.getResourceAsStream("/markers/" + name + "-v2.json")) {
      return new com.fasterxml.jackson.databind.ObjectMapper().readTree(in);
    }
  }

  static java.util.stream.Stream<Arguments> boundaries() throws Exception {
    var result = new ArrayList<Arguments>();
    for (String api : List.of("markers", "marker-group", "marker-cluster"))
      for (var row : fixture(api)) result.add(Arguments.of(api, row));
    return result.stream();
  }

  @ParameterizedTest
  @MethodSource("boundaries")
  void inputsAndPermissions(String api, JsonNode row) throws Exception {
    seed("basic");
    compare(row, "basic");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"mixed", "threshold300", "threshold301", "missing-cells", "stale", "overflow"})
  void selectionAndResponseBranches(String scenario) throws Exception {
    seed(scenario);
    for (var row : fixture(scenario)) compare(row, scenario);
  }

  @Test
  void realWritesPreserveTheKnownMissingCells() throws Exception {
    seed("basic");
    var fixtures = fixture("rounding");
    int offset = 0;
    for (String lat : List.of("37.05", "-0.00001")) {
      for (var side : SharedHarness.Side.values()) {
        var created =
            request(
                side == SharedHarness.Side.V2 ? v2Url() : v3Url(),
                PlaceWriteSupport.createPath(1),
                HttpMethod.POST,
                PlaceWriteSupport.token(environment()),
                mapper.readTree(
                    "{\"name\":\"rounding\",\"address\":\"edge\",\"latitude\":"
                        + lat
                        + ",\"longitude\":0}"));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        String id = created.getBody().path("id").asText(),
            fixed = PlaceObservationTest.place(lat.equals("37.05") ? 80 : 81);
        try (var db = environment().connect(side)) {
          for (String sql :
              List.of(
                  "UPDATE places SET id=? WHERE id=?",
                  "UPDATE place_marker_cells SET \"placeId\"=? WHERE \"placeId\"=?"))
            try (var q = db.prepareStatement(sql)) {
              q.setString(1, fixed);
              q.setString(2, id);
              q.executeUpdate();
            }
        }
      }
      for (int i = 0; i < 6; i++) compare(fixtures.get(offset++), "basic");
    }
  }

  void compare(JsonNode row, String scenario) throws Exception {
    String path = row.path("path").asText(),
        token =
            environment()
                .token(
                    "valid",
                    ReviewTestSupport.user(row.path("user").asInt()),
                    "ignored@example.test");
    for (var side : SharedHarness.Side.values())
      try (var db = environment().connect(side)) {
        var before = DatabaseSnapshot.capture(db, PlaceWriteSupport.rules());
        SqlQueryCounter.reset();
        var r =
            request(
                side == SharedHarness.Side.V2 ? v2Url() : v3Url(),
                path,
                HttpMethod.GET,
                token,
                null);
        assertThat(r.getStatusCode().value())
            .as("%s %s", side, path)
            .isEqualTo(row.path("status").asInt());
        JsonComparison.compare(row.get("body"), r.getBody(), Map.of());
        if (side == SharedHarness.Side.V3)
          assertThat(SqlQueryCounter.statements()).as(path).hasSize(sqlCount(row, scenario));
        before.assertUnchanged(DatabaseSnapshot.capture(db, PlaceWriteSupport.rules()));
      }
  }

  static int sqlCount(JsonNode row, String scenario) {
    int status = row.path("status").asInt();
    String path = row.path("path").asText(), message = row.path("body").path("message").asText();
    if (status == 404) return 2;
    if (status == 403) return 3;
    if (status == 400 && (message.startsWith("level") || message.startsWith("precision"))) return 1;
    int permission =
        (path.contains(PlaceObservationTest.map(2)) || path.contains(PlaceObservationTest.map(3)))
                && row.path("user").asInt() != 2
            ? 1
            : 0;
    if (status != 200) return 2 + permission;
    if (path.contains("marker-cluster")) {
      var body = row.get("body");
      return permission
          + (body.path("count").asInt() == 0 ? 3 : body.path("items").isEmpty() ? 4 : 5);
    }
    boolean fallback =
        scenario.equals("threshold301")
            && (path.contains("level=1") && !path.contains("level=11") && !path.contains("level=14")
                || path.endsWith("level=5"));
    return 3 + permission + (fallback ? 1 : 0);
  }
}
