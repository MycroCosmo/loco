package com.loco.favorites;

import static org.assertj.core.api.Assertions.assertThat;

import com.loco.compat.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
class SubmissionRaceComparisonTest extends SubmissionTestSupport {
  @ParameterizedTest
  @ValueSource(strings = {"favorite-add", "favorite-delete", "feedback", "report"})
  void preservesObservedConcurrency(String scenario) throws Exception {
    seed();
    String path =
        scenario.startsWith("favorite")
            ? "/api/favorites" + (scenario.endsWith("delete") ? "/" + favorite(1) : "")
            : scenario.equals("feedback") ? "/api/feedback" : "/api/reports";
    String table =
        scenario.equals("favorite-delete")
            ? "favorites"
            : scenario.equals("favorite-add") ? "maps" : "users";
    String id =
        scenario.equals("favorite-delete")
            ? favorite(1)
            : scenario.equals("favorite-add") ? map(3) : user(1);
    String target =
        scenario.startsWith("favorite")
            ? "favorites"
            : scenario.equals("feedback") ? "feedbacks" : "reports";
    var method = scenario.equals("favorite-delete") ? HttpMethod.DELETE : HttpMethod.POST;
    var input =
        scenario.equals("favorite-add")
            ? mapper.createObjectNode().put("mapId", map(3))
            : scenario.equals("feedback")
                ? mapper.createObjectNode().put("content", "same content")
                : SubmissionObservationTest.validReport();
    var expected = SubmissionComparisonTest.fixture(scenario + "-race");
    environment().reserveV2Requests(method.name(), path, 2);
    String token = environment().token("valid", user(1), "ignored@example.test");
    for (var side : SharedHarness.Side.values())
      try (var db = environment().connect(side)) {
        var before = DatabaseSnapshot.capture(db, rules());
        var responses =
            DatabaseRequestRace.run(
                environment(),
                side,
                table,
                id,
                target,
                () ->
                    request(
                        side == SharedHarness.Side.V2 ? v2Url() : v3Url(),
                        path,
                        method,
                        token,
                        input));
        var result = mapper.createObjectNode();
        var responseRows = result.putArray("responses");
        responses.stream()
            .sorted(Comparator.comparingInt(r -> r.getStatusCode().value()))
            .forEach(
                r ->
                    responseRows
                        .addObject()
                        .put("status", r.getStatusCode().value())
                        .set("body", normalize(r.getBody())));
        result.set("tables", rows(side));
        assertThat(result).isEqualTo(expected);
        before.assertUnchangedExcept(
            DatabaseSnapshot.capture(db, rules()), Set.of("favorites", "feedbacks", "reports"));
      }
  }
}
