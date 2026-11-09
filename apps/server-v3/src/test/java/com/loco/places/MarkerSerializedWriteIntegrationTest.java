package com.loco.places;

import static org.assertj.core.api.Assertions.*;

import com.loco.compat.SharedHarness;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class MarkerSerializedWriteIntegrationTest extends MarkerTestSupport {
  @Override
  protected boolean usesV2Http() {
    return false;
  }

  static Stream<Arguments> races() {
    return Stream.of("move", "delete", "create-delete")
        .flatMap(operation -> Stream.of(2, 4, 8, 16).map(n -> Arguments.of(operation, n)));
  }

  @ParameterizedTest
  @MethodSource("races")
  void completedConcurrentMutationsMatchFreshAggregate(String operation, int n) throws Exception {
    seed("basic");
    try (var db = environment().connect(SharedHarness.Side.V3);
        var q = db.createStatement();
        var pool = Executors.newFixedThreadPool(n)) {
      q.execute("DELETE FROM place_reviews;DELETE FROM places;DELETE FROM place_marker_cells");
      q.execute(
          "INSERT INTO places(id,\"mapId\",\"createdById\",name,address,latitude,longitude,tags,\"imageUrls\",\"updatedAt\") SELECT 'cccccccc-cccc-4ccc-8ccc-'||lpad(n::text,12,'0'),'"
              + PlaceObservationTest.map(1)
              + "','"
              + SharedHarness.Environment.USER_ID
              + "','race','synthetic',37+n%2,127,ARRAY[]::text[],ARRAY[]::text[],CURRENT_TIMESTAMP FROM generate_series(1,"
              + n
              + ")n");
      ScriptUtils.executeSqlScript(db, new ClassPathResource("markers/cells.sql"));
      var barrier = new CyclicBarrier(n);
      var pending = new ArrayList<Future<Integer>>();
      String token = PlaceWriteSupport.token(environment());
      for (int i = 1; i <= n; i++) {
        final int index = i;
        pending.add(
            pool.submit(
                () -> {
                  barrier.await(10, TimeUnit.SECONDS);
                  String path = "/api/places/" + PlaceObservationTest.place(index);
                  if (operation.equals("move"))
                    return request(
                            v3Url(),
                            path,
                            HttpMethod.PUT,
                            token,
                            Map.of("latitude", 39 + index % 2))
                        .getStatusCode()
                        .value();
                  if (operation.equals("create-delete") && index % 2 == 0)
                    return request(
                            v3Url(),
                            PlaceWriteSupport.createPath(1),
                            HttpMethod.POST,
                            token,
                            Map.of(
                                "name",
                                "race-new",
                                "address",
                                "synthetic",
                                "latitude",
                                37 + index % 2,
                                "longitude",
                                127))
                        .getStatusCode()
                        .value();
                  return request(v3Url(), path, HttpMethod.DELETE, token, null)
                      .getStatusCode()
                      .value();
                }));
      }
      var statuses = new ArrayList<Integer>();
      for (var future : pending) statuses.add(future.get(30, TimeUnit.SECONDS));
      assertThat(statuses).allMatch(status -> status == 200 || status == 201);
      try (var rows =
          q.executeQuery(
              "WITH expected AS (SELECT \"mapId\",p,ROUND(latitude::numeric,p)::text lat,ROUND(longitude::numeric,p)::text lng,count(*) n FROM places CROSS JOIN generate_series(1,3)p GROUP BY 1,2,3,4) SELECT count(*) FROM expected e FULL JOIN place_marker_cells c ON c.\"mapId\"=e.\"mapId\" AND c.precision=e.p AND c.\"cellLatKey\"=e.lat AND c.\"cellLngKey\"=e.lng WHERE c.count IS DISTINCT FROM e.n")) {
        rows.next();
        assertThat(rows.getInt(1))
            .as("Missing, extra and stale cells after all writers complete")
            .isZero();
      }
      var file =
          java.nio.file.Path.of("build/reports/markers/e22-" + operation + "-" + n + ".json");
      java.nio.file.Files.createDirectories(file.getParent());
      mapper
          .writerWithDefaultPrettyPrinter()
          .writeValue(
              file.toFile(),
              Map.of("operation", operation, "requests", n, "statuses", statuses, "staleCells", 0));
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3})
  void deferredCommitFailureRollsBackBothCellsAndReleasesLock(int precision) throws Exception {
    seed("basic");
    try (var db = environment().connect(SharedHarness.Side.V3);
        var q = db.createStatement()) {
      try (var row = q.executeQuery("SHOW transaction_isolation")) {
        row.next();
        assertThat(row.getString(1)).isEqualTo("read committed");
      }
      q.execute(
          "CREATE FUNCTION marker_commit_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF COALESCE(NEW.precision,OLD.precision)="
              + precision
              + " THEN RAISE EXCEPTION 'commit failure';END IF;RETURN NULL;END $$;CREATE CONSTRAINT TRIGGER marker_commit_failure AFTER INSERT OR UPDATE OR DELETE ON place_marker_cells DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION marker_commit_failure()");
      try {
        var response =
            request(
                v3Url(),
                "/api/places/" + PlaceObservationTest.place(6),
                HttpMethod.PUT,
                PlaceWriteSupport.token(environment()),
                Map.of("latitude", 38, "longitude", 128));
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        try (var row =
            q.executeQuery(
                "SELECT latitude FROM places WHERE id='" + PlaceObservationTest.place(6) + "'")) {
          row.next();
          assertThat(row.getDouble(1)).isEqualTo(38);
        }
        try (var rows =
            q.executeQuery(
                "SELECT precision,\"cellLatKey\",count FROM place_marker_cells WHERE \"mapId\"='"
                    + PlaceObservationTest.map(2)
                    + "' ORDER BY precision")) {
          for (int p = 1; p <= 3; p++) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(2))
                .isEqualTo(CoordinateKeys.fixed(p < precision ? 38 : 37.4444, p));
            assertThat(rows.getInt(3)).isEqualTo(1);
          }
          assertThat(rows.next()).isFalse();
        }
        try (var row =
            q.executeQuery(
                "SELECT pg_try_advisory_xact_lock("
                    + UUID.fromString(PlaceObservationTest.map(2)).hashCode()
                    + ","
                    + precision
                    + ")")) {
          row.next();
          assertThat(row.getBoolean(1)).as("Rollback releases transaction advisory lock").isTrue();
        }
      } finally {
        q.execute(
            "DROP TRIGGER marker_commit_failure ON place_marker_cells;DROP FUNCTION marker_commit_failure()");
      }
    }
  }
}
