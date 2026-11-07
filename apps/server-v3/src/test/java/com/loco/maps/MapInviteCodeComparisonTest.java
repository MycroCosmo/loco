package com.loco.maps;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.loco.compat.CompatibilityTestBase;
import com.loco.compat.DatabaseRequestRace;
import com.loco.compat.DatabaseSnapshot;
import com.loco.compat.JsonComparison;
import com.loco.compat.SharedHarness;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
@Import(SqlQueryCounter.Configuration.class)
class MapInviteCodeComparisonTest extends CompatibilityTestBase {
  record Case(String id, String kind, String user, int status, int queries) {}

  static List<Case> cases() {
    String me = SharedHarness.Environment.USER_ID;
    return List.of(
        new Case(MapObservationTest.id(2), "valid", me, 201, 5),
        new Case(MapObservationTest.id(1), "valid", me, 400, 2),
        new Case(MapObservationTest.id(4), "valid", me, 403, 2),
        new Case(MapObservationTest.id(5), "valid", me, 403, 2),
        new Case("missing", "valid", me, 404, 2),
        new Case(MapObservationTest.id(2), "missing", me, 401, 0),
        new Case(MapObservationTest.id(2), "malformed", me, 401, 0),
        new Case(MapObservationTest.id(2), "expired", me, 401, 0),
        new Case(MapObservationTest.id(2), "wrong-signature", me, 401, 0),
        new Case(MapObservationTest.id(2), "valid", "44444444-4444-4444-8444-444444444444", 401, 1),
        new Case(
            MapObservationTest.id(2), "valid", "99999999-9999-4999-8999-999999999999", 401, 1));
  }

  @ParameterizedTest
  @MethodSource("cases")
  void responseStateAndQueryCountMatchV2(Case test) throws Exception {
    environment().reset("maps/seed.sql");
    String token = environment().token(test.kind(), test.user(), "ignored@example.test");
    JsonNode expected = null;
    for (var side : SharedHarness.Side.values())
      try (var db = environment().connect(side)) {
        var before = DatabaseSnapshot.capture(db, DatabaseSnapshot.Rules.defaults());
        var unchanged = DatabaseSnapshot.capture(db, unchangedFields(test.id()));
        SqlQueryCounter.reset();
        var response =
            request(base(side), path(test.id()), HttpMethod.POST, token, Map.of("ignored", true));
        assertThat(response.getStatusCode().value()).isEqualTo(test.status());
        if (expected == null) expected = response.getBody();
        else {
          JsonComparison.compare(
              expected,
              response.getBody(),
              test.status() == 201
                  ? Map.of("/inviteCode", "독립 생성 코드", "/updatedAt", "처리 시각")
                  : Map.of());
          assertThat(SqlQueryCounter.statements()).hasSize(test.queries());
        }
        if (test.status() != 201)
          before.assertUnchanged(DatabaseSnapshot.capture(db, DatabaseSnapshot.Rules.defaults()));
        else {
          assertCode(db, test.id(), response.getBody(), side);
          assertThat(response.getBody().path("inviteCode").asText()).isNotEqualTo("INVITE02");
          unchanged.assertUnchanged(DatabaseSnapshot.capture(db, unchangedFields(test.id())));
        }
      }
  }

  @Test
  void oldCodeStopsWorkingAndNewCodeJoinsTheSameRoom() throws Exception {
    environment().reset("maps/seed.sql");
    String token =
        environment()
            .token("valid", SharedHarness.Environment.USER_ID, SharedHarness.Environment.EMAIL);
    String joiner =
        environment().token("valid", "33333333-3333-4333-8333-333333333333", "member@example.test");
    for (var side : SharedHarness.Side.values()) {
      String base = base(side), id = MapObservationTest.id(2);
      var response = request(base, path(id), HttpMethod.POST, token, null);
      assertThat(response.getStatusCode().value()).isEqualTo(201);
      assertThat(
              request(
                      base,
                      "/api/maps/join-by-code",
                      HttpMethod.POST,
                      joiner,
                      Map.of("inviteCode", "INVITE02"))
                  .getStatusCode()
                  .value())
          .isEqualTo(404);
      var joined =
          request(
              base,
              "/api/maps/join-by-code",
              HttpMethod.POST,
              joiner,
              Map.of("inviteCode", response.getBody().path("inviteCode").asText()));
      assertThat(joined.getStatusCode().value()).isEqualTo(201);
      assertThat(joined.getBody().path("id").asText()).isEqualTo(id);
      assertThat(joined.getBody().path("_count").path("members").asInt()).isEqualTo(2);
    }
    var row =
        Map.<String, Object>of(
            "case",
            "maps.invite-code-generator",
            "kind",
            "intentional",
            "v2",
            "Math.random base36, up to 8 characters",
            "v3",
            "SecureRandom, exactly 8 uppercase alphanumeric characters");
    if (!environment().knownDifferences.contains(row)) environment().knownDifferences.add(row);
  }

  @ParameterizedTest
  @ValueSource(strings = {"before-update", "commit"})
  void failurePreservesTheOldCodeAndAllBusinessState(String phase) throws Exception {
    environment().reset("maps/seed.sql");
    String token =
        environment()
            .token("valid", SharedHarness.Environment.USER_ID, SharedHarness.Environment.EMAIL);
    for (var side : SharedHarness.Side.values())
      try (var db = environment().connect(side)) {
        var sql = db.createStatement();
        sql.execute(
            "CREATE FUNCTION invite_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'invite rollback'; END; $$");
        sql.execute(
            phase.equals("commit")
                ? "CREATE CONSTRAINT TRIGGER invite_fail AFTER UPDATE ON maps DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION invite_fail()"
                : "CREATE TRIGGER invite_fail BEFORE UPDATE ON maps FOR EACH ROW EXECUTE FUNCTION invite_fail()");
        try {
          var before = DatabaseSnapshot.capture(db, DatabaseSnapshot.Rules.defaults());
          var response =
              request(base(side), path(MapObservationTest.id(2)), HttpMethod.POST, token, null);
          assertThat(response.getStatusCode().value()).isEqualTo(500);
          assertThat(response.getBody())
              .isEqualTo(
                  mapper.readTree("{\"statusCode\":500,\"message\":\"Internal server error\"}"));
          before.assertUnchanged(DatabaseSnapshot.capture(db, DatabaseSnapshot.Rules.defaults()));
        } finally {
          sql.execute("DROP TRIGGER invite_fail ON maps");
          sql.execute("DROP FUNCTION invite_fail()");
        }
      }
  }

  @Test
  void concurrentRegenerationKeepsOneOfTheReturnedCodesWithoutChangingV2Semantics()
      throws Exception {
    environment().reset("maps/seed.sql");
    String id = MapObservationTest.id(2),
        token =
            environment()
                .token("valid", SharedHarness.Environment.USER_ID, SharedHarness.Environment.EMAIL);
    for (var side : SharedHarness.Side.values()) {
      var results =
          DatabaseRequestRace.run(
              environment(),
              side,
              "maps",
              id,
              () -> request(base(side), path(id), HttpMethod.POST, token, null));
      assertThat(results)
          .allSatisfy(response -> assertThat(response.getStatusCode().value()).isEqualTo(201));
      var codes =
          results.stream().map(response -> response.getBody().path("inviteCode").asText()).toList();
      assertThat(codes).doesNotHaveDuplicates().doesNotContain("INVITE02");
      try (var db = environment().connect(side);
          var row =
              db.createStatement()
                  .executeQuery("SELECT \"inviteCode\" FROM maps WHERE id='" + id + "'")) {
        row.next();
        assertThat(codes).contains(row.getString(1));
      }
    }
  }

  private static DatabaseSnapshot.Rules unchangedFields(String id) {
    return new DatabaseSnapshot.Rules(
        Map.of(),
        Map.of(
            "maps",
            "SELECT id,\"ownerId\",title,description,tags,\"coverImageUrl\",visibility,CASE WHEN id='"
                + id
                + "' THEN NULL ELSE \"inviteCode\" END AS \"inviteCode\",\"createdAt\",CASE WHEN id='"
                + id
                + "' THEN timestamp '2000-01-01' ELSE \"updatedAt\" END AS \"updatedAt\" FROM maps"));
  }

  private void assertCode(
      java.sql.Connection db, String id, JsonNode response, SharedHarness.Side side)
      throws Exception {
    assertThat(response.path("inviteCode").asText())
        .matches(side == SharedHarness.Side.V3 ? "[A-Z0-9]{8}" : "[A-Z0-9]{1,8}");
    try (var query =
        db.prepareStatement("SELECT \"inviteCode\",\"updatedAt\" FROM maps WHERE id=?")) {
      query.setString(1, id);
      try (var row = query.executeQuery()) {
        row.next();
        assertThat(response.path("inviteCode").asText()).isEqualTo(row.getString(1));
        assertThat(response.path("updatedAt").asText())
            .isEqualTo(
                com.loco.users.UserResponses.timestamp(row.getTimestamp(2).toLocalDateTime()));
      }
    }
  }

  private String base(SharedHarness.Side side) {
    return side == SharedHarness.Side.V2 ? v2Url() : v3Url();
  }

  private static String path(String id) {
    return "/api/maps/" + id + "/invite-code/regenerate";
  }
}
