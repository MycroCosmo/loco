package com.loco.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.loco.compat.*;
import com.loco.maps.SqlQueryCounter;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
@Import(SqlQueryCounter.Configuration.class)
class MarkerEdgeComparisonTest extends MarkerTestSupport {
  @Test
  void groupDoesNotApplyListPagination() throws Exception {
    seed("threshold60");
    var left =
        request(
            v2Url(),
            path("marker-group") + "?lat=37.4444&lng=127.8888&limit=1&cursor=missing",
            HttpMethod.GET,
            PlaceWriteSupport.token(environment()),
            null);
    SqlQueryCounter.reset();
    var right =
        request(
            v3Url(),
            path("marker-group") + "?lat=37.4444&lng=127.8888&limit=1&cursor=missing",
            HttpMethod.GET,
            PlaceWriteSupport.token(environment()),
            null);
    assertThat(left.getStatusCode().value()).isEqualTo(200);
    assertThat(right.getStatusCode()).isEqualTo(left.getStatusCode());
    assertThat(left.getBody().size()).isEqualTo(60);
    JsonComparison.compare(left.getBody(), right.getBody(), Map.of());
    assertThat(SqlQueryCounter.statements()).hasSize(3);
  }

  @Test
  void storedCellCountSurvivesWhenActualPlacesAreMissing() throws Exception {
    seed("basic");
    for (var side : SharedHarness.Side.values())
      try (var db = environment().connect(side);
          var q = db.createStatement()) {
        q.execute(
            "DELETE FROM place_reviews; DELETE FROM places WHERE \"mapId\"='"
                + PlaceObservationTest.map(1)
                + "'");
      }
    var left =
        request(
            v2Url(),
            path("marker-cluster") + "?precision=3&cellLatKey=37.444&cellLngKey=127.889",
            HttpMethod.GET,
            PlaceWriteSupport.token(environment()),
            null);
    SqlQueryCounter.reset();
    var right =
        request(
            v3Url(),
            path("marker-cluster") + "?precision=3&cellLatKey=37.444&cellLngKey=127.889",
            HttpMethod.GET,
            PlaceWriteSupport.token(environment()),
            null);
    assertThat(left.getStatusCode().value()).isEqualTo(200);
    assertThat(right.getStatusCode()).isEqualTo(left.getStatusCode());
    assertThat(left.getBody())
        .isEqualTo(mapper.readTree("{\"count\":4,\"items\":[],\"hasMore\":false}"));
    JsonComparison.compare(left.getBody(), right.getBody(), Map.of());
    assertThat(SqlQueryCounter.statements()).hasSize(4);
  }

  @ParameterizedTest
  @ValueSource(ints = {699, 700, 701})
  void overflowBoundary(int cells) throws Exception {
    seed("overflow");
    for (var side : SharedHarness.Side.values())
      try (var db = environment().connect(side);
          var q = db.createStatement()) {
        q.execute("DELETE FROM place_marker_cells WHERE count>" + cells);
      }
    var bodies = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
    for (var side : SharedHarness.Side.values()) {
      SqlQueryCounter.reset();
      var r =
          request(
              side == SharedHarness.Side.V2 ? v2Url() : v3Url(),
              path("markers") + "?" + BOUNDS + "&level=6",
              HttpMethod.GET,
              PlaceWriteSupport.token(environment()),
              null);
      assertThat(r.getStatusCode().value()).isEqualTo(200);
      var body = r.getBody();
      bodies.add(body);
      assertThat(body.size()).isEqualTo(Math.min(700, cells));
      var last = body.get(body.size() - 1);
      if (cells >= 700) {
        assertThat(last.path("id").asText()).isEqualTo("overflow");
        assertThat(last.path("count").asInt()).isEqualTo(cells == 700 ? 1 : 3);
        assertThat(last.has("precision")).isFalse();
      } else assertThat(last.path("id").asText()).isNotEqualTo("overflow");
      if (side == SharedHarness.Side.V3) assertThat(SqlQueryCounter.statements()).hasSize(3);
    }
    compareCellResponses(bodies.get(0), bodies.get(1));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "markers?swLat=-90&neLat=90&swLng=-180&neLng=180&level=5",
        "markers?swLat=-90&neLat=90&swLng=-180&neLng=180&level=6",
        "marker-group?groupKey=37.000000:0.000000",
        "marker-group?groupKey=37.000001:0.000000",
        "marker-group?groupKey=-0.000000:0.000000",
        "marker-group?lat=-0.00000001&lng=-0.0",
        "marker-group?lat=37.0000005&lng=0",
        "markers?swLat=37.0000005&neLat=37.0000005&swLng=0&neLng=0&level=5"
      })
  void floatingPointAndGroupRangeEdges(String api) throws Exception {
    seed("basic");
    for (var side : SharedHarness.Side.values())
      try (var db = environment().connect(side);
          var q = db.createStatement()) {
        q.execute(
            "UPDATE places SET latitude=CASE id WHEN '"
                + PlaceObservationTest.place(1)
                + "' THEN 37.00000049 WHEN '"
                + PlaceObservationTest.place(2)
                + "' THEN 37.0000005 WHEN '"
                + PlaceObservationTest.place(3)
                + "' THEN -0.00000001 ELSE 0 END,longitude=0 WHERE \"mapId\"='"
                + PlaceObservationTest.map(1)
                + "'");
        q.execute("DELETE FROM place_marker_cells");
        org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
            db, new org.springframework.core.io.ClassPathResource("markers/cells.sql"));
      }
    var v2 =
        request(v2Url(), path(api), HttpMethod.GET, PlaceWriteSupport.token(environment()), null);
    assertThat(v2.getStatusCode().value()).isEqualTo(200);
    SqlQueryCounter.reset();
    var v3 =
        request(v3Url(), path(api), HttpMethod.GET, PlaceWriteSupport.token(environment()), null);
    assertThat(v3.getStatusCode()).isEqualTo(v2.getStatusCode());
    if (api.equals("markers?swLat=-90&neLat=90&swLng=-180&neLng=180&level=5")) {
      // E-5: negative zero joins zero; the positive six-digit half boundary rounds up in PG.
      assertThat(v2.getBody().get(0).path("type").asText()).isEqualTo("place");
      assertThat(v2.getBody().get(1).path("groupKey").asText()).isEqualTo("37.000000:0.000000");
      String expected =
          "[{\"type\":\"group\",\"id\":\"0.000000:0.000000\",\"name\":\"2개 장소\",\"latitude\":-0.000000005,\"longitude\":0,\"count\":2,\"groupKey\":\"0.000000:0.000000\"},"
              + "{\"type\":\"place\",\"id\":\""
              + PlaceObservationTest.place(1)
              + "\",\"name\":\"Alpha\",\"latitude\":37.00000049,\"longitude\":0,\"count\":1},"
              + "{\"type\":\"place\",\"id\":\""
              + PlaceObservationTest.place(2)
              + "\",\"name\":\"Beta\",\"latitude\":37.0000005,\"longitude\":0,\"count\":1}]";
      JsonComparison.compare(mapper.readTree(expected), v3.getBody(), Map.of());
    } else if (api.equals("marker-group?lat=37.0000005&lng=0")) {
      assertThat(v2.getBody().get(0).path("id").asText()).isEqualTo(PlaceObservationTest.place(1));
      var canonical =
          request(
              v2Url(),
              path("marker-group?groupKey=37.000001:0.000000"),
              HttpMethod.GET,
              PlaceWriteSupport.token(environment()),
              null);
      JsonComparison.compare(canonical.getBody(), v3.getBody(), Map.of());
      assertThat(v3.getBody().size()).isEqualTo(1);
      assertThat(v3.getBody().get(0).path("id").asText()).isEqualTo(PlaceObservationTest.place(2));
    } else if (api.equals("markers?swLat=-90&neLat=90&swLng=-180&neLng=180&level=6")) {
      compareCellResponses(v2.getBody(), v3.getBody());
    } else JsonComparison.compare(v2.getBody(), v3.getBody(), Map.of());
    assertThat(SqlQueryCounter.statements()).hasSize(3);
  }
}
