package com.loco.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.loco.compat.*;
import com.loco.maps.SqlQueryCounter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;

@Import(SqlQueryCounter.Configuration.class)
class ReviewReadIntegrationTest extends CompatibilityTestBase {
  protected boolean usesV2Http() {
    return false;
  }

  static java.util.stream.Stream<JsonNode> observations() throws Exception {
    try (var in = ReviewReadIntegrationTest.class.getResourceAsStream("/reviews/http-v2.json")) {
      var rows = new com.fasterxml.jackson.databind.ObjectMapper().readTree(in);
      return java.util.stream.StreamSupport.stream(rows.spliterator(), false)
          .filter(r -> r.path("method").asText().equals("GET"));
    }
  }

  @ParameterizedTest
  @MethodSource("observations")
  void responseDatabaseAndSqlBudget(JsonNode expected) throws Exception {
    ReviewTestSupport.seed(environment());
    try (var db = environment().connect(SharedHarness.Side.V3)) {
      var rules = PlaceWriteSupport.rules();
      var before = DatabaseSnapshot.capture(db, rules);
      String path = expected.path("path").asText();
      int user = expected.path("user").asInt(), status = expected.path("status").asInt();
      SqlQueryCounter.reset();
      var response =
          request(
              v3Url(),
              path,
              HttpMethod.GET,
              environment().token("valid", ReviewTestSupport.user(user), "ignored@example.test"),
              null);
      assertThat(response.getStatusCode().value()).isEqualTo(status);
      assertThat(ReviewTestSupport.normalize(response.getBody())).isEqualTo(expected.get("body"));
      boolean privateNonOwner =
          (path.contains(PlaceObservationTest.place(6))
                  || path.contains(PlaceObservationTest.place(7)))
              && user != 2;
      int budget =
          path.endsWith("/reviews/me")
              ? 2
              : 2 + (privateNonOwner ? 1 : 0) + (status == 200 ? 2 : 0);
      assertThat(SqlQueryCounter.statements()).as(path + " SQL").hasSize(budget);
      before.assertUnchanged(DatabaseSnapshot.capture(db, rules));
    }
  }
}
