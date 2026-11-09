package com.loco.places;

import static org.assertj.core.api.Assertions.*;

import com.loco.compat.SharedHarness;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
class MarkerRefreshRaceComparisonTest extends MarkerTestSupport {
  @Test
  void preservesV2StaleSnapshotEvidenceAndRequiresV3FreshSnapshot() throws Exception {
    seed("basic");
    var observed = new LinkedHashMap<String, List<Integer>>();
    for (var side : SharedHarness.Side.values()) {
      try (var gate = environment().connect(side);
          var db = environment().connect(side);
          var q = db.createStatement();
          var pool = Executors.newFixedThreadPool(2)) {
        q.execute("DELETE FROM place_reviews;DELETE FROM places;DELETE FROM place_marker_cells");
        q.execute(
            "CREATE FUNCTION refresh_race_gate() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.precision=1 AND NEW.count=1 THEN PERFORM pg_advisory_xact_lock(728197);END IF;RETURN NEW;END $$;CREATE TRIGGER refresh_race_gate BEFORE INSERT ON place_marker_cells FOR EACH ROW EXECUTE FUNCTION refresh_race_gate()");
        gate.setAutoCommit(false);
        try (var hold = gate.createStatement()) {
          hold.execute("SELECT pg_advisory_xact_lock(728197)");
        }
        String base = side == SharedHarness.Side.V2 ? v2Url() : v3Url(),
            token = PlaceWriteSupport.token(environment());
        Callable<Integer> create =
            () ->
                request(
                        base,
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
        Future<Integer> second = null;
        try {
          awaitWaiters(q, 1);
          second = pool.submit(create);
          if (side == SharedHarness.Side.V2)
            assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(201);
          else awaitWaiters(q, 2);
          gate.commit();
          assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(201);
          assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(201);
          var counts = new ArrayList<Integer>();
          try (var rows =
              q.executeQuery("SELECT count FROM place_marker_cells ORDER BY precision")) {
            while (rows.next()) counts.add(rows.getInt(1));
          }
          assertThat(counts).containsExactly(side == SharedHarness.Side.V2 ? 1 : 2, 2, 2);
          observed.put(side.name(), counts);
        } finally {
          gate.rollback();
          first.cancel(true);
          if (second != null) second.cancel(true);
          pool.shutdownNow();
          q.execute(
              "DROP TRIGGER refresh_race_gate ON place_marker_cells;DROP FUNCTION refresh_race_gate()");
        }
      }
    }
    environment()
        .knownDifferences
        .add(
            Map.of(
                "case",
                "markers.refresh-serialization",
                "kind",
                "improved",
                "decision",
                "E-22 B variant: separate map+precision lock statement then READ COMMITTED DML; all writers must participate",
                "v2",
                observed.get("V2"),
                "v3",
                observed.get("V3")));
  }

  private static void awaitWaiters(java.sql.Statement q, int count) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (true) {
      try (var r =
          q.executeQuery(
              "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event='advisory'")) {
        r.next();
        if (r.getInt(1) == count) return;
      }
      if (System.nanoTime() > end)
        throw new AssertionError("Missing expected database gate/lock waiters: " + count);
      Thread.sleep(20);
    }
  }
}
