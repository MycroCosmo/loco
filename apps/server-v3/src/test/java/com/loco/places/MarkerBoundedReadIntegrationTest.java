package com.loco.places;

import static org.assertj.core.api.Assertions.*;

import com.loco.compat.SharedHarness;
import com.loco.maps.SqlQueryCounter;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;

@Import(SqlQueryCounter.Configuration.class)
class MarkerBoundedReadIntegrationTest extends MarkerTestSupport {
  @Override
  protected boolean usesV2Http() {
    return false;
  }

  record Expected(String key, double lat, int count) {}

  @ParameterizedTest
  @ValueSource(ints = {0, 698, 699, 700, 701, 50000})
  void selectedSetOrderOverflowAndJdbcRowsAreBounded(int count) throws Exception {
    seed("basic");
    try (var db = environment().connect(SharedHarness.Side.V3);
        var q = db.createStatement()) {
      q.execute("DELETE FROM place_marker_cells");
      q.execute(
          "INSERT INTO place_marker_cells(\"mapId\",precision,\"cellLatKey\",\"cellLngKey\",latitude,longitude,\"minLat\",\"maxLat\",\"minLng\",\"maxLng\",count,\"exactKeyCount\",\"updatedAt\") SELECT '"
              + PlaceObservationTest.map(1)
              + "',3,(n/1000.0)::numeric(10,3)::text,'0.000',n/1000.0,0,n/1000.0,n/1000.0,0,0,n%5+1,2,CURRENT_TIMESTAMP FROM generate_series(0,"
              + (count - 1)
              + ")n");
      SqlQueryCounter.reset();
      var response =
          request(
              v3Url(),
              path("markers") + "?" + BOUNDS + "&level=6",
              HttpMethod.GET,
              PlaceWriteSupport.token(environment()),
              null);
      assertThat(response.getStatusCode().value()).isEqualTo(200);
      assertThat(SqlQueryCounter.statements()).hasSize(3);
      String actualSql = SqlQueryCounter.statements().getLast();
      var body = response.getBody();
      assertThat(body.size()).isEqualTo(Math.min(count, 700));
      var expected = new ArrayList<Expected>();
      for (int n = 0; n < count; n++)
        expected.add(
            new Expected(
                BigDecimal.valueOf(n).movePointLeft(3).setScale(3).toPlainString(),
                n / 1000.0,
                n % 5 + 1));
      expected.sort(
          Comparator.comparingInt(Expected::count).reversed().thenComparing(Expected::key));
      for (int n = 0; n < Math.min(count, 699); n++) {
        var e = expected.get(n);
        var row = body.get(n);
        assertThat(row.path("cellLatKey").asText()).isEqualTo(e.key());
        assertThat(row.path("cellLngKey").asText()).isEqualTo("0.000");
        assertThat(row.path("count").asInt()).isEqualTo(e.count());
        assertThat(row.path("latitude").asDouble()).isEqualTo(e.lat());
      }
      if (count >= 700) {
        long weight = 0;
        BigDecimal sum = BigDecimal.ZERO;
        for (var e : expected.subList(699, count)) {
          weight += e.count();
          sum = sum.add(new BigDecimal(e.key()).multiply(BigDecimal.valueOf(e.count())));
        }
        var overflow = body.get(699);
        assertThat(overflow.path("id").asText()).isEqualTo("overflow");
        assertThat(overflow.path("count").asLong()).isEqualTo(weight);
        assertThat(overflow.path("latitude").asDouble())
            .isCloseTo(
                sum.divide(BigDecimal.valueOf(weight), java.math.MathContext.DECIMAL128)
                    .doubleValue(),
                org.assertj.core.data.Offset.offset(1e-10));
      }
      try (var stmt = db.prepareStatement(actualSql)) {
        bindCells(stmt);
        int returned = 0;
        try (var rows = stmt.executeQuery()) {
          while (rows.next()) returned++;
        }
        assertThat(returned)
            .as("Actual HTTP SQL must not transfer every matching cell")
            .isEqualTo(body.size())
            .isLessThanOrEqualTo(700);
      }
      try (var stmt =
          db.prepareStatement("EXPLAIN (ANALYZE,BUFFERS,VERBOSE,FORMAT JSON) " + actualSql)) {
        bindCells(stmt);
        try (var rows = stmt.executeQuery()) {
          rows.next();
          var plan = mapper.readTree(rows.getString(1));
          var file = java.nio.file.Path.of("build/reports/markers/p2-bounded-" + count + ".json");
          java.nio.file.Files.createDirectories(file.getParent());
          mapper
              .writerWithDefaultPrettyPrinter()
              .writeValue(
                  file.toFile(),
                  Map.of(
                      "inputCells",
                      count,
                      "jdbcRows",
                      body.size(),
                      "sql",
                      actualSql,
                      "plan",
                      plan));
        }
      }
    }
  }

  static void bindCells(PreparedStatement stmt) throws Exception {
    Object[] values = {
      PlaceObservationTest.map(1), 3, -90d, 90d, -180d, 180d, -180d, -90d, 180d, 90d
    };
    for (int i = 0; i < values.length; i++) stmt.setObject(i + 1, values[i]);
  }

  static List<String> snapshotRows(Connection db, String sql) throws Exception {
    var result = new ArrayList<String>();
    try (var stmt = db.prepareStatement(sql)) {
      bindCells(stmt);
      try (var rows = stmt.executeQuery()) {
        while (rows.next()) {
          var values = new ArrayList<String>();
          for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++)
            values.add(rows.getString(i));
          result.add(values.toString());
        }
      }
    }
    Collections.sort(result);
    return result;
  }

  @Test
  void probeVisibleAndOverflowShareOneSnapshotDuringConcurrentChanges() throws Exception {
    seed("overflow");
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
    String observed = SqlQueryCounter.statements().getLast();
    String instrumented =
        observed.replace(
            "SELECT * FROM matching LIMIT 700",
            "SELECT * FROM matching WHERE marker_probe_gate() LIMIT 700");
    assertThat(instrumented).isNotEqualTo(observed);
    try (var gate = environment().connect(SharedHarness.Side.V3);
        var db = environment().connect(SharedHarness.Side.V3);
        var q = db.createStatement();
        var pool = Executors.newSingleThreadExecutor()) {
      var before = snapshotRows(db, observed);
      assertThat(before).hasSize(700);
      q.execute(
          "CREATE FUNCTION marker_probe_gate() RETURNS boolean LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(728194);RETURN true;END $$");
      gate.setAutoCommit(false);
      try (var hold = gate.createStatement()) {
        hold.execute("SELECT pg_advisory_xact_lock(728194)");
      }
      var waiting =
          pool.submit(
              () -> {
                try (var reader = environment().connect(SharedHarness.Side.V3)) {
                  return snapshotRows(reader, instrumented);
                }
              });
      try {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
          try (var rows =
              q.executeQuery(
                  "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event='advisory'")) {
            rows.next();
            if (rows.getInt(1) == 1) break;
          }
          if (waiting.isDone() || System.nanoTime() > deadline)
            throw new AssertionError("Probe did not reach snapshot gate");
          Thread.sleep(20);
        }
        q.execute("UPDATE place_marker_cells SET count=count+10");
        gate.commit();
        assertThat(waiting.get(15, TimeUnit.SECONDS)).isEqualTo(before);
        assertThat(snapshotRows(db, observed)).isNotEqualTo(before);
      } finally {
        gate.rollback();
        waiting.cancel(true);
        pool.shutdownNow();
        q.execute("DROP FUNCTION marker_probe_gate()");
      }
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {300, 301})
  void roundIsNotEvaluatedForCandidatesThatWillBeDiscarded(int count) throws Exception {
    seed("threshold" + count);
    SqlQueryCounter.reset();
    var response =
        request(
            v3Url(),
            path("markers") + "?" + BOUNDS + "&level=5",
            HttpMethod.GET,
            PlaceWriteSupport.token(environment()),
            null);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(SqlQueryCounter.statements()).hasSize(count == 300 ? 3 : 4);
    String observed = SqlQueryCounter.statements().get(2);
    try (var db = environment().connect(SharedHarness.Side.V3);
        var q = db.createStatement()) {
      q.execute(
          "CREATE TABLE marker_round_calls(n int);CREATE FUNCTION marker_test_round(v numeric,p int) RETURNS numeric LANGUAGE plpgsql AS $$ BEGIN INSERT INTO marker_round_calls VALUES(1);RETURN round(v,p);END $$");
      try {
        String instrumented = observed.replace("THEN ROUND(", "THEN marker_test_round(");
        assertThat(instrumented).isNotEqualTo(observed);
        try (var stmt = db.prepareStatement(instrumented)) {
          Object[] values = {PlaceObservationTest.map(1), -90d, 90d, -180d, 180d};
          for (int i = 0; i < values.length; i++) stmt.setObject(i + 1, values[i]);
          int returned = 0;
          try (var rows = stmt.executeQuery()) {
            while (rows.next()) {
              returned++;
              assertThat(rows.getString("latKey") == null).isEqualTo(count == 301);
            }
          }
          assertThat(returned).isEqualTo(count);
        }
        try (var rows = q.executeQuery("SELECT count(*) FROM marker_round_calls")) {
          rows.next();
          assertThat(rows.getInt(1)).isEqualTo(count == 300 ? 600 : 0);
        }
      } finally {
        q.execute("DROP FUNCTION marker_test_round(numeric,int);DROP TABLE marker_round_calls");
      }
    }
  }
}
