package com.loco.places;

import static org.assertj.core.api.Assertions.*;

import com.loco.compat.SharedHarness;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.datasource.init.ScriptUtils;

@Tag("v2-comparison")
class MarkerCrossMoveIntegrationTest extends MarkerTestSupport {
  private void awaitGate(Statement q, int expected, Future<?> request) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (true) {
      try (var rows =
          q.executeQuery(
              "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event='advisory'")) {
        rows.next();
        if (rows.getInt(1) == expected) return;
      }
      if (request.isDone() || System.nanoTime() > end)
        throw new AssertionError("Cross move did not reach gate " + expected);
      Thread.sleep(20);
    }
  }

  @ParameterizedTest
  @EnumSource(SharedHarness.Side.class)
  void crossingMovesDoNotIntroduceADeadlock(SharedHarness.Side side) throws Exception {
    seed("basic");
    try (var db = environment().connect(side);
        var q = db.createStatement();
        var gate = environment().connect(side);
        var pool = Executors.newFixedThreadPool(2)) {
      q.execute(
          "DELETE FROM place_reviews;DELETE FROM places WHERE id NOT IN ('"
              + PlaceObservationTest.place(1)
              + "','"
              + PlaceObservationTest.place(2)
              + "');UPDATE places SET latitude=CASE WHEN id='"
              + PlaceObservationTest.place(1)
              + "' THEN 37 ELSE 38 END,longitude=127;DELETE FROM place_marker_cells");
      ScriptUtils.executeSqlScript(db, new ClassPathResource("markers/cells.sql"));
      // Pause each implementation after it owns the first cell lock. v2 commits its
      // old cell before touching the new one; v3 now retains both within one precision.
      String conditions =
          side == SharedHarness.Side.V3
              ? "IF NEW.precision=1 AND NEW.\"cellLatKey\"='38.0' AND NEW.count=2 THEN PERFORM pg_advisory_xact_lock(728195);ELSIF NEW.precision=1 AND NEW.\"cellLatKey\"='37.0' AND NEW.count=1 THEN PERFORM pg_advisory_xact_lock(728196);END IF;"
              : "IF TG_OP='DELETE' AND OLD.precision=1 AND OLD.\"cellLatKey\"='37.0' THEN PERFORM pg_advisory_xact_lock(728195);ELSIF TG_OP='UPDATE' AND NEW.precision=1 AND NEW.\"cellLatKey\"='38.0' AND NEW.count=1 THEN PERFORM pg_advisory_xact_lock(728196);END IF;";
      q.execute(
          "CREATE FUNCTION marker_cross_gate() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
              + conditions
              + " IF TG_OP='DELETE' THEN RETURN OLD;END IF;RETURN NEW;END $$;CREATE TRIGGER marker_cross_gate BEFORE UPDATE OR DELETE ON place_marker_cells FOR EACH ROW EXECUTE FUNCTION marker_cross_gate()");
      gate.setAutoCommit(false);
      try (var hold = gate.createStatement()) {
        hold.execute("SELECT pg_advisory_xact_lock(728195),pg_advisory_xact_lock(728196)");
      }
      String token = PlaceWriteSupport.token(environment());
      String base = side == SharedHarness.Side.V2 ? v2Url() : v3Url();
      Callable<Integer> first =
          () ->
              request(
                      base,
                      "/api/places/" + PlaceObservationTest.place(1),
                      HttpMethod.PUT,
                      token,
                      Map.of("latitude", 38))
                  .getStatusCode()
                  .value();
      Callable<Integer> second =
          () ->
              request(
                      base,
                      "/api/places/" + PlaceObservationTest.place(2),
                      HttpMethod.PUT,
                      token,
                      Map.of("latitude", 37))
                  .getStatusCode()
                  .value();
      var left = pool.submit(first);
      Future<Integer> right = null;
      try {
        awaitGate(q, 1, left);
        right = pool.submit(second);
        awaitGate(q, 2, right);
        gate.commit();
        var statuses = List.of(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS));
        var cells = new ArrayList<Map<String, Object>>();
        try (var rows =
            q.executeQuery(
                "SELECT precision,\"cellLatKey\",count FROM place_marker_cells ORDER BY precision,\"cellLatKey\"")) {
          while (rows.next())
            cells.add(
                Map.of(
                    "precision",
                    rows.getInt(1),
                    "key",
                    rows.getString(2),
                    "count",
                    rows.getInt(3)));
        }
        var output =
            java.nio.file.Path.of(
                "build/reports/markers/p2-cross-move-" + side.name().toLowerCase() + ".json");
        java.nio.file.Files.createDirectories(output.getParent());
        mapper
            .writerWithDefaultPrettyPrinter()
            .writeValue(
                output.toFile(), Map.of("side", side.name(), "statuses", statuses, "cells", cells));
        assertThat(statuses)
            .as("A new deadlock is not an approved stale-snapshot difference")
            .containsExactly(200, 200);
      } finally {
        gate.rollback();
        left.cancel(true);
        if (right != null) right.cancel(true);
        pool.shutdownNow();
        q.execute(
            "DROP TRIGGER marker_cross_gate ON place_marker_cells;DROP FUNCTION marker_cross_gate()");
      }
    }
  }
}
