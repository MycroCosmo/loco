package com.loco.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loco.compat.DatabaseSnapshot;
import java.util.List;
import java.util.Map;

final class PlaceWriteSupport {
  static DatabaseSnapshot.Rules rules() {
    return new DatabaseSnapshot.Rules(
        Map.of("map_members", List.of("__sort_map", "__sort_user")),
        Map.of(
            "map_members",
            "SELECT mm.*,m.title AS __sort_map,u.email AS __sort_user FROM map_members mm JOIN maps m ON mm.\"mapId\"=m.id JOIN users u ON mm.\"userId\"=u.id"));
  }

  static JsonNode normalize(JsonNode source) {
    var body = source.deepCopy();
    if (body instanceof ObjectNode obj && obj.has("id")) {
      if (!obj.path("id").asText().startsWith("cccccccc-")) {
        assertThat(obj.path("id").asText()).matches("[0-9a-f-]{36}");
        obj.put("id", "$id");
      }
      for (String key : List.of("createdAt", "updatedAt")) {
        java.time.Instant.parse(obj.path(key).asText());
        obj.put(key, "$time");
      }
    }
    return body;
  }

  static String token(com.loco.compat.SharedHarness.Environment env) throws Exception {
    return env.token(
        "valid",
        com.loco.compat.SharedHarness.Environment.USER_ID,
        com.loco.compat.SharedHarness.Environment.EMAIL);
  }

  static String createPath(int n) {
    return "/api/maps/" + PlaceObservationTest.map(n) + "/places";
  }

  static void compareCoordinateBoundary(
      DatabaseSnapshot left,
      DatabaseSnapshot right,
      java.util.Set<String> seedIds,
      JsonNode expected) {
    var rules = rules();
    double latitude = expected.path("body").path("latitude").asDouble(Double.NaN);
    if (expected.path("status").asInt() >= 400 || (latitude != 37.05 && latitude != -0.00001)) {
      compareCenters(left.normalized(seedIds, rules), right.normalized(seedIds, rules));
      return;
    }
    // E-5 changes only these boundary cells. All other tables and cells stay exact comparisons.
    var v2 = left.normalized(seedIds, rules);
    var v3 = right.normalized(seedIds, rules);
    var v2Cells = v2.get("place_marker_cells");
    var v3Cells = v3.get("place_marker_cells");
    var actualV2 = v2Cells.stream().filter(c -> c.path("latitude").asDouble() == latitude).toList();
    var actualV3 = v3Cells.stream().filter(c -> c.path("latitude").asDouble() == latitude).toList();
    var place =
        v3.get("places").stream()
            .filter(p -> p.path("latitude").asDouble() == latitude)
            .findFirst()
            .orElseThrow();
    String[] latKeys =
        latitude == 37.05
            ? new String[] {"37.1", "37.05", "37.050"}
            : new String[] {"0.0", "0.00", "0.000"};
    String[] lngKeys = {"127.9", "127.89", "127.889"};
    var required = new java.util.ArrayList<ObjectNode>();
    for (int p = 1; p <= 3; p++) {
      var cell = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
      cell.put("mapId", place.path("mapId").asText())
          .put("precision", p)
          .put("cellLatKey", latKeys[p - 1])
          .put("cellLngKey", lngKeys[p - 1])
          .put("latitude", latitude)
          .put("longitude", 127.8888)
          .put("minLat", latitude)
          .put("maxLat", latitude)
          .put("minLng", 127.8888)
          .put("maxLng", 127.8888)
          .put("count", 1)
          .put("exactKeyCount", 1)
          .put("groupKey", (latitude == 37.05 ? "37.050000" : "-0.000010") + ":127.888800")
          .put("placeId", place.path("id").asText())
          .put("placeName", place.path("name").asText())
          .put("updatedAt", "$timestamp");
      required.add(cell);
    }
    assertThat(actualV3)
        .as("E-5 complete corrected cells")
        .containsExactlyInAnyOrderElementsOf(required);
    var historical = latitude == 37.05 ? required.subList(1, 3) : List.<ObjectNode>of();
    assertThat(actualV2)
        .as("Preserved v2 missing-cell observation")
        .containsExactlyInAnyOrderElementsOf(historical);
    v2Cells.removeAll(actualV2);
    v3Cells.removeAll(actualV3);
    compareCenters(v2, v3);
  }

  static void compareCenters(
      Map<String, java.util.List<ObjectNode>> expected,
      Map<String, java.util.List<ObjectNode>> actual) {
    var order =
        java.util.Comparator.comparing((ObjectNode c) -> c.path("mapId").asText())
            .thenComparingInt(c -> c.path("precision").asInt())
            .thenComparing(c -> c.path("cellLatKey").asText())
            .thenComparing(c -> c.path("cellLngKey").asText());
    var left = expected.get("place_marker_cells");
    var right = actual.get("place_marker_cells");
    left.sort(order);
    right.sort(order);
    assertThat(right.size())
        .as("E-20 does not permit missing or extra cells")
        .isEqualTo(left.size());
    for (int i = 0; i < left.size(); i++)
      for (String key : java.util.List.of("latitude", "longitude")) {
        assertThat(right.get(i).path(key).isNumber()).isTrue();
        assertThat(right.get(i).path(key).asDouble())
            .as("E-20 center only: " + key)
            .isFinite()
            .isCloseTo(
                left.get(i).path(key).asDouble(), org.assertj.core.data.Offset.offset(1e-10));
        right.get(i).set(key, left.get(i).get(key));
      }
    if (!actual.equals(expected))
      throw new AssertionError(
          "E-20 permits only center deltas; all keys/counts/extents/other DB fields must match exactly");
  }
}
