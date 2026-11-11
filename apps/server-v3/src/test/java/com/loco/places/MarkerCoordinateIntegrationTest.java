package com.loco.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.loco.compat.SharedHarness;
import com.loco.maps.SqlQueryCounter;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;

/** P1 oracle reads persisted coordinates, independently of the HTTP response rounding. */
@Import(SqlQueryCounter.Configuration.class)
class MarkerCoordinateIntegrationTest extends MarkerTestSupport {
  @Override
  protected boolean usesV2Http() {
    return false;
  }

  static Stream<Double> boundaries() {
    var values =
        new LinkedHashSet<Double>(List.of(0.0, -0.0, -0.00001, 37.05, -37.05, 90.0, -90.0));
    for (double edge : new double[] {0.05, 0.005, 0.0005, 37.0000005})
      for (double v : new double[] {Math.nextDown(edge), edge, Math.nextUp(edge)}) {
        values.add(v);
        values.add(-v);
      }
    return values.stream();
  }

  void empty() throws Exception {
    seed("basic");
    try (var db = environment().connect(SharedHarness.Side.V3);
        var q = db.createStatement()) {
      q.execute("DELETE FROM place_reviews; DELETE FROM places; DELETE FROM place_marker_cells");
    }
  }

  JsonNode call(String url, HttpMethod method, JsonNode body, int status) throws Exception {
    var r = request(v3Url(), url, method, PlaceWriteSupport.token(environment()), body);
    assertThat(r.getStatusCode().value()).as(url).isEqualTo(status);
    return r.getBody();
  }

  @ParameterizedTest
  @MethodSource("boundaries")
  void actualWritesUseTheSameKeysAsStoredCoordinates(double lat) throws Exception {
    empty();
    double lng = lat == 90 ? 180 : lat == -90 ? -180 : 0;
    var body =
        mapper
            .createObjectNode()
            .put("name", "boundary")
            .put("address", "synthetic")
            .put("latitude", lat)
            .put("longitude", lng);
    var created = call(PlaceWriteSupport.createPath(1), HttpMethod.POST, body, 201);
    String id = created.path("id").asText();
    assertSingle(id);
    if (lat == 37.05 || lat == -0.00001) {
      try (var db = environment().connect(SharedHarness.Side.V3);
          var q = db.createStatement();
          var r =
              q.executeQuery("SELECT \"cellLatKey\" FROM place_marker_cells WHERE precision=1")) {
        assertThat(r.next()).isTrue();
        assertThat(r.getString(1)).isEqualTo(lat == 37.05 ? "37.1" : "0.0");
      }
    }
    call(
        "/api/places/" + id,
        HttpMethod.PUT,
        mapper.createObjectNode().put("latitude", lat == 0 ? -37.05 : -lat),
        200);
    assertSingle(id);
    call("/api/places/" + id, HttpMethod.DELETE, null, 200);
    try (var db = environment().connect(SharedHarness.Side.V3);
        var q = db.createStatement();
        var r = q.executeQuery("SELECT count(*) FROM place_marker_cells")) {
      assertThat(r.next()).isTrue();
      assertThat(r.getInt(1)).isZero();
    }
  }

  void assertSingle(String id) throws Exception {
    var keys = new ArrayList<String[]>();
    try (var db = environment().connect(SharedHarness.Side.V3);
        var q =
            db.prepareStatement(
                "SELECT p,ROUND(latitude::numeric,p)::text,ROUND(longitude::numeric,p)::text FROM places CROSS JOIN generate_series(1,3)p WHERE id=? ORDER BY p")) {
      q.setString(1, id);
      try (var r = q.executeQuery()) {
        while (r.next()) keys.add(new String[] {r.getString(1), r.getString(2), r.getString(3)});
      }
      try (var cells = db.createStatement();
          var r =
              cells.executeQuery(
                  "SELECT precision,\"cellLatKey\",\"cellLngKey\",count FROM place_marker_cells ORDER BY precision")) {
        for (var key : keys) {
          assertThat(r.next()).isTrue();
          assertThat(List.of(r.getString(1), r.getString(2), r.getString(3))).containsExactly(key);
          assertThat(r.getInt(4)).isEqualTo(1);
          for (int i = 1; i < 3; i++)
            if (new BigDecimal(key[i]).signum() == 0) assertThat(key[i]).doesNotStartWith("-");
        }
        assertThat(r.next()).isFalse();
      }
      try (var group =
          db.prepareStatement(
              "SELECT ROUND(latitude::numeric,6)::text||':'||ROUND(longitude::numeric,6)::text FROM places WHERE id=?")) {
        group.setString(1, id);
        try (var r = group.executeQuery()) {
          assertThat(r.next()).isTrue();
          var result =
              call(path("marker-group") + "?groupKey=" + r.getString(1), HttpMethod.GET, null, 200);
          assertThat(result.size()).isEqualTo(1);
          assertThat(result.get(0).path("id").asText()).isEqualTo(id);
        }
      }
    }
    assertThat(keys).hasSize(3);
    for (int level : new int[] {5, 6, 9, 11}) {
      var result =
          call(path("markers") + "?" + BOUNDS + "&level=" + level, HttpMethod.GET, null, 200);
      assertThat(result.size()).isEqualTo(1);
      assertThat(result.get(0).path("id").asText()).isEqualTo(id);
    }
    for (var key : keys) {
      var result =
          call(
              path("marker-cluster")
                  + "?precision="
                  + key[0]
                  + "&cellLatKey="
                  + key[1]
                  + "&cellLngKey="
                  + key[2],
              HttpMethod.GET,
              null,
              200);
      assertThat(result.path("count").asInt()).isEqualTo(1);
      assertThat(result.path("items").size()).isEqualTo(1);
      assertThat(result.path("items").get(0).path("id").asText()).isEqualTo(id);
    }
  }

  @Test
  void legacyNegativeZeroAndNonzeroKeysHaveExplicitMeaning() throws Exception {
    empty();
    var body =
        mapper
            .createObjectNode()
            .put("name", "zero")
            .put("address", "synthetic")
            .put("latitude", -0.00001)
            .put("longitude", 0);
    call(PlaceWriteSupport.createPath(1), HttpMethod.POST, body, 201);
    var old =
        call(
            path("marker-cluster") + "?precision=3&cellLatKey=-0.000&cellLngKey=-0.000",
            HttpMethod.GET,
            null,
            200);
    assertThat(old.path("count").asInt()).isEqualTo(1);
    body.put("latitude", 37.05);
    call(PlaceWriteSupport.createPath(1), HttpMethod.POST, body, 201);
    var stale =
        call(
            path("marker-cluster") + "?precision=1&cellLatKey=37.0&cellLngKey=0.0",
            HttpMethod.GET,
            null,
            200);
    assertThat(stale.path("count").asInt()).isZero();
    var fresh =
        call(
            path("marker-cluster") + "?precision=1&cellLatKey=37.1&cellLngKey=0.0",
            HttpMethod.GET,
            null,
            200);
    assertThat(fresh.path("count").asInt()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(ints = {300, 301})
  void sixDigitGroupsAreIdenticalAcrossThePlacesFallback(int count) throws Exception {
    empty();
    try (var db = environment().connect(SharedHarness.Side.V3);
        var q = db.createStatement()) {
      q.execute(
          "INSERT INTO places(id,\"mapId\",\"createdById\",name,address,latitude,longitude,\"updatedAt\") SELECT md5(n::text)::uuid::text,'"
              + PlaceObservationTest.map(1)
              + "','11111111-1111-4111-8111-111111111111','p','a',37.0000005,0,CURRENT_TIMESTAMP FROM generate_series(1,"
              + count
              + ")n");
      org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
          db, new org.springframework.core.io.ClassPathResource("markers/cells.sql"));
    }
    var marker = call(path("markers") + "?" + BOUNDS + "&level=5", HttpMethod.GET, null, 200);
    assertThat(marker.size()).isEqualTo(1);
    assertThat(marker.get(0).path("groupKey").asText()).isEqualTo("37.000001:0.000000");
    assertThat(marker.get(0).path("count").asInt()).isEqualTo(count);
    assertThat(
            call(path("marker-group") + "?groupKey=37.000001:0.000000", HttpMethod.GET, null, 200)
                .size())
        .isEqualTo(count);
    assertThat(
            call(path("marker-group") + "?groupKey=37.000000:0.000000", HttpMethod.GET, null, 200)
                .size())
        .isZero();
  }

  @ParameterizedTest
  @ValueSource(ints = {699, 700, 701})
  void tiedOverflowHasStableVisibleMarkersAndCenterAfterPhysicalReordering(int count)
      throws Exception {
    empty();
    try (var db = environment().connect(SharedHarness.Side.V3);
        var q = db.createStatement()) {
      q.execute(
          "INSERT INTO places(id,\"mapId\",\"createdById\",name,address,latitude,longitude,\"updatedAt\") SELECT md5(n::text||':'||j)::uuid::text,'"
              + PlaceObservationTest.map(1)
              + "','11111111-1111-4111-8111-111111111111','p','a',(n-1)/100.0+j/1000000.0,0,CURRENT_TIMESTAMP FROM generate_series(1,"
              + count
              + ")n CROSS JOIN generate_series(1,2)j");
      org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
          db, new org.springframework.core.io.ClassPathResource("markers/cells.sql"));
    }
    JsonNode first = null;
    var expected =
        java.util.stream.IntStream.range(0, count)
            .mapToObj(
                n -> BigDecimal.valueOf(n).movePointLeft(2).setScale(3).toPlainString() + ":0.000")
            .sorted()
            .toList();
    for (int repeat = 0; repeat < 3; repeat++) {
      SqlQueryCounter.reset();
      var response = call(path("markers") + "?" + BOUNDS + "&level=6", HttpMethod.GET, null, 200);
      assertThat(SqlQueryCounter.statements()).hasSize(3);
      assertThat(response.size()).isEqualTo(Math.min(count, 700));
      var visible = new ArrayList<String>();
      for (int i = 0; i < Math.min(count, 699); i++)
        visible.add(response.get(i).path("clusterKey").asText());
      assertThat(visible).containsExactlyElementsOf(expected.subList(0, Math.min(count, 699)));
      if (count >= 700) {
        var overflow = response.get(699);
        assertThat(overflow.path("id").asText()).isEqualTo("overflow");
        assertThat(overflow.path("count").asInt()).isEqualTo((count - 699) * 2);
        double center =
            java.util.stream.IntStream.range(699, count)
                .mapToDouble(n -> n / 100.0 + 0.0000015)
                .average()
                .orElseThrow();
        assertThat(overflow.path("latitude").asDouble())
            .isCloseTo(center, org.assertj.core.data.Offset.offset(1e-12));
      }
      if (first != null) assertThat(response).isEqualTo(first);
      else first = response;
      try (var db = environment().connect(SharedHarness.Side.V3);
          var q = db.createStatement()) {
        q.execute(
            "CREATE TEMP TABLE reordered AS SELECT * FROM place_marker_cells; DELETE FROM place_marker_cells; INSERT INTO place_marker_cells SELECT * FROM reordered ORDER BY \"cellLatKey\" DESC,precision DESC; ANALYZE place_marker_cells");
      }
    }
  }
}
