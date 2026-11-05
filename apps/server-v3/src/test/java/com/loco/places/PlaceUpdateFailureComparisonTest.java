package com.loco.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.loco.compat.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
class PlaceUpdateFailureComparisonTest extends CompatibilityTestBase {
  static Stream<Arguments> failures() {
    return Stream.of(
        Arguments.of("place", 0),
        Arguments.of("old", 1),
        Arguments.of("old", 2),
        Arguments.of("old", 3),
        Arguments.of("new", 1),
        Arguments.of("new", 2),
        Arguments.of("new", 3));
  }

  @ParameterizedTest
  @MethodSource("failures")
  void failureRetainsExactlyTheEarlierCommits(String phase, int precision) throws Exception {
    environment().reset("places/seed.sql");
    var rules = PlaceWriteSupport.rules();
    DatabaseSnapshot before;
    String id = PlaceObservationTest.place(6), path = "/api/places/" + id;
    try (var db = environment().connect(SharedHarness.Side.V2)) {
      before = DatabaseSnapshot.capture(db, rules);
    }
    for (var side : SharedHarness.Side.values())
      try (var db = environment().connect(side)) {
        String base = side == SharedHarness.Side.V2 ? v2Url() : v3Url(),
            token = PlaceWriteSupport.token(environment());
        assertThat(
                request(base, path, HttpMethod.PUT, token, java.util.Map.of())
                    .getStatusCode()
                    .value())
            .isEqualTo(200);
        var seeded = DatabaseSnapshot.capture(db, rules);
        var sql = db.createStatement();
        String table = phase.equals("place") ? "places" : "place_marker_cells";
        String condition =
            phase.equals("place")
                ? "true"
                : "COALESCE(NEW.precision,OLD.precision)="
                    + precision
                    + " AND COALESCE(NEW.\"cellLatKey\",OLD.\"cellLatKey\")='"
                    + CoordinateKeys.fixed(phase.equals("new") ? 38 : 37.4444, precision)
                    + "'";
        sql.execute(
            "CREATE FUNCTION update_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF "
                + condition
                + " THEN RAISE EXCEPTION 'injected'; END IF; IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW; END; $$");
        sql.execute(
            "CREATE TRIGGER update_failure BEFORE INSERT OR UPDATE OR DELETE ON "
                + table
                + " FOR EACH ROW EXECUTE FUNCTION update_failure()");
        try {
          var response =
              request(
                  base,
                  path,
                  HttpMethod.PUT,
                  token,
                  java.util.Map.of("name", "moved", "latitude", 38, "longitude", 128));
          assertThat(response.getStatusCode().value()).isEqualTo(500);
          assertThat(response.getBody())
              .isEqualTo(
                  mapper.readTree("{\"statusCode\":500,\"message\":\"Internal server error\"}"));
          if (phase.equals("place")) seeded.assertUnchanged(DatabaseSnapshot.capture(db, rules));
          else {
            try (var row = sql.executeQuery("SELECT latitude FROM places WHERE id='" + id + "'")) {
              row.next();
              assertThat(row.getDouble(1)).isEqualTo(38);
            }
            try (var rows =
                sql.executeQuery(
                    "SELECT precision,\"cellLatKey\",count FROM place_marker_cells ORDER BY precision")) {
              int found = 0;
              while (rows.next()) {
                int p = rows.getInt(1);
                found++;
                assertThat(rows.getString(2))
                    .isEqualTo(CoordinateKeys.fixed(phase.equals("new") ? 38 : 37.4444, p));
                assertThat(rows.getInt(3)).isEqualTo(1);
                if (phase.equals("old")) assertThat(p).isGreaterThanOrEqualTo(precision);
                else assertThat(p).isLessThan(precision);
              }
              assertThat(found).isEqualTo(phase.equals("old") ? 4 - precision : precision - 1);
            }
          }
        } finally {
          sql.execute("DROP TRIGGER update_failure ON " + table);
          sql.execute("DROP FUNCTION update_failure()");
        }
      }
    try (var left = environment().connect(SharedHarness.Side.V2);
        var right = environment().connect(SharedHarness.Side.V3)) {
      DatabaseSnapshot.compare(
          DatabaseSnapshot.capture(left, rules),
          DatabaseSnapshot.capture(right, rules),
          before.seedIds(),
          rules);
    }
  }
}
