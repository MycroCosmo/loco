package com.loco.places;

import static org.assertj.core.api.Assertions.*;

import com.loco.compat.SharedHarness;
import com.loco.maps.SqlQueryCounter;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;

@Import(SqlQueryCounter.Configuration.class)
class MarkerCellWriteIntegrationTest extends MarkerTestSupport {
  @Override
  protected boolean usesV2Http() {
    return false;
  }

  @ParameterizedTest
  @ValueSource(doubles = {37.44441, 38.0})
  void eachAffectedCellIsWrittenOnceAndItsPrecisionUsesOneTimestamp(double latitude)
      throws Exception {
    seed("basic");
    try (var db = environment().connect(SharedHarness.Side.V3);
        var q = db.createStatement()) {
      q.execute(
          "UPDATE places SET \"mapId\"='"
              + PlaceObservationTest.map(2)
              + "' WHERE id='"
              + PlaceObservationTest.place(7)
              + "'");
      q.execute(
          "CREATE TABLE marker_write_audit(p int,lat text,at timestamp); CREATE FUNCTION audit_marker_write() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN INSERT INTO marker_write_audit VALUES(NEW.precision,NEW.\"cellLatKey\",NEW.\"updatedAt\");RETURN NEW;END $$; CREATE TRIGGER audit_marker_write AFTER INSERT OR UPDATE ON place_marker_cells FOR EACH ROW EXECUTE FUNCTION audit_marker_write()");
      try {
        SqlQueryCounter.reset();
        var r =
            request(
                v3Url(),
                "/api/places/" + PlaceObservationTest.place(6),
                HttpMethod.PUT,
                PlaceWriteSupport.token(environment()),
                Map.of("latitude", latitude));
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(SqlQueryCounter.statements()).hasSize(7);
        try (var rows =
            q.executeQuery(
                "SELECT p,count(*),count(DISTINCT lat),count(DISTINCT at) FROM marker_write_audit GROUP BY p ORDER BY p")) {
          for (int p = 1; p <= 3; p++) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isEqualTo(p);
            assertThat(rows.getInt(2)).isEqualTo(latitude == 38 ? 2 : 1);
            assertThat(rows.getInt(3)).isEqualTo(rows.getInt(2));
            assertThat(rows.getInt(4)).isEqualTo(1);
          }
          assertThat(rows.next()).isFalse();
        }
      } finally {
        q.execute(
            "DROP TRIGGER audit_marker_write ON place_marker_cells;DROP FUNCTION audit_marker_write();DROP TABLE marker_write_audit");
      }
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {2, 4, 8, 16})
  void oneStatementDoesNotClaimToFixAStaleConcurrentSnapshot(int requests) throws Exception {
    seed("basic");
    try (var gate = environment().connect(SharedHarness.Side.V3);
        var observe = environment().connect(SharedHarness.Side.V3);
        var q = observe.createStatement();
        var pool = Executors.newFixedThreadPool(requests)) {
      q.execute("DELETE FROM place_reviews; DELETE FROM places; DELETE FROM place_marker_cells");
      q.execute(
          "CREATE FUNCTION marker_snapshot_gate() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.precision=1 AND NEW.count=1 THEN PERFORM pg_advisory_xact_lock(728193); END IF;RETURN NEW;END $$;CREATE TRIGGER marker_snapshot_gate BEFORE INSERT ON place_marker_cells FOR EACH ROW EXECUTE FUNCTION marker_snapshot_gate()");
      gate.setAutoCommit(false);
      try (var hold = gate.createStatement()) {
        hold.execute("SELECT pg_advisory_xact_lock(728193)");
      }
      var token = PlaceWriteSupport.token(environment());
      Callable<Integer> create =
          () ->
              request(
                      v3Url(),
                      PlaceWriteSupport.createPath(1),
                      HttpMethod.POST,
                      token,
                      Map.of(
                          "name",
                          "race",
                          "address",
                          "synthetic",
                          "latitude",
                          37.4444,
                          "longitude",
                          127.8888))
                  .getStatusCode()
                  .value();
      var first = pool.submit(create);
      try {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (true) {
          try (var rows =
              q.executeQuery(
                  "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event='advisory' AND query LIKE '%WITH keys%'")) {
            rows.next();
            if (rows.getInt(1) == 1) break;
          }
          if (first.isDone() || System.nanoTime() > deadline)
            throw new AssertionError("First aggregate did not reach the DB gate");
          Thread.sleep(20);
        }
        var rest = new ArrayList<Future<Integer>>();
        for (int i = 1; i < requests; i++) rest.add(pool.submit(create));
        for (var future : rest) assertThat(future.get(30, TimeUnit.SECONDS)).isEqualTo(201);
        gate.commit();
        assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(201);
        var counts = new ArrayList<Integer>();
        try (var rows =
            q.executeQuery("SELECT precision,count FROM place_marker_cells ORDER BY precision")) {
          for (int p = 1; p <= 3; p++) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isEqualTo(p);
            counts.add(rows.getInt(2));
          }
          assertThat(rows.next()).isFalse();
        }
        assertThat(counts)
            .as("E-6 B explicitly retains the stale snapshot risk")
            .containsExactly(1, requests, requests);
        var output = java.nio.file.Path.of("build/reports/markers/p2-race-" + requests + ".json");
        java.nio.file.Files.createDirectories(output.getParent());
        mapper
            .writerWithDefaultPrettyPrinter()
            .writeValue(
                output.toFile(),
                Map.of(
                    "requests",
                    requests,
                    "success",
                    requests,
                    "errors",
                    0,
                    "staleCells",
                    1,
                    "counts",
                    counts,
                    "expectedCount",
                    requests,
                    "limit",
                    "E-6 B: aggregate snapshot can precede the conflicting writer; no serialization promised"));
      } finally {
        gate.rollback();
        first.cancel(true);
        pool.shutdownNow();
        q.execute(
            "DROP TRIGGER marker_snapshot_gate ON place_marker_cells;DROP FUNCTION marker_snapshot_gate()");
      }
    }
  }
}
