package com.loco.favorites;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loco.compat.*;
import java.nio.file.*;
import java.util.*;

public abstract class SubmissionTestSupport extends CompatibilityTestBase {
  static String map(int n) {
    return "aaaaaaaa-aaaa-4aaa-8aaa-%012d".formatted(n);
  }

  static String favorite(int n) {
    return "ffffffff-ffff-4fff-8fff-%012d".formatted(n);
  }

  static String user(int n) {
    return ("%d".formatted(n)).repeat(8)
        + "-"
        + ("%d".formatted(n)).repeat(4)
        + "-4"
        + ("%d".formatted(n)).repeat(3)
        + "-8"
        + ("%d".formatted(n)).repeat(3)
        + "-"
        + ("%d".formatted(n)).repeat(12);
  }

  void seed() throws Exception {
    environment().reset("places/seed.sql");
    for (var side : SharedHarness.Side.values())
      try (var db = environment().connect(side);
          var q = db.createStatement()) {
        q.execute(
            "INSERT INTO favorites(id,\"userId\",\"mapId\",\"createdAt\") VALUES ('"
                + favorite(1)
                + "','"
                + user(1)
                + "','"
                + map(2)
                + "','2026-01-02'),('"
                + favorite(2)
                + "','"
                + user(1)
                + "','"
                + map(4)
                + "','2026-01-03'),('"
                + favorite(3)
                + "','"
                + user(2)
                + "','"
                + map(1)
                + "','2026-01-01')");
      }
  }

  static JsonNode normalize(JsonNode source) {
    var copy = source.deepCopy();
    normalizeInPlace(copy);
    return copy;
  }

  private static void normalizeInPlace(JsonNode n) {
    if (n instanceof ObjectNode o) {
      if (o.has("id")
          && o.path("id").asText().matches("[0-9a-f-]{36}")
          && !o.path("id")
              .asText()
              .matches("(?:ffffffff|aaaaaaaa|11111111|22222222|33333333|44444444|cccccccc)-.*"))
        o.put("id", "$id");
      for (String key : List.of("createdAt", "updatedAt"))
        if (o.has(key) && !o.get(key).isNull()) {
          java.time.Instant.parse(o.get(key).asText());
          o.put(key, "$time");
        }
    }
    n.forEach(SubmissionTestSupport::normalizeInPlace);
  }

  static DatabaseSnapshot.Rules rules() {
    return new DatabaseSnapshot.Rules(
        Map.of("map_members", List.of("__map", "__user"), "favorites", List.of("__map", "__user")),
        Map.of(
            "map_members",
            "SELECT mm.*,m.title AS __map,u.email AS __user FROM map_members mm JOIN maps m ON m.id=mm.\"mapId\" JOIN users u ON u.id=mm.\"userId\"",
            "favorites",
            "SELECT f.*,m.title AS __map,u.email AS __user FROM favorites f JOIN maps m ON m.id=f.\"mapId\" JOIN users u ON u.id=f.\"userId\""));
  }

  JsonNode rows(SharedHarness.Side side) throws Exception {
    var result = mapper.createObjectNode();
    try (var db = environment().connect(side);
        var q = db.createStatement()) {
      for (String table : List.of("favorites", "feedbacks", "reports")) {
        String order =
            table.equals("favorites")
                ? "\"mapId\",\"userId\""
                : table.equals("feedbacks")
                    ? "content,\"userId\""
                    : "\"targetType\",\"targetId\",reason,detail";
        try (var r =
            q.executeQuery(
                "SELECT coalesce(jsonb_agg(x),'[]'::jsonb)::text FROM (SELECT * FROM "
                    + table
                    + " ORDER BY "
                    + order
                    + ")x")) {
          r.next();
          var items = mapper.readTree(r.getString(1));
          for (var item : items) {
            var o = (ObjectNode) item;
            String time = o.path("createdAt").asText();
            java.time.LocalDateTime.parse(time);
            o.put("createdAt", "$time");
            String id = o.path("id").asText();
            assertThat(id).matches("[0-9a-f-]{36}");
            if (!id.startsWith("ffffffff-")) o.put("id", "$id");
          }
          result.set(table, items);
        }
      }
    }
    return result;
  }

  void record(String name, JsonNode value) throws Exception {
    var p = Path.of("build/reports/submissions/" + name);
    Files.createDirectories(p.getParent());
    mapper.writerWithDefaultPrettyPrinter().writeValue(p.toFile(), value);
    if (!Boolean.getBoolean("submissions.observe"))
      try (var in = getClass().getResourceAsStream("/submissions/" + name)) {
        assertThat(value).isEqualTo(mapper.readTree(in));
      }
  }
}
