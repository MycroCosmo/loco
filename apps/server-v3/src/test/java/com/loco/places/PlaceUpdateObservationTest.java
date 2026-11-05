package com.loco.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.loco.compat.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
class PlaceUpdateObservationTest extends CompatibilityTestBase {
  @Test
  void capturesPartialUpdateAndNullBehavior() throws Exception {
    var inputs =
        new ArrayList<>(
            List.of(
                "{}",
                "[]",
                "[1]",
                "{\"unknown\":1}",
                "{\"name\":\"Alpha\"}",
                "{\"name\":\"renamed\"}",
                "{\"name\":\"\",\"address\":\"\"}",
                PlaceCreateObservationTest.MINIMAL,
                "{\"name\":1,\"address\":false,\"latitude\":\"1\",\"longitude\":181,\"tags\":[1]}",
                "{\"latitude\":38,\"longitude\":128}",
                "{\"latitude\":37.44441}",
                "{\"latitude\":37.4499}",
                "{\"latitude\":37.05}",
                "{\"latitude\":-0.00001}",
                "{\"latitude\":0,\"longitude\":-0.0}",
                "{\"tags\":[\"new\",\"new\"]}",
                "{\"tags\":[]}",
                "{\"phone\":\"phone\",\"category\":\"cafe\",\"memo\":\"memo\",\"roadAddress\":\"road\"}"));
    for (String field :
        List.of(
            "name",
            "address",
            "roadAddress",
            "latitude",
            "longitude",
            "phone",
            "category",
            "memo",
            "tags")) inputs.add("{\"" + field + "\":null}");
    var rows = mapper.createArrayNode();
    for (String input : inputs) observe(rows, PlaceObservationTest.place(1), input);
    for (int n : new int[] {4, 6, 7})
      observe(rows, PlaceObservationTest.place(n), "{\"name\":\"renamed\"}");
    observe(rows, "missing", "{\"name\":\"renamed\"}");
    if (Boolean.getBoolean("places.observe")) {
      var path = Path.of("build/reports/places/update-v2.json");
      Files.createDirectories(path.getParent());
      mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), rows);
    } else
      try (var stream = getClass().getResourceAsStream("/places/update-v2.json")) {
        assertThat(rows).isEqualTo(mapper.readTree(stream));
      }
  }

  private void observe(com.fasterxml.jackson.databind.node.ArrayNode rows, String id, String input)
      throws Exception {
    environment().reset("places/seed.sql");
    var result =
        request(
            v2Url(),
            "/api/places/" + id,
            HttpMethod.PUT,
            PlaceWriteSupport.token(environment()),
            mapper.readTree(input));
    boolean changed = false;
    try (var db = environment().connect(SharedHarness.Side.V2);
        var q =
            db.prepareStatement(
                "SELECT \"updatedAt\"<>timestamp '2026-01-01' FROM places WHERE id=?")) {
      q.setString(1, id);
      try (var r = q.executeQuery()) {
        if (r.next()) changed = r.getBoolean(1);
      }
    }
    rows.addObject()
        .put("path", "/api/places/" + id)
        .put("input", input)
        .put("status", result.getStatusCode().value())
        .put("updated", changed)
        .set("body", PlaceWriteSupport.normalize(result.getBody()));
  }
}
