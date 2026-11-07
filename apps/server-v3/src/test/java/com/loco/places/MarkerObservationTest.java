package com.loco.places;

import com.loco.compat.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
class MarkerObservationTest extends MarkerTestSupport {
  @ParameterizedTest
  @ValueSource(strings = {"markers", "marker-group", "marker-cluster"})
  void boundaries(String api) throws Exception {
    seed("basic");
    var rows = mapper.createArrayNode();
    for (String query : queries(api)) observe(rows, path(api) + "?" + query, 1);
    String valid =
        switch (api) {
          case "markers" -> BOUNDS;
          case "marker-group" -> "lat=37.4444&lng=127.8888";
          default -> "precision=3&cellLatKey=37.444&cellLngKey=127.889";
        };
    for (int map : new int[] {2, 3, 4})
      for (int user : new int[] {1, 2, 3})
        observe(
            rows,
            path(api).replace(PlaceObservationTest.map(1), PlaceObservationTest.map(map))
                + "?"
                + valid,
            user);
    observe(rows, path(api).replace(PlaceObservationTest.map(1), "missing") + "?" + valid, 1);
    observe(
        rows,
        path(api).replace(PlaceObservationTest.map(1), "missing") + "?level=bad&precision=bad",
        1);
    observe(
        rows,
        path(api).replace(PlaceObservationTest.map(1), PlaceObservationTest.map(3))
            + "?level=bad&precision=bad",
        1);
    record(api + "-v2.json", rows);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"mixed", "threshold300", "threshold301", "missing-cells", "stale", "overflow"})
  void branches(String scenario) throws Exception {
    seed(scenario);
    var rows = mapper.createArrayNode();
    for (int level : new int[] {1, 5, 6, 9, 11, 14})
      observe(rows, path("markers") + "?" + BOUNDS + "&level=" + level, 1);
    observe(
        rows, path("markers") + "?swLat=37.444401&neLat=37.444402&swLng=127&neLng=128&level=6", 1);
    observe(
        rows,
        path("marker-cluster") + "?precision=3&cellLatKey=37.444&cellLngKey=127.889&limit=2",
        1);
    record(scenario + "-v2.json", rows);
  }

  @Test
  void missingRoundingCells() throws Exception {
    seed("basic");
    var rows = mapper.createArrayNode();
    for (String lat : List.of("37.05", "-0.00001")) {
      var created =
          request(
              v2Url(),
              PlaceWriteSupport.createPath(1),
              HttpMethod.POST,
              PlaceWriteSupport.token(environment()),
              mapper.readTree(
                  "{\"name\":\"rounding\",\"address\":\"edge\",\"latitude\":"
                      + lat
                      + ",\"longitude\":0}"));
      org.assertj.core.api.Assertions.assertThat(created.getStatusCode().value()).isEqualTo(201);
      // 고정 ID는 응답 비교를 위한 치환이며 장소/셀의 수치 값은 그대로 둔다.
      String id = created.getBody().path("id").asText();
      try (var db = environment().connect(SharedHarness.Side.V2);
          var q = db.prepareStatement("UPDATE places SET id=? WHERE id=?")) {
        q.setString(1, PlaceObservationTest.place(lat.equals("37.05") ? 80 : 81));
        q.setString(2, id);
        q.executeUpdate();
      }
      try (var db = environment().connect(SharedHarness.Side.V2);
          var q =
              db.prepareStatement(
                  "UPDATE place_marker_cells SET \"placeId\"=? WHERE \"placeId\"=?")) {
        q.setString(1, PlaceObservationTest.place(lat.equals("37.05") ? 80 : 81));
        q.setString(2, id);
        q.executeUpdate();
      }
      for (int level : new int[] {5, 6, 9, 11})
        observe(rows, path("markers") + "?swLat=-1&neLat=38&swLng=-1&neLng=1&level=" + level, 1);
      for (String key : lat.equals("37.05") ? List.of("37.0", "37.1") : List.of("-0.0", "0.0"))
        observe(
            rows, path("marker-cluster") + "?precision=1&cellLatKey=" + key + "&cellLngKey=0.0", 1);
    }
    record("rounding-v2.json", rows);
  }

  void observe(com.fasterxml.jackson.databind.node.ArrayNode rows, String path, int user)
      throws Exception {
    var r =
        request(
            v2Url(),
            path,
            HttpMethod.GET,
            environment().token("valid", ReviewTestSupport.user(user), "ignored@example.test"),
            null);
    rows.addObject()
        .put("path", path)
        .put("user", user)
        .put("status", r.getStatusCode().value())
        .set("body", r.getBody());
  }
}
