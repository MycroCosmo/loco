package com.loco.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loco.compat.*;
import java.util.*;

final class ReviewTestSupport {
  static String path(int place) {
    return "/api/places/" + PlaceObservationTest.place(place) + "/reviews";
  }

  static String user(int n) {
    return switch (n) {
      case 1 -> SharedHarness.Environment.USER_ID;
      case 2 -> "22222222-2222-4222-8222-222222222222";
      case 3 -> "33333333-3333-4333-8333-333333333333";
      default -> "44444444-4444-4444-8444-444444444444";
    };
  }

  static void seed(SharedHarness.Environment env) throws Exception {
    env.reset("places/seed.sql");
    for (var side : SharedHarness.Side.values()) {
      if (side == SharedHarness.Side.V2 && !env.v2Enabled()) continue;
      try (var db = env.connect(side)) {
        org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
            db, new org.springframework.core.io.ClassPathResource("reviews/extra-seed.sql"));
      }
    }
  }

  static JsonNode normalize(JsonNode source) {
    var result = source.deepCopy();
    normalizeInPlace(result);
    return result;
  }

  private static void normalizeInPlace(JsonNode n) {
    if (n instanceof ObjectNode obj) {
      if (obj.has("rating") && obj.has("userId") && obj.has("placeId")) {
        assertThat(obj.path("id").asText()).matches("[0-9a-f-]{36}");
        if (!obj.path("id").asText().startsWith("eeeeeeee-"))
          obj.put(
              "id", "$review:" + obj.path("placeId").asText() + ":" + obj.path("userId").asText());
      }
      for (String key : List.of("createdAt", "updatedAt"))
        if (obj.has(key)) {
          java.time.Instant.parse(obj.path(key).asText());
          obj.put(key, "$time");
        }
    }
    n.forEach(ReviewTestSupport::normalizeInPlace);
  }
}
