package com.loco.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.loco.compat.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
class PlaceCreateFailureComparisonTest extends CompatibilityTestBase {
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void earlierCommitsSurviveEachFailure(int precision) throws Exception {
    environment().reset("places/seed.sql");
    var rules = PlaceWriteSupport.rules();
    DatabaseSnapshot before;
    try (var db = environment().connect(SharedHarness.Side.V2)) {
      before = DatabaseSnapshot.capture(db, rules);
    }
    for (var side : SharedHarness.Side.values())
      try (var db = environment().connect(side)) {
        String table = precision == 0 ? "places" : "place_marker_cells";
        var sql = db.createStatement();
        sql.execute(
            "CREATE FUNCTION place_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + (precision == 0 ? "" : "IF NEW.precision=" + precision + " THEN ")
                + "RAISE EXCEPTION 'injected'; "
                + (precision == 0 ? "" : "END IF; RETURN NEW;")
                + " END; $$");
        sql.execute(
            "CREATE TRIGGER place_failure BEFORE INSERT ON "
                + table
                + " FOR EACH ROW EXECUTE FUNCTION place_failure()");
        try {
          var response =
              request(
                  side == SharedHarness.Side.V2 ? v2Url() : v3Url(),
                  PlaceWriteSupport.createPath(1),
                  HttpMethod.POST,
                  PlaceWriteSupport.token(environment()),
                  mapper.readTree(PlaceCreateObservationTest.MINIMAL));
          assertThat(response.getStatusCode().value()).isEqualTo(500);
          assertThat(response.getBody())
              .isEqualTo(
                  mapper.readTree("{\"statusCode\":500,\"message\":\"Internal server error\"}"));
          try (var rows =
              sql.executeQuery(
                  "SELECT (SELECT count(*) FROM places) AS places,(SELECT count(*) FROM place_marker_cells) AS cells")) {
            rows.next();
            assertThat(rows.getInt("places")).isEqualTo(precision == 0 ? 7 : 8);
            assertThat(rows.getInt("cells")).isEqualTo(Math.max(0, precision - 1));
          }
        } finally {
          sql.execute("DROP TRIGGER place_failure ON " + table);
          sql.execute("DROP FUNCTION place_failure()");
        }
      }
    try (var left = environment().connect(SharedHarness.Side.V2);
        var right = environment().connect(SharedHarness.Side.V3)) {
      assertThat(
              DatabaseSnapshot.capture(left, rules)
                  .normalized(before.seedIds(), rules)
                  .get("place_marker_cells"))
          .isEqualTo(
              DatabaseSnapshot.capture(right, rules)
                  .normalized(before.seedIds(), rules)
                  .get("place_marker_cells"));
      DatabaseSnapshot.compare(
          DatabaseSnapshot.capture(left, rules),
          DatabaseSnapshot.capture(right, rules),
          before.seedIds(),
          rules);
    }
  }
}
