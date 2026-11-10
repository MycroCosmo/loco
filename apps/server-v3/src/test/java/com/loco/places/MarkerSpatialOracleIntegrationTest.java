package com.loco.places;

import static org.assertj.core.api.Assertions.*;

import com.loco.compat.SharedHarness;
import com.loco.maps.SqlQueryCounter;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;

@Import(SqlQueryCounter.Configuration.class)
class MarkerSpatialOracleIntegrationTest extends MarkerTestSupport {
  @Override
  protected boolean usesV2Http() {
    return false;
  }

  private String observed() throws Exception {
    SqlQueryCounter.reset();
    assertThat(
            request(
                    v3Url(),
                    path("markers") + "?" + BOUNDS + "&level=6",
                    HttpMethod.GET,
                    PlaceWriteSupport.token(environment()),
                    null)
                .getStatusCode()
                .value())
        .isEqualTo(200);
    assertThat(SqlQueryCounter.statements()).hasSize(3);
    return SqlQueryCounter.statements().getLast();
  }

  private String baseline() throws Exception {
    try (var in = getClass().getResourceAsStream("/markers/p2-b-cells.sql")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8)
          .replaceAll(":(map|precision|minLat|maxLat|minLng|maxLng)\\b", "?");
    }
  }

  private List<List<String>> rows(Connection db, String sql, int precision, double[] bounds)
      throws Exception {
    Object[] args = {
      PlaceObservationTest.map(1),
      precision,
      bounds[0],
      bounds[1],
      bounds[2],
      bounds[3],
      bounds[2],
      bounds[0],
      bounds[3],
      bounds[1]
    };
    var result = new ArrayList<List<String>>();
    try (var stmt = db.prepareStatement(sql)) {
      for (int i = 0; i < stmt.getParameterMetaData().getParameterCount(); i++)
        stmt.setObject(i + 1, args[i]);
      try (var r = stmt.executeQuery()) {
        while (r.next()) {
          var row = new ArrayList<String>();
          for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) row.add(r.getString(i));
          result.add(row);
        }
      }
    }
    result.sort(Comparator.comparing(Object::toString));
    return result;
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3})
  void spatialCandidatePreservesEveryNumericBoundaryAndDegenerateCell(int precision)
      throws Exception {
    seed("basic");
    String actual = observed(), old = baseline();
    try (var db = environment().connect(SharedHarness.Side.V3);
        var q = db.createStatement()) {
      q.execute("DELETE FROM place_marker_cells");
      double[][] boxes = {
        {37.05, 37.05, 127, 127},
        {-0.00001, -0.00001, -0.0, 0.0},
        {-1, 1, -1, 1},
        {0, 0, -1, 1},
        {-1, 1, 0, 0},
        {-90, -89, -180, -179},
        {89, 90, 179, 180},
        {37.04999999999999, 37.05000000000001, 127, 127},
        {91, 92, 181, 182}
      };
      for (int i = 0; i < boxes.length; i++) {
        double[] b = boxes[i];
        try (var s =
            db.prepareStatement(
                "INSERT INTO place_marker_cells(\"mapId\",precision,\"cellLatKey\",\"cellLngKey\",latitude,longitude,\"minLat\",\"maxLat\",\"minLng\",\"maxLng\",count,\"exactKeyCount\",\"updatedAt\") VALUES(?,? ,?, '0',?,?, ?,?,?,?,2,2,now())")) {
          Object[] v = {
            PlaceObservationTest.map(1),
            precision,
            "box-" + i,
            (b[0] + b[1]) / 2,
            (b[2] + b[3]) / 2,
            b[0],
            b[1],
            b[2],
            b[3]
          };
          for (int n = 0; n < v.length; n++) s.setObject(n + 1, v[n]);
          s.executeUpdate();
        }
      }
      var bounds = new ArrayList<double[]>();
      bounds.addAll(
          List.of(
              new double[] {-90, 90, -180, 180},
              new double[] {0, 0, 0, 0},
              new double[] {37.05, 37.05, 127, 127},
              new double[] {-0.00001, -0.00001, -0.0, 0.0},
              new double[] {0.9, 1, 0.9, 1},
              new double[] {91, 92, 181, 182},
              new double[] {-1e100, 1e100, -1e100, 1e100},
              new double[] {-1e308, 1e308, -1e308, 1e308},
              new double[] {1e100, 1e101, 1e100, 1e101},
              new double[] {37.06, 37.07, 127, 127}));
      for (double[] b : boxes)
        for (double edge : new double[] {b[0], Math.nextDown(b[0]), Math.nextUp(b[1])})
          bounds.add(new double[] {edge, edge, b[2], b[3]});
      Random random = new Random(32026);
      for (int i = 0; i < 40; i++) {
        double a = random.nextDouble() * 180 - 90, c = random.nextDouble() * 360 - 180;
        bounds.add(new double[] {a, a + random.nextDouble(), c, c + random.nextDouble()});
      }
      for (String mode : List.of("normal", "index")) {
        q.execute("SET enable_seqscan=" + (mode.equals("normal") ? "on" : "off"));
        for (double[] b : bounds)
          assertThat(rows(db, actual, precision, b))
              .as(mode + " precision=" + precision + " bounds=" + Arrays.toString(b))
              .isEqualTo(rows(db, old, precision, b));
      }
      // An overlapping corner must be included even though the cell centroid is outside.
      assertThat(rows(db, actual, precision, new double[] {0.9, 1, 0.9, 1})).hasSize(1);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 699, 700, 701, 50000})
  void baselineAndSpatialSqlKeepTheSameVisibleSetAndOverflowAfterReordering(int count)
      throws Exception {
    seed("basic");
    String actual = observed(), old = baseline();
    try (var db = environment().connect(SharedHarness.Side.V3);
        var q = db.createStatement()) {
      q.execute("DELETE FROM place_marker_cells");
      q.execute(
          "INSERT INTO place_marker_cells(\"mapId\",precision,\"cellLatKey\",\"cellLngKey\",latitude,longitude,\"minLat\",\"maxLat\",\"minLng\",\"maxLng\",count,\"exactKeyCount\",\"updatedAt\") SELECT '"
              + PlaceObservationTest.map(1)
              + "',3,(n/1000.0)::numeric(10,3)::text,'0.000',n/1000.0,0,n/1000.0,n/1000.0,0,0,n%5+1,2,now() FROM generate_series(0,"
              + (count - 1)
              + ")n ORDER BY n DESC");
      double[] b = {-90, 90, -180, 180};
      assertThat(rows(db, actual, 3, b))
          .isEqualTo(rows(db, old, 3, b))
          .hasSize(Math.min(count, 700));
      q.execute("UPDATE place_marker_cells SET \"updatedAt\"=now()");
      assertThat(rows(db, actual, 3, b)).isEqualTo(rows(db, old, 3, b));
    }
  }

  @Test
  void publicHttpKeepsReversedBoundsIgnoredTagsAndLevelSelection() throws Exception {
    seed("basic");
    String token = PlaceWriteSupport.token(environment());
    for (int level : List.of(0, 5, 6, 8, 9, 10, 11, 14)) {
      var normal =
          request(
              v3Url(),
              path("markers") + "?" + BOUNDS + "&level=" + level,
              HttpMethod.GET,
              token,
              null);
      var reversed =
          request(
              v3Url(),
              path("markers")
                  + "?swLat=90&neLat=-90&swLng=180&neLng=-180&tag=missing&precision=1&level="
                  + level,
              HttpMethod.GET,
              token,
              null);
      assertThat(normal.getStatusCode().value()).isEqualTo(level == 0 ? 400 : 200);
      if (level == 0)
        assertThat(normal.getBody().path("message").asText()).isEqualTo("level은 숫자여야 합니다.");
      assertThat(reversed.getStatusCode()).isEqualTo(normal.getStatusCode());
      assertThat(reversed.getBody()).isEqualTo(normal.getBody());
    }
  }
}
