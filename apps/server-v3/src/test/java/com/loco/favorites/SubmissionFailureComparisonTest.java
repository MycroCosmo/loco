package com.loco.favorites;

import static org.assertj.core.api.Assertions.assertThat;

import com.loco.compat.*;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
class SubmissionFailureComparisonTest extends SubmissionTestSupport {
  @ParameterizedTest
  @CsvSource({
    "favorites,POST,false",
    "favorites,DELETE,false",
    "feedbacks,POST,false",
    "reports,POST,false",
    "favorites,POST,true",
    "favorites,DELETE,true",
    "feedbacks,POST,true",
    "reports,POST,true"
  })
  void statementOrCommitFailureReturns500AndLeavesAllRowsUnchanged(
      String table, String method, boolean deferred) throws Exception {
    seed();
    String path =
        table.equals("favorites")
            ? "/api/favorites" + (method.equals("DELETE") ? "/" + favorite(1) : "")
            : table.equals("feedbacks") ? "/api/feedback" : "/api/reports";
    Object input =
        table.equals("favorites")
            ? Map.of("mapId", map(3))
            : table.equals("feedbacks")
                ? Map.of("content", "rollback content")
                : SubmissionObservationTest.validReport();
    for (var side : SharedHarness.Side.values())
      try (var db = environment().connect(side);
          var sql = db.createStatement()) {
        var before = DatabaseSnapshot.capture(db, rules());
        sql.execute(
            "CREATE FUNCTION submission_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected'; END; $$");
        sql.execute(
            "CREATE "
                + (deferred ? "CONSTRAINT " : "")
                + "TRIGGER submission_failure "
                + (deferred ? "AFTER " : "BEFORE ")
                + "INSERT OR DELETE ON "
                + table
                + (deferred ? " DEFERRABLE INITIALLY DEFERRED" : "")
                + " FOR EACH ROW EXECUTE FUNCTION submission_failure()");
        try {
          var response =
              request(
                  side == SharedHarness.Side.V2 ? v2Url() : v3Url(),
                  path,
                  HttpMethod.valueOf(method),
                  environment().token("valid", user(1), "ignored@example.test"),
                  input);
          assertThat(response.getStatusCode().value()).isEqualTo(500);
          assertThat(response.getBody())
              .isEqualTo(
                  mapper.readTree("{\"statusCode\":500,\"message\":\"Internal server error\"}"));
          before.assertUnchanged(DatabaseSnapshot.capture(db, rules()));
        } finally {
          sql.execute("DROP TRIGGER submission_failure ON " + table);
          sql.execute("DROP FUNCTION submission_failure()");
        }
      }
  }
}
