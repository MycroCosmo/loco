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
}
