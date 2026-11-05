package com.loco.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.loco.compat.CompatibilityTestBase;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
class PlaceCreateObservationTest extends CompatibilityTestBase {
  static final String MINIMAL =
      "{\"name\":\"new-place\",\"address\":\"address\",\"latitude\":37.4444,\"longitude\":127.8888}";

  @Test
  void capturesCreateInputsAndPermissions() throws Exception {
    var cases = new ArrayList<JsonNode>();
    for (String input :
        List.of(
            "{}",
            "[]",
            "[1]",
            "{\"unknown\":1}",
            MINIMAL,
            "{\"name\":null,\"address\":null,\"latitude\":null,\"longitude\":null}",
            "{\"name\":1,\"address\":1,\"roadAddress\":1,\"latitude\":\"1\",\"longitude\":true,\"phone\":1,\"category\":1,\"memo\":1,\"tags\":[1]}"))
      cases.add(mapper.readTree(input));
    for (String key : List.of("roadAddress", "phone", "category", "memo", "tags"))
      for (String value : List.of("null", "1", "\"text\"", "[]")) {
        var body = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(MINIMAL);
        body.set(key, mapper.readTree(value));
        cases.add(body);
      }
    for (double coordinate :
        new double[] {
          0, -0.0, -0.00001, 37.05, 37.44441, 37.44449, 37.4499, 90, -90, 90.1, -90.1
        }) {
      var body = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(MINIMAL);
      body.put("latitude", coordinate);
      cases.add(body);
    }
    cases.add(
        mapper
            .readTree(MINIMAL)
            .deepCopy()
            .withObject("")
            .put("name", "x".repeat(101))
            .put("memo", "y".repeat(501)));
    cases.add(
        mapper
            .readTree(MINIMAL)
            .deepCopy()
            .withObject("")
            .put("name", "")
            .put("address", "")
            .put("longitude", 180));
    var rows = mapper.createArrayNode();
    for (var body : cases) observe(rows, PlaceWriteSupport.createPath(1), body);
    for (int n = 2; n <= 4; n++)
      observe(rows, PlaceWriteSupport.createPath(n), mapper.readTree(MINIMAL));
    observe(rows, "/api/maps/missing/places", mapper.readTree(MINIMAL));
    if (Boolean.getBoolean("places.observe")) {
      var path = Path.of("build/reports/places/create-v2.json");
      Files.createDirectories(path.getParent());
      mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), rows);
    } else
      try (var stream = getClass().getResourceAsStream("/places/create-v2.json")) {
        assertThat(rows).isEqualTo(mapper.readTree(stream));
      }
  }

  private void observe(
      com.fasterxml.jackson.databind.node.ArrayNode rows, String path, JsonNode body)
      throws Exception {
    environment().reset("places/seed.sql");
    var response =
        request(v2Url(), path, HttpMethod.POST, PlaceWriteSupport.token(environment()), body);
    rows.addObject()
        .put("path", path)
        .put("input", body.toString())
        .put("status", response.getStatusCode().value())
        .set("body", PlaceWriteSupport.normalize(response.getBody()));
  }
}
