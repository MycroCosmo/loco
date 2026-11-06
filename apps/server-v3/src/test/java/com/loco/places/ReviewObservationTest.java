package com.loco.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.loco.compat.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
class ReviewObservationTest extends CompatibilityTestBase {
  @Test
  void recordsInputsPermissionsAndAggregation() throws Exception {
    var rows = mapper.createArrayNode();
    var inputs =
        new ArrayList<>(
            List.of(
                "{}",
                "[]",
                "[1]",
                "{\"unknown\":1}",
                "{\"rating\":null,\"content\":null}",
                "{\"rating\":\"3\",\"content\":3}",
                "{\"rating\":3.5,\"content\":false}"));
    for (String rating : List.of("0", "1", "5", "6", "-1", "true", "[]", "{}"))
      inputs.add("{\"rating\":" + rating + ",\"content\":\"review\"}");
    for (String content :
        List.of(
            "",
            " ",
            " \n\t ",
            "\u00a0\ufeff",
            "  trimmed  ",
            "x".repeat(1000),
            "x".repeat(1001),
            " " + "x".repeat(1000),
            "😀".repeat(1000),
            "😀".repeat(1001)))
      inputs.add(mapper.createObjectNode().put("rating", 4).put("content", content).toString());
    inputs.add("{\"rating\":3,\"content\":\"ok\",\"userId\":\"override\"}");
    for (String input : inputs) observe(rows, "POST", ReviewTestSupport.path(1), 1, input);
    for (int n : new int[] {1, 2, 3, 5, 6, 7})
      for (String method : List.of("GET", "POST", "DELETE"))
        observe(
            rows,
            method,
            ReviewTestSupport.path(n) + (method.equals("DELETE") ? "/me" : ""),
            1,
            method.equals("POST") ? "{\"rating\":4,\"content\":\"changed\"}" : null);
    for (int user : new int[] {2, 3})
      for (int place : new int[] {1, 6, 7})
        for (String method : List.of("GET", "POST", "DELETE"))
          observe(
              rows,
              method,
              ReviewTestSupport.path(place) + (method.equals("DELETE") ? "/me" : ""),
              user,
              method.equals("POST") ? "{\"rating\":2,\"content\":\"other\"}" : null);
    for (String method : List.of("GET", "POST", "DELETE"))
      observe(
          rows,
          method,
          "/api/places/missing/reviews" + (method.equals("DELETE") ? "/me" : ""),
          1,
          method.equals("POST") ? "{\"rating\":3,\"content\":\"ok\"}" : null);
    observe(rows, "POST", "/api/places/missing/reviews", 1, "{\"rating\":3,\"content\":\" \"}");
    for (int u = 1; u <= 3; u++) observe(rows, "GET", "/api/places/reviews/me", u, null);
    writeOrCompare("http-v2.json", rows);
  }

  private void observe(
      com.fasterxml.jackson.databind.node.ArrayNode rows,
      String method,
      String path,
      int user,
      String input)
      throws Exception {
    ReviewTestSupport.seed(environment());
    var result =
        request(
            v2Url(),
            path,
            HttpMethod.valueOf(method),
            environment().token("valid", ReviewTestSupport.user(user), "ignored@example.test"),
            input == null ? null : mapper.readTree(input));
    var row =
        rows.addObject()
            .put("method", method)
            .put("path", path)
            .put("user", user)
            .put("input", input)
            .put("status", result.getStatusCode().value());
    row.set("body", ReviewTestSupport.normalize(result.getBody()));
    var summaries = mapper.createArrayNode();
    try (var db = environment().connect(SharedHarness.Side.V2);
        var r =
            db.createStatement()
                .executeQuery(
                    "SELECT p.id,count(r.id),coalesce(avg(r.rating),0)::float8 FROM places p LEFT JOIN place_reviews r ON r.\"placeId\"=p.id GROUP BY p.id ORDER BY p.id")) {
      while (r.next())
        summaries
            .addObject()
            .put("placeId", r.getString(1))
            .put("count", r.getInt(2))
            .put("average", r.getDouble(3));
    }
    row.set("summaries", summaries);
  }

  @Test
  void recordsConcurrentCreationAndReplacement() throws Exception {
    var rows = mapper.createArrayNode();
    for (boolean existing : List.of(false, true)) {
      ReviewTestSupport.seed(environment());
      String place = PlaceObservationTest.place(existing ? 2 : 1),
          token = PlaceWriteSupport.token(environment());
      var responses =
          DatabaseRequestRace.run(
              environment(),
              SharedHarness.Side.V2,
              existing ? "place_reviews" : "places",
              existing ? "eeeeeeee-eeee-4eee-8eee-000000000001" : place,
              "place_reviews",
              () ->
                  request(
                      v2Url(),
                      "/api/places/" + place + "/reviews",
                      HttpMethod.POST,
                      token,
                      Map.of("rating", 4, "content", "race review")));
      var row = rows.addObject().put("existing", existing);
      row.set(
          "statuses",
          mapper.valueToTree(
              responses.stream().map(r -> r.getStatusCode().value()).sorted().toList()));
      try (var db = environment().connect(SharedHarness.Side.V2);
          var r =
              db.createStatement()
                  .executeQuery(
                      "SELECT id,rating,content FROM place_reviews WHERE \"placeId\"='"
                          + place
                          + "' AND \"userId\"='"
                          + SharedHarness.Environment.USER_ID
                          + "'")) {
        assertThat(r.next()).isTrue();
        row.put(
                "preservedId",
                !existing || r.getString(1).equals("eeeeeeee-eeee-4eee-8eee-000000000001"))
            .put("rating", r.getInt(2))
            .put("content", r.getString(3));
        assertThat(r.next()).isFalse();
      }
    }
    writeOrCompare("race-v2.json", rows);
  }

  private void writeOrCompare(String name, JsonNode value) throws Exception {
    if (Boolean.getBoolean("reviews.observe")) {
      var path = Path.of("build/reports/reviews", name);
      Files.createDirectories(path.getParent());
      mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    } else
      try (var input = getClass().getResourceAsStream("/reviews/" + name)) {
        assertThat(value).isEqualTo(mapper.readTree(input));
      }
  }
}
