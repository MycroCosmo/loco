package com.loco.users;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.loco.compat.CompatibilityTestBase;
import com.loco.compat.DatabaseSnapshot;
import com.loco.compat.JsonComparison;
import com.loco.compat.SharedHarness;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
class UserProfileComparisonTest extends CompatibilityTestBase {
  static Stream<JsonNode> profileCases() throws Exception {
    try (var stream =
        UserProfileComparisonTest.class.getResourceAsStream("/users/profile-v2.json")) {
      var rows = new com.fasterxml.jackson.databind.ObjectMapper().readTree(stream);
      return StreamSupport.stream(rows.spliterator(), false);
    }
  }

  @ParameterizedTest
  @MethodSource("profileCases")
  void profileMatchesObservedV2(JsonNode expected) throws Exception {
    environment().reset("compat/seed.sql");
    String input = expected.path("input").asText();
    String token =
        environment()
            .token("valid", SharedHarness.Environment.USER_ID, SharedHarness.Environment.EMAIL);
    JsonNode first = null;
    for (var side : SharedHarness.Side.values())
      try (var db = environment().connect(side)) {
        var before = DatabaseSnapshot.capture(db, DatabaseSnapshot.Rules.defaults());
        var result =
            request(
                side == SharedHarness.Side.V2 ? v2Url() : v3Url(),
                "/api/users/me",
                input.equals("GET") ? HttpMethod.GET : HttpMethod.PUT,
                token,
                input.equals("GET") || input.equals("null-body") ? null : mapper.readTree(input));
        assertThat(result.getStatusCode().value()).isEqualTo(expected.path("status").asInt());
        if (first == null) first = result.getBody();
        else
          JsonComparison.compare(
              first,
              result.getBody(),
              first.has("updatedAt") ? Map.of("/updatedAt", "각 서버 처리 시각") : Map.of());
        try (var row =
            db.createStatement()
                .executeQuery("SELECT nickname,\"profileImageUrl\",\"updatedAt\" FROM users")) {
          row.next();
          assertThat(row.getString(1)).isEqualTo(expected.path("nickname").asText());
          assertThat(row.getString(2))
              .isEqualTo(
                  expected.path("profileImageUrl").isNull()
                      ? null
                      : expected.path("profileImageUrl").asText());
          boolean changed =
              !row.getTimestamp(3)
                  .toLocalDateTime()
                  .equals(java.time.LocalDateTime.of(2026, 1, 1, 0, 0));
          assertThat(changed).isEqualTo(expected.path("updatedAtChanged").asBoolean());
          if (result.getBody().has("updatedAt"))
            assertThat(result.getBody().path("updatedAt").asText())
                .isEqualTo(UserResponses.timestamp(row.getTimestamp(3).toLocalDateTime()));
        }
        if (!expected.path("updatedAtChanged").asBoolean())
          before.assertUnchanged(DatabaseSnapshot.capture(db, DatabaseSnapshot.Rules.defaults()));
      }
    compareDatabases();
  }

  @ParameterizedTest
  @ValueSource(strings = {"KAKAO", "NAVER"})
  void oauthProfileAndStoredImageAreReturned(String provider) throws Exception {
    environment().reset("compat/seed.sql");
    for (var side : SharedHarness.Side.values())
      try (var db = environment().connect(side)) {
        db.createStatement()
            .execute(
                "UPDATE users SET provider='"
                    + provider
                    + "',\"profileImageUrl\"='https://example.com/avatar.webp'");
      }
    String token =
        environment().token("valid", SharedHarness.Environment.USER_ID, "stale@example.test");
    var left = request(v2Url(), "/api/users/me", HttpMethod.GET, token, null);
    var right = request(v3Url(), "/api/users/me", HttpMethod.GET, token, null);
    assertThat(right.getStatusCode().value()).isEqualTo(200);
    JsonComparison.compare(left.getBody(), right.getBody(), Map.of());
    assertThat(right.getBody().path("provider").asText()).isEqualTo(provider);
  }

  @Test
  void profileDatabaseFailureRollsBack() throws Exception {
    environment().reset("compat/seed.sql");
    String token =
        environment()
            .token("valid", SharedHarness.Environment.USER_ID, SharedHarness.Environment.EMAIL);
    for (var side : SharedHarness.Side.values())
      try (var db = environment().connect(side)) {
        db.createStatement()
            .execute(
                "CREATE FUNCTION profile_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'profile failure'; END; $$");
        db.createStatement()
            .execute(
                "CREATE TRIGGER profile_fail BEFORE UPDATE ON users FOR EACH ROW EXECUTE FUNCTION profile_fail()");
        try {
          var before = DatabaseSnapshot.capture(db, DatabaseSnapshot.Rules.defaults());
          var result =
              request(
                  side == SharedHarness.Side.V2 ? v2Url() : v3Url(),
                  "/api/users/me",
                  HttpMethod.PUT,
                  token,
                  Map.of("nickname", "new"));
          assertThat(result.getStatusCode().value()).isEqualTo(500);
          assertThat(result.getBody())
              .isEqualTo(
                  mapper.readTree("{\"statusCode\":500,\"message\":\"Internal server error\"}"));
          before.assertUnchanged(DatabaseSnapshot.capture(db, DatabaseSnapshot.Rules.defaults()));
        } finally {
          db.createStatement().execute("DROP TRIGGER profile_fail ON users");
          db.createStatement().execute("DROP FUNCTION profile_fail()");
        }
      }
  }

  private void compareDatabases() throws Exception {
    var rules = DatabaseSnapshot.Rules.defaults();
    try (var left = environment().connect(SharedHarness.Side.V2);
        var right = environment().connect(SharedHarness.Side.V3)) {
      var before = DatabaseSnapshot.capture(left, rules);
      DatabaseSnapshot.compare(
          before, DatabaseSnapshot.capture(right, rules), before.seedIds(), rules);
    }
  }
}
