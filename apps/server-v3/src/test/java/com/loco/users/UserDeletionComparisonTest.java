package com.loco.users;

import static org.assertj.core.api.Assertions.assertThat;

import com.loco.auth.TokenIssuer;
import com.loco.compat.CompatibilityTestBase;
import com.loco.compat.DatabaseSnapshot;
import com.loco.compat.SharedHarness;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
class UserDeletionComparisonTest extends CompatibilityTestBase {
  private static final String USER = SharedHarness.Environment.USER_ID;
  private static final String OTHER = "22222222-2222-4222-8222-222222222222";
  private static final DatabaseSnapshot.Rules RULES = tokenRules();

  private static DatabaseSnapshot.Rules tokenRules() {
    var queries = new java.util.HashMap<String, String>();
    for (String table : List.of("refresh_tokens", "password_reset_tokens")) {
      var sql = new StringBuilder("SELECT t.*, CASE \"tokenHash\"");
      var roles = List.of("active", "expired", "consumed", "other-member");
      for (int i = 0; i < roles.size(); i++)
        sql.append(" WHEN '")
            .append(TokenIssuer.hash(table + i))
            .append("' THEN '")
            .append(roles.get(i))
            .append("'");
      queries.put(table, sql.append(" END AS __role FROM ").append(table).append(" t").toString());
    }
    return new DatabaseSnapshot.Rules(
        Map.of("refresh_tokens", List.of("__role"), "password_reset_tokens", List.of("__role")),
        queries);
  }

  private void seed() throws Exception {
    environment().reset("compat/seed.sql");
    for (var side : SharedHarness.Side.values())
      try (var db = environment().connect(side)) {
        db.createStatement()
            .execute(
                "UPDATE users SET \"fcmToken\"='phone',\"profileImageUrl\"='/uploads/profiles/keep.webp'");
        db.createStatement()
            .execute(
                "INSERT INTO maps(id,\"ownerId\",title,\"updatedAt\") VALUES ('55555555-5555-4555-8555-555555555555','"
                    + USER
                    + "','retained map','2026-01-01')");
        db.createStatement()
            .execute(
                "INSERT INTO places(id,\"mapId\",\"createdById\",name,address,latitude,longitude,\"updatedAt\") VALUES ('66666666-6666-4666-8666-666666666666','55555555-5555-4555-8555-555555555555','"
                    + USER
                    + "','retained place','address',37.5,127.0,'2026-01-01')");
        db.createStatement()
            .execute(
                "INSERT INTO users(id,email,nickname,provider,\"updatedAt\") VALUES ('"
                    + OTHER
                    + "','other@example.test','other','LOCAL','2026-01-01')");
        for (String table : List.of("refresh_tokens", "password_reset_tokens")) {
          String consumed = table.equals("refresh_tokens") ? "revokedAt" : "usedAt";
          for (int i = 0; i < 4; i++) {
            try (var insert =
                db.prepareStatement(
                    "INSERT INTO "
                        + table
                        + "(id,\"userId\",\"tokenHash\",\"expiresAt\",\""
                        + consumed
                        + "\",\"createdAt\") VALUES (?,?,?,?::timestamp,?::timestamp,'2026-01-01')")) {
              insert.setString(
                  1,
                  (table.equals("refresh_tokens") ? "30000000" : "40000000")
                      + "-0000-4000-8000-00000000000"
                      + i);
              insert.setString(2, i == 3 ? OTHER : USER);
              insert.setString(3, TokenIssuer.hash(table + i));
              insert.setString(4, i == 1 ? "2020-01-01" : "2099-01-01");
              insert.setString(5, i == 2 ? "2025-01-01" : null);
              insert.executeUpdate();
            }
          }
        }
      }
  }

  @Test
  void withdrawalInvalidatesOnlyMembersUnusedTokens() throws Exception {
    seed();
    for (var side : SharedHarness.Side.values())
      try (var db = environment().connect(side)) {
        String base = side == SharedHarness.Side.V2 ? v2Url() : v3Url();
        String token = environment().token("valid", USER, SharedHarness.Environment.EMAIL);
        var response = request(base, "/api/users/me", HttpMethod.DELETE, token, null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody())
            .isEqualTo(mapper.valueToTree(Map.of("message", "회원 탈퇴가 완료되었습니다.")));
        try (var row =
            db.createStatement()
                .executeQuery(
                    "SELECT \"deletedAt\",\"fcmToken\",\"profileImageUrl\",\"updatedAt\" FROM users WHERE id='"
                        + USER
                        + "'")) {
          assertThat(row.next()).isTrue();
          assertThat(row.getTimestamp(1)).isNotNull();
          assertThat(row.getString(2)).isNull();
          assertThat(row.getString(3)).isEqualTo("/uploads/profiles/keep.webp");
          assertThat(row.getTimestamp(4).toLocalDateTime().getYear()).isGreaterThan(2025);
          for (String table : List.of("refresh_tokens", "password_reset_tokens")) {
            String consumed = table.equals("refresh_tokens") ? "revokedAt" : "usedAt";
            try (var values =
                db.createStatement()
                    .executeQuery(
                        "SELECT \"userId\",\""
                            + consumed
                            + "\" FROM "
                            + table
                            + " ORDER BY \"tokenHash\"")) {
              int changed = 0, old = 0, untouched = 0;
              while (values.next()) {
                if (OTHER.equals(values.getString(1))) {
                  assertThat(values.getTimestamp(2)).isNull();
                  untouched++;
                } else if (values.getTimestamp(2).toLocalDateTime().getYear() == 2025) old++;
                else {
                  assertThat(values.getTimestamp(2)).isEqualTo(row.getTimestamp(1));
                  changed++;
                }
              }
              assertThat(List.of(changed, old, untouched)).isEqualTo(List.of(2, 1, 1));
            }
          }
        }
        assertThat(
                request(base, "/api/users/me", HttpMethod.GET, token, null).getStatusCode().value())
            .isEqualTo(401);
        try (var count =
            db.createStatement()
                .executeQuery(
                    "SELECT (SELECT count(*) FROM maps), (SELECT count(*) FROM places)")) {
          count.next();
          assertThat(count.getInt(1)).isEqualTo(1);
          assertThat(count.getInt(2)).isEqualTo(1);
        }
        assertThat(
                request(base, "/api/users/me", HttpMethod.DELETE, token, null)
                    .getStatusCode()
                    .value())
            .isEqualTo(401);
      }
    try (var left = environment().connect(SharedHarness.Side.V2);
        var right = environment().connect(SharedHarness.Side.V3)) {
      var snapshot = DatabaseSnapshot.capture(left, RULES);
      DatabaseSnapshot.compare(
          snapshot, DatabaseSnapshot.capture(right, RULES), snapshot.seedIds(), RULES);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"refresh_tokens", "password_reset_tokens"})
  void failureRollsBackEveryTable(String table) throws Exception {
    seed();
    for (var side : SharedHarness.Side.values())
      try (var db = environment().connect(side)) {
        db.createStatement()
            .execute(
                "CREATE FUNCTION withdrawal_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'withdrawal failure'; END; $$");
        db.createStatement()
            .execute(
                "CREATE TRIGGER withdrawal_fail BEFORE UPDATE ON "
                    + table
                    + " FOR EACH ROW EXECUTE FUNCTION withdrawal_fail()");
        try {
          var before = DatabaseSnapshot.capture(db, RULES);
          var response =
              request(
                  side == SharedHarness.Side.V2 ? v2Url() : v3Url(),
                  "/api/users/me",
                  HttpMethod.DELETE,
                  environment().token("valid", USER, SharedHarness.Environment.EMAIL),
                  null);
          assertThat(response.getStatusCode().value()).isEqualTo(500);
          assertThat(response.getBody())
              .isEqualTo(
                  mapper.readTree("{\"statusCode\":500,\"message\":\"Internal server error\"}"));
          before.assertUnchanged(DatabaseSnapshot.capture(db, RULES));
        } finally {
          db.createStatement().execute("DROP TRIGGER withdrawal_fail ON " + table);
          db.createStatement().execute("DROP FUNCTION withdrawal_fail()");
        }
      }
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing-token", "deleted-user", "missing-user"})
  void invalidMemberCannotWithdraw(String state) throws Exception {
    environment().reset("compat/seed.sql");
    for (var side : SharedHarness.Side.values())
      try (var db = environment().connect(side)) {
        if (state.equals("deleted-user"))
          db.createStatement().execute("UPDATE users SET \"deletedAt\"=CURRENT_TIMESTAMP");
        if (state.equals("missing-user")) db.createStatement().execute("DELETE FROM users");
        var before = DatabaseSnapshot.capture(db, RULES);
        var response =
            request(
                side == SharedHarness.Side.V2 ? v2Url() : v3Url(),
                "/api/users/me",
                HttpMethod.DELETE,
                state.equals("missing-token")
                    ? null
                    : environment().token("valid", USER, SharedHarness.Environment.EMAIL),
                null);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody())
            .isEqualTo(mapper.readTree("{\"statusCode\":401,\"message\":\"Unauthorized\"}"));
        before.assertUnchanged(DatabaseSnapshot.capture(db, RULES));
      }
  }
}
