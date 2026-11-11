package com.loco.places;

import com.loco.compat.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

abstract class MarkerTestSupport extends CompatibilityTestBase {
  // Call only for cell-derived marker responses, never individual/group lookup APIs.
  static void compareCellResponses(
      com.fasterxml.jackson.databind.JsonNode expected,
      com.fasterxml.jackson.databind.JsonNode actual) {
    org.assertj.core.api.Assertions.assertThat(expected.isArray() && actual.isArray()).isTrue();
    org.assertj.core.api.Assertions.assertThat(actual.size()).isEqualTo(expected.size());
    var checked = actual.deepCopy();
    for (int i = 0; i < actual.size(); i++) {
      org.assertj.core.api.Assertions.assertThat(actual.get(i).isObject()).isTrue();
      for (String field : List.of("latitude", "longitude")) {
        var left = expected.get(i).path(field);
        var right = actual.get(i).path(field);
        org.assertj.core.api.Assertions.assertThat(left.isNumber() && right.isNumber()).isTrue();
        org.assertj.core.api.Assertions.assertThat(left.asDouble()).isFinite();
        org.assertj.core.api.Assertions.assertThat(right.asDouble())
            .isFinite()
            .isCloseTo(left.asDouble(), org.assertj.core.data.Offset.offset(1e-10));
        ((com.fasterxml.jackson.databind.node.ObjectNode) checked.get(i)).set(field, left);
      }
    }
    JsonComparison.compare(expected, checked, Map.of());
  }

  static String path(String api) {
    return "/api/maps/" + PlaceObservationTest.map(1) + "/places/" + api;
  }

  static final String BOUNDS = "swLat=-90&swLng=-180&neLat=90&neLng=180";

  void seed(String scenario) throws Exception {
    environment().reset("places/seed.sql");
    for (var side : SharedHarness.Side.values()) {
      if (side == SharedHarness.Side.V2 && !environment().v2Enabled()) continue;
      try (var db = environment().connect(side);
          var q = db.createStatement()) {
        if (scenario.startsWith("threshold")) {
          int n = Integer.parseInt(scenario.substring(9));
          q.execute(
              "DELETE FROM place_reviews; DELETE FROM places WHERE \"mapId\"='"
                  + PlaceObservationTest.map(1)
                  + "'");
          q.execute(
              "INSERT INTO places(id,\"mapId\",\"createdById\",name,address,latitude,longitude,tags,\"imageUrls\",\"createdAt\",\"updatedAt\") SELECT 'dddddddd-dddd-4ddd-8ddd-'||lpad(n::text,12,'0'),'"
                  + PlaceObservationTest.map(1)
                  + "','11111111-1111-4111-8111-111111111111','place-'||n,'address',37.4444,127.8888,ARRAY[]::text[],ARRAY[]::text[],'2026-01-01','2026-01-01' FROM generate_series(1,"
                  + n
                  + ")n");
        } else if (scenario.equals("mixed")) {
          q.execute(
              "UPDATE places SET latitude=CASE WHEN id='"
                  + PlaceObservationTest.place(3)
                  + "' THEN 37.444401 WHEN id='"
                  + PlaceObservationTest.place(4)
                  + "' THEN 38.1234 ELSE latitude END");
        }
        ScriptUtils.executeSqlScript(db, new ClassPathResource("markers/cells.sql"));
        if (scenario.equals("stale"))
          q.execute("UPDATE place_marker_cells SET count=99 WHERE precision=3");
        if (scenario.equals("missing-cells")) q.execute("DELETE FROM place_marker_cells");
        if (scenario.equals("overflow")) {
          q.execute("DELETE FROM place_marker_cells");
          q.execute(
              "INSERT INTO place_marker_cells(\"mapId\",precision,\"cellLatKey\",\"cellLngKey\",latitude,longitude,\"minLat\",\"maxLat\",\"minLng\",\"maxLng\",count,\"exactKeyCount\",\"updatedAt\") SELECT '"
                  + PlaceObservationTest.map(1)
                  + "',3,n::text,'0.000',n/1000.0,n/2000.0,n/1000.0,n/1000.0,n/2000.0,n/2000.0,n,2,'2026-01-01' FROM generate_series(1,701)n");
        }
      }
    }
  }

  static List<String> queries(String api) {
    var qs = new ArrayList<String>();
    if (api.equals("markers")) {
      qs.add(BOUNDS);
      for (String level :
          List.of(
              "",
              "0",
              "1",
              "5",
              "6",
              "8",
              "9",
              "10",
              "11",
              "14",
              "15",
              "5.5",
              "x",
              "5x",
              "NaN",
              "Infinity",
              "0x6",
              "6e0",
              "%20",
              "%EF%BB%BF6",
              "6&level=7",
              "%5B%5D")) qs.add(BOUNDS + "&level=" + level);
      for (String v :
          List.of(
              "",
              "NaN",
              "Infinity",
              "-Infinity",
              "x",
              "37x",
              "1e2",
              "0x10",
              "37&swLat=38",
              "%20%20",
              "-0.00001",
              "91")) qs.add("swLat=" + v + "&swLng=126&neLat=38&neLng=128");
      qs.add("");
      qs.add("swLat=38&neLat=36&swLng=128&neLng=126");
      qs.add(BOUNDS + "&tag=missing&search=missing&precision=1");
      qs.add(BOUNDS + "&level%5Bx%5D=6");
      qs.add("swLat%5Bx%5D=37&swLng=126&neLat=38&neLng=128");
      qs.add("swLat=37.4444&neLat=37.4444&swLng=127.8888&neLng=127.8888");
    } else if (api.equals("marker-group")) {
      qs.add("lat=37.4444&lng=127.8888");
      qs.add("");
      for (String key :
          List.of(
              "",
              "37.444400:127.888800",
              "%2037.444400%20:%20127.888800%20",
              "37:127:0",
              ":127",
              "91:0",
              "0:181",
              "NaN:0",
              "Infinity:0",
              "0x25:0x7f",
              "3.74444e1:1.278888e2",
              "37.4444:127.8888&groupKey=0:0",
              "37.4444:127.8888&lat=bad&lng=bad",
              "-0.000000:0.000000")) qs.add("groupKey=" + key);
      for (String v : List.of("NaN", "Infinity", "91", "37.4444x", "0x25", "37.4444&lat=38"))
        qs.add("lat=" + v + "&lng=127.8888");
      qs.add("groupKey%5Bx%5D=1");
      qs.add("lat=37.4444&lng=127.8888&tag=missing");
    } else {
      String keys = "cellLatKey=37.444&cellLngKey=127.889";
      for (String p :
          List.of("", "0", "1", "2", "3", "4", "1.5", "x", "0x3", "3e0", "%20", "3&precision=2"))
        qs.add(keys + "&precision=" + p);
      qs.add(keys);
      qs.add("precision=3");
      for (String k :
          List.of(
              "",
              "37.444",
              "37.4440",
              "%2037.444%20",
              "NaN",
              "Infinity",
              "91",
              "-0.000",
              "0x25",
              "37.444&cellLatKey=0")) qs.add("precision=3&cellLatKey=" + k + "&cellLngKey=127.889");
      for (String limit :
          List.of("", "0", "-1", "1", "2", "50", "51", "NaN", "2x", "1.5", "0x10", "1&limit=2"))
        qs.add(keys + "&precision=3&limit=" + limit);
      qs.add(keys + "&precision=3&tag=missing&search=missing");
      qs.add("precision=3&cellLatKey%5Bx%5D=37&cellLngKey=127");
    }
    return qs;
  }

  void record(String name, com.fasterxml.jackson.databind.JsonNode data) throws Exception {
    Path p = Path.of("build/reports/markers/" + name);
    Files.createDirectories(p.getParent());
    mapper.writerWithDefaultPrettyPrinter().writeValue(p.toFile(), data);
    if (!Boolean.getBoolean("markers.observe"))
      try (var in = getClass().getResourceAsStream("/markers/" + name)) {
        org.assertj.core.api.Assertions.assertThat(data).isEqualTo(mapper.readTree(in));
      }
  }
}
