package com.loco.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.loco.compat.*;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
class ReviewFailureComparisonTest extends CompatibilityTestBase {
  @ParameterizedTest
  @CsvSource({"POST,false", "DELETE,false", "POST,true", "DELETE,true"})
  void writeFailureRollsBackButResponseFailureDoesNot(String method, boolean afterWrite)
      throws Exception {
    ReviewTestSupport.seed(environment());
    var rules = PlaceWriteSupport.rules();
    DatabaseSnapshot before;
    try (var db = environment().connect(SharedHarness.Side.V2)) {
      before = DatabaseSnapshot.capture(db, rules);
    }
    for (var side : SharedHarness.Side.values())
      try (var db = environment().connect(side);
          var sql = db.createStatement()) {
        sql.execute(
            "CREATE FUNCTION review_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + (afterWrite
                    ? "DELETE FROM map_members WHERE \"mapId\"='"
                        + PlaceObservationTest.map(2)
                        + "' AND \"userId\"='"
                        + ReviewTestSupport.user(1)
                        + "'; RETURN NULL;"
                    : "RAISE EXCEPTION 'injected';")
                + " END; $$");
        sql.execute(
            "CREATE TRIGGER review_failure "
                + (afterWrite ? "AFTER" : "BEFORE")
                + " INSERT OR UPDATE OR DELETE ON place_reviews FOR EACH ROW EXECUTE FUNCTION review_failure()");
        try {
          var response =
              request(
                  side == SharedHarness.Side.V2 ? v2Url() : v3Url(),
                  ReviewTestSupport.path(6) + (method.equals("DELETE") ? "/me" : ""),
                  HttpMethod.valueOf(method),
                  PlaceWriteSupport.token(environment()),
                  Map.of("rating", 2, "content", "committed"));
          assertThat(response.getStatusCode().value()).isEqualTo(afterWrite ? 403 : 500);
          assertThat(response.getBody())
              .isEqualTo(
                  mapper.readTree(
                      afterWrite
                          ? "{\"statusCode\":403,\"message\":\"접근 권한이 없습니다.\",\"error\":\"Forbidden\"}"
                          : "{\"statusCode\":500,\"message\":\"Internal server error\"}"));
          if (!afterWrite) before.assertUnchanged(DatabaseSnapshot.capture(db, rules));
          else
            try (var rows =
                sql.executeQuery(
                    "SELECT content FROM place_reviews WHERE \"placeId\"='"
                        + PlaceObservationTest.place(6)
                        + "' AND \"userId\"='"
                        + ReviewTestSupport.user(1)
                        + "'")) {
              assertThat(rows.next()).isEqualTo(method.equals("POST"));
              if (method.equals("POST")) assertThat(rows.getString(1)).isEqualTo("committed");
            }
        } finally {
          sql.execute("DROP TRIGGER review_failure ON place_reviews");
          sql.execute("DROP FUNCTION review_failure()");
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
