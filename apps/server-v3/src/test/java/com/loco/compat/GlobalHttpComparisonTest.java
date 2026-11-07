package com.loco.compat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import java.util.stream.*;
import java.util.zip.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

@Tag("v2-comparison")
class GlobalHttpComparisonTest extends CompatibilityTestBase {
  private static final HttpClient HTTP =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  static Stream<Arguments> cases() throws Exception {
    try (var in = GlobalHttpComparisonTest.class.getResourceAsStream("/globals/http-v2.json")) {
      var cases = new com.fasterxml.jackson.databind.ObjectMapper().readTree(in).path("cases");
      return StreamSupport.stream(cases.spliterator(), false)
          .filter(c -> !c.path("name").asText().startsWith("configured-"))
          .map(c -> Arguments.of(c.path("name").asText(), c));
    }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "nickname=updated",
        "nickname=%ED%95%9C%EA%B8%80",
        "nickname[]=a&nickname[]=b",
        "nickname[name]=a",
        "unknown[field]=x",
        "nickname=",
        "nickname=first&nickname=second",
        "nickname=%FF"
      })
  void formInputReachesRealApiLikeV2(String form) throws Exception {
    environment().reset("places/seed.sql");
    var headers = new org.springframework.http.HttpHeaders();
    headers.setContentType(
        org.springframework.http.MediaType.parseMediaType(
            "application/x-www-form-urlencoded; charset=utf-8"));
    headers.setBearerAuth(
        environment()
            .token("valid", SharedHarness.Environment.USER_ID, SharedHarness.Environment.EMAIL));
    var entity =
        new org.springframework.http.HttpEntity<>(form.getBytes(StandardCharsets.UTF_8), headers);
    var left =
        client.exchange(
            v2Url() + "/api/users/me",
            org.springframework.http.HttpMethod.PUT,
            entity,
            JsonNode.class);
    var right =
        client.exchange(
            v3Url() + "/api/users/me",
            org.springframework.http.HttpMethod.PUT,
            entity,
            JsonNode.class);
    assertThat(right.getStatusCode()).isEqualTo(left.getStatusCode());
    JsonComparison.compare(
        left.getBody(),
        right.getBody(),
        left.getBody().has("updatedAt") ? Map.of("/updatedAt", "server write time") : Map.of());
    var rules =
        new DatabaseSnapshot.Rules(
            Map.of("map_members", List.of("__map", "__user")),
            Map.of(
                "map_members",
                "SELECT mm.*,m.title AS __map,u.email AS __user FROM map_members mm JOIN maps m ON m.id=mm.\"mapId\" JOIN users u ON u.id=mm.\"userId\""));
    try (var a = environment().connect(SharedHarness.Side.V2);
        var b = environment().connect(SharedHarness.Side.V3)) {
      var leftState = DatabaseSnapshot.capture(a, rules);
      DatabaseSnapshot.compare(
          leftState, DatabaseSnapshot.capture(b, rules), leftState.seedIds(), rules);
    }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"application/json", "application/x-www-form-urlencoded"})
  void oversizedProtectedRequestFailsBeforeAuthenticationAndCors(String type) throws Exception {
    var headers = new org.springframework.http.HttpHeaders();
    headers.setContentType(org.springframework.http.MediaType.parseMediaType(type));
    headers.setOrigin("http://localhost:8080");
    byte[] body =
        (type.equals("application/json")
                ? "{\"x\":\"" + "a".repeat(10485761) + "\"}"
                : "x=" + "a".repeat(10485761))
            .getBytes(StandardCharsets.UTF_8);
    for (String base : List.of(v2Url(), v3Url())) {
      var result =
          client.exchange(
              base + "/api/users/me",
              org.springframework.http.HttpMethod.PUT,
              new org.springframework.http.HttpEntity<>(body, headers),
              JsonNode.class);
      assertThat(result.getStatusCode().value()).isEqualTo(413);
      assertThat(result.getHeaders())
          .doesNotContainKeys(
              "Access-Control-Allow-Origin", "Content-Security-Policy", "X-RateLimit-Limit");
      assertThat(result.getBody())
          .isEqualTo(
              mapper.readTree("{\"statusCode\":413,\"message\":\"request entity too large\"}"));
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void matchesRecordedOriginalHttp(String name, JsonNode fixture) throws Exception {
    environment().reset("places/seed.sql");
    Path file = environment().uploads.resolve("__global.txt");
    Files.createDirectories(file.getParent());
    Files.writeString(file, "global-fixture\n");
    Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2020-01-01T00:00:00Z")));
    try (var db = environment().connect(SharedHarness.Side.V3);
        var sql = db.createStatement()) {
      var rules = DatabaseSnapshot.Rules.defaults();
      var before = DatabaseSnapshot.capture(db, rules);
      boolean failure = name.endsWith("-failure");
      if (failure) {
        sql.execute(
            "CREATE FUNCTION harness_global_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'global HTTP fixture failure'; END $$");
        sql.execute(
            "CREATE TRIGGER harness_global_failure BEFORE INSERT ON feedbacks FOR EACH ROW EXECUTE FUNCTION harness_global_failure()");
      }
      try {
        var actual = send(fixture.path("request"));
        var expected = fixture.path("response");
        assertThat(actual.statusCode()).isEqualTo(expected.path("status").asInt());
        String text = new String(actual.body(), StandardCharsets.UTF_8);
        if (expected.path("body").isTextual())
          assertThat(text).isEqualTo(expected.path("body").asText());
        else
          JsonComparison.compare(
              expected.path("body"),
              mapper.readTree(text),
              Set.of("malformed-json", "json-null", "json-scalar").contains(name)
                  ? Map.of("/message", "JSON parser runtime wording, existing explicit exception")
                  : Map.of());
        ResponseHeaders.compare(
            expected.path("headers"), actual, fixture.path("request").path("method").asText());
        before.assertUnchanged(DatabaseSnapshot.capture(db, rules));
      } finally {
        if (failure) {
          sql.execute("DROP TRIGGER harness_global_failure ON feedbacks");
          sql.execute("DROP FUNCTION harness_global_failure()");
        }
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  private HttpResponse<byte[]> send(JsonNode recipe) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create(v3Url() + recipe.path("path").asText()))
            .timeout(Duration.ofSeconds(20));
    String token =
        environment()
            .token("valid", SharedHarness.Environment.USER_ID, SharedHarness.Environment.EMAIL);
    recipe
        .path("headers")
        .properties()
        .forEach(
            e ->
                builder.header(
                    e.getKey(),
                    e.getValue().asText().equals("$valid")
                        ? "Bearer " + token
                        : e.getValue().asText()));
    byte[] body = body(recipe.path("body"));
    var publisher =
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : recipe.path("chunked").asBoolean()
                ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body))
                : HttpRequest.BodyPublishers.ofByteArray(body);
    return HTTP.send(
        builder.method(recipe.path("method").asText(), publisher).build(),
        HttpResponse.BodyHandlers.ofByteArray());
  }

  static byte[] body(JsonNode recipe) throws IOException {
    if (recipe.isMissingNode()) return null;
    int size = recipe.path("bytes").asInt();
    String text =
        switch (recipe.path("kind").asText()) {
          case "json-size" -> "{\"padding\":\"" + "a".repeat(size - 14) + "\"}";
          case "json-utf8" ->
              "{\"padding\":\"" + "한".repeat((size - 14) / 3) + "a".repeat((size - 14) % 3) + "\"}";
          case "form-size" -> "padding=" + "a".repeat(size - 8);
          default -> recipe.path("text").asText();
        };
    byte[] raw = text.getBytes(StandardCharsets.UTF_8);
    if (!recipe.has("compression")) return raw;
    var bytes = new ByteArrayOutputStream();
    try (var compressor =
        recipe.path("compression").asText().equals("gzip")
            ? new GZIPOutputStream(bytes)
            : new DeflaterOutputStream(bytes)) {
      compressor.write(raw);
    }
    return bytes.toByteArray();
  }

  @Test
  void reportsPoweredByDifferenceAndKeepsParserBeforeCorsAndQuota() throws Exception {
    var recipe =
        mapper.readTree(
            "{\"method\":\"POST\",\"path\":\"/api/auth/login\",\"headers\":{\"Origin\":\"http://localhost:8080\",\"Content-Type\":\"application/json\"},\"body\":{\"text\":\"{\"}}");
    var response = send(recipe);
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.headers().map())
        .doesNotContainKeys(
            "x-powered-by",
            "content-security-policy",
            "access-control-allow-origin",
            "x-ratelimit-limit");
    try (var in = getClass().getResourceAsStream("/globals/http-v2.json")) {
      var baseline = mapper.readTree(in).path("cases");
      assertThat(
              StreamSupport.stream(baseline.spliterator(), false)
                  .filter(c -> c.path("name").asText().equals("malformed-json"))
                  .findFirst()
                  .orElseThrow()
                  .path("response")
                  .path("headers")
                  .path("x-powered-by")
                  .asText())
          .isEqualTo("Express");
    }
    environment()
        .knownDifferences
        .add(
            Map.of(
                "case",
                "http.runtime-header",
                "kind",
                "intentional",
                "reason",
                "10-1 A: omit Express runtime identification only; parser-before-Helmet/CORS preserved",
                "v2",
                "X-Powered-By: Express on early parser errors",
                "v3",
                "header absent"));
  }

  @Test
  void apiConditionalRequestUsesItsOwnEtag() throws Exception {
    environment().reset("places/seed.sql");
    String token =
        environment()
            .token("valid", SharedHarness.Environment.USER_ID, SharedHarness.Environment.EMAIL);
    var initial =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(v3Url() + "/api/users/me"))
                .header("Authorization", "Bearer " + token)
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
    String etag = initial.headers().firstValue("etag").orElseThrow();
    var cached =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(v3Url() + "/api/users/me"))
                .header("Authorization", "Bearer " + token)
                .header("If-None-Match", etag)
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertThat(cached.statusCode()).isEqualTo(304);
    assertThat(cached.body()).isEmpty();
  }
}
