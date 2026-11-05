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
class PlaceReadIntegrationTest extends CompatibilityTestBase {
  protected boolean usesV2Http() {
    return false;
  }

  static java.util.stream.Stream<JsonNode> observations() throws Exception {
    try (var in = PlaceReadIntegrationTest.class.getResourceAsStream("/places/read-v2.json")) {
      var rows = new com.fasterxml.jackson.databind.ObjectMapper().readTree(in);
      return java.util.stream.StreamSupport.stream(rows.spliterator(), false);
    }
  }

  @ParameterizedTest
  @MethodSource("observations")
  void responseAndSqlBudget(JsonNode expected) throws Exception {
    environment().reset("places/seed.sql");
    var rules = DatabaseSnapshot.Rules.defaults();
    try (var db = environment().connect(SharedHarness.Side.V3)) {
      var before = DatabaseSnapshot.capture(db, rules);
      SqlQueryCounter.reset();
      String path = expected.path("path").asText();
      var response =
          request(
              v3Url(),
              path,
              HttpMethod.GET,
              environment()
                  .token(
                      "valid", SharedHarness.Environment.USER_ID, SharedHarness.Environment.EMAIL),
              null);
      assertThat(response.getStatusCode().value())
          .as(path)
          .isEqualTo(expected.path("status").asInt());
      assertThat(response.getBody()).as(path).isEqualTo(expected.get("body"));
      int status = expected.path("status").asInt(), budget;
      if (status == 400) budget = 1;
      else if (path.startsWith("/api/maps/")) {
        budget = status == 404 ? 2 : 3;
        if (path.contains(PlaceObservationTest.map(2))) budget = 4;
        if (path.contains(PlaceObservationTest.map(3))) budget = 3;
        if (status == 500) budget = 2;
      } else
        budget =
            path.endsWith(PlaceObservationTest.place(6))
                    || path.endsWith(PlaceObservationTest.place(7))
                ? 3
                : 2;
      assertThat(SqlQueryCounter.statements()).as(path + " SQL").hasSize(budget);
      before.assertUnchanged(DatabaseSnapshot.capture(db, rules));
    }
  }
}
