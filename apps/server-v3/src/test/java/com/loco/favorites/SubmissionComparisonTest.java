package com.loco.favorites;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.loco.compat.*;
import com.loco.maps.SqlQueryCounter;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
@Import(SqlQueryCounter.Configuration.class)
class SubmissionComparisonTest extends SubmissionTestSupport {
  static JsonNode fixture(String name) throws Exception {
    try (var in =
        SubmissionComparisonTest.class.getResourceAsStream("/submissions/" + name + "-v2.json")) {
      return new com.fasterxml.jackson.databind.ObjectMapper().readTree(in);
    }
  }

  static java.util.stream.Stream<Arguments> cases() throws Exception {
    var result = new ArrayList<Arguments>();
    for (String file : List.of("favorites", "feedback", "reports"))
      for (var row : fixture(file)) result.add(Arguments.of(file, row));
    return result.stream();
  }

  @ParameterizedTest
  @MethodSource("cases")
  void matchesObservedResponseAndEveryDatabaseColumn(String file, JsonNode row) throws Exception {
    seed();
    String method = row.path("method").asText(),
        path = row.path("path").asText(),
        token =
            environment().token("valid", user(row.path("user").asInt()), "ignored@example.test");
    var input = row.path("input").isNull() ? null : mapper.readTree(row.path("input").asText());
    DatabaseSnapshot before;
    try (var db = environment().connect(SharedHarness.Side.V2)) {
      before = DatabaseSnapshot.capture(db, rules());
    }
    for (var side : SharedHarness.Side.values())
      try (var db = environment().connect(side)) {
        var initial = DatabaseSnapshot.capture(db, rules());
        SqlQueryCounter.reset();
        var response =
            request(
                side == SharedHarness.Side.V2 ? v2Url() : v3Url(),
                path,
                HttpMethod.valueOf(method),
                token,
                input);
        int status = row.path("status").asInt();
        assertThat(response.getStatusCode().value())
            .as("%s %s %s", side, path, input)
            .isEqualTo(status);
        JsonComparison.compare(row.get("body"), normalize(response.getBody()), Map.of());
        assertThat(rows(side)).isEqualTo(row.get("tables"));
        if (side == SharedHarness.Side.V3) {
          int count =
              status == 400
                  ? 1
                  : file.equals("favorites")
                      ? (method.equals("GET") ? 2 : status == 403 || status == 404 ? 2 : 3)
                      : 2;
          assertThat(SqlQueryCounter.statements()).hasSize(count);
        }
        if (status >= 400 || method.equals("GET"))
          initial.assertUnchanged(DatabaseSnapshot.capture(db, rules()));
        if (status == 201 && response.getBody().has("id")) {
          String table = file.equals("favorites") ? "favorites" : "feedbacks";
          try (var q = db.prepareStatement("SELECT \"createdAt\" FROM " + table + " WHERE id=?")) {
            q.setString(1, response.getBody().path("id").asText());
            try (var r = q.executeQuery()) {
              assertThat(r.next()).isTrue();
              if (file.equals("favorites"))
                assertThat(response.getBody().path("createdAt").asText())
                    .isEqualTo(
                        com.loco.users.UserResponses.timestamp(
                            r.getTimestamp(1).toLocalDateTime()));
            }
          }
        }
      }
    try (var left = environment().connect(SharedHarness.Side.V2);
        var right = environment().connect(SharedHarness.Side.V3)) {
      DatabaseSnapshot.compare(
          DatabaseSnapshot.capture(left, rules()),
          DatabaseSnapshot.capture(right, rules()),
          before.seedIds(),
          rules());
    }
  }
}
