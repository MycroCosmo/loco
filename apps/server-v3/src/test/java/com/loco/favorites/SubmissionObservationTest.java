package com.loco.favorites;

import com.loco.compat.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
class SubmissionObservationTest extends SubmissionTestSupport {
  @Test
  void favoriteInputsAndPermissions() throws Exception {
    var data = mapper.createArrayNode();
    for (String body :
        List.of(
            "{}",
            "[]",
            "[1]",
            "{\"mapId\":null}",
            "{\"mapId\":1}",
            "{\"mapId\":false}",
            "{\"mapId\":[]}",
            "{\"mapId\":{}}",
            "{\"mapId\":\"\"}",
            "{\"mapId\":\" \"}",
            "{\"mapId\":\"missing\"}",
            "{\"mapId\":\"" + map(3) + "\",\"extra\":1}",
            "{\"mapId\":\"" + map(3) + "\",\"__proto__\":{},\"constructor\":{}}"))
      observe(data, "POST", "/api/favorites", 1, body);
    for (int u : new int[] {1, 2, 3}) {
      observe(data, "GET", "/api/favorites?limit=1&userId=" + user(2), u, null);
      for (int m = 1; m <= 4; m++)
        observe(data, "POST", "/api/favorites", u, "{\"mapId\":\"" + map(m) + "\"}");
      for (int f = 1; f <= 3; f++)
        observe(data, "DELETE", "/api/favorites/" + favorite(f), u, null);
    }
    observe(data, "DELETE", "/api/favorites/missing", 1, null);
    record("favorites-v2.json", data);
  }

  @Test
  void feedbackBoundaries() throws Exception {
    var data = mapper.createArrayNode();
    for (String input :
        List.of(
            "{}",
            "[]",
            "[1]",
            "{\"content\":null}",
            "{\"content\":1}",
            "{\"content\":false}",
            "{\"content\":[]}",
            "{\"content\":{}}",
            "{\"unknown\":1}")) observe(data, "POST", "/api/feedback", 1, input);
    for (String content :
        List.of(
            "",
            "x".repeat(9),
            "x".repeat(10),
            "x".repeat(1000),
            "x".repeat(1001),
            " ".repeat(10),
            "  content text  ",
            "😀".repeat(9),
            "😀".repeat(10),
            "😀".repeat(1000),
            "😀".repeat(1001),
            "\u0000".repeat(10)))
      observe(
          data,
          "POST",
          "/api/feedback",
          1,
          mapper.createObjectNode().put("content", content).toString());
    observe(
        data,
        "POST",
        "/api/feedback",
        1,
        "{\"content\":\"valid content\",\"userId\":\"" + user(2) + "\"}");
    record("feedback-v2.json", data);
  }

  @Test
  void reportBoundaries() throws Exception {
    var data = mapper.createArrayNode();
    for (String input :
        List.of(
            "{}",
            "[]",
            "[1]",
            "{\"targetType\":null,\"targetId\":null,\"reason\":null,\"detail\":null}",
            "{\"targetType\":1,\"targetId\":1,\"reason\":1,\"detail\":1}",
            "{\"unknown\":1}")) observe(data, "POST", "/api/reports", 1, input);
    for (String type : List.of("MAP", "PLACE", "USER", "map", "UNKNOWN", ""))
      observe(data, "POST", "/api/reports", 1, validReport().put("targetType", type).toString());
    for (String id : List.of("", " ", "missing", user(1), map(3)))
      observe(data, "POST", "/api/reports", 1, validReport().put("targetId", id).toString());
    for (String reason :
        List.of(
            "",
            "x".repeat(4),
            "x".repeat(5),
            "x".repeat(200),
            "x".repeat(201),
            " ".repeat(5),
            "😀".repeat(5),
            "😀".repeat(200),
            "😀".repeat(201)))
      observe(data, "POST", "/api/reports", 1, validReport().put("reason", reason).toString());
    for (String detail :
        List.of("", "x".repeat(1000), "x".repeat(1001), "😀".repeat(1000), "😀".repeat(1001)))
      observe(data, "POST", "/api/reports", 1, validReport().put("detail", detail).toString());
    for (String detail : List.of("null", "1", "false", "[]", "{}")) {
      var body = validReport();
      body.set("detail", mapper.readTree(detail));
      observe(data, "POST", "/api/reports", 1, body.toString());
    }
    observe(data, "POST", "/api/reports", 1, validReport().put("reporterId", user(2)).toString());
    record("reports-v2.json", data);
  }

  static com.fasterxml.jackson.databind.node.ObjectNode validReport() {
    return new com.fasterxml.jackson.databind.ObjectMapper()
        .createObjectNode()
        .put("targetType", "MAP")
        .put("targetId", "missing")
        .put("reason", "reason");
  }

  void observe(
      com.fasterxml.jackson.databind.node.ArrayNode data,
      String method,
      String path,
      int user,
      String input)
      throws Exception {
    seed();
    var r =
        request(
            v2Url(),
            path,
            HttpMethod.valueOf(method),
            environment().token("valid", user(user), "ignored@example.test"),
            input == null ? null : mapper.readTree(input));
    var row =
        data.addObject()
            .put("method", method)
            .put("path", path)
            .put("user", user)
            .put("input", input)
            .put("status", r.getStatusCode().value());
    row.set("body", normalize(r.getBody()));
    row.set("tables", rows(SharedHarness.Side.V2));
  }

  @ParameterizedTest
  @ValueSource(strings = {"favorite-add", "favorite-delete", "feedback", "report"})
  void concurrentRequests(String scenario) throws Exception {
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
                : validReport();
    environment().reserveV2Requests(method.name(), path, 2);
    String token = environment().token("valid", user(1), "ignored@example.test");
    var responses =
        DatabaseRequestRace.run(
            environment(),
            SharedHarness.Side.V2,
            table,
            id,
            target,
            () -> request(v2Url(), path, method, token, input));
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
    result.set("tables", rows(SharedHarness.Side.V2));
    record(scenario + "-race-v2.json", result);
  }
}
