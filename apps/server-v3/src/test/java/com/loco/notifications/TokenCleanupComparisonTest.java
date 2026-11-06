package com.loco.notifications;

import static org.assertj.core.api.Assertions.*;

import com.loco.auth.*;
import com.loco.compat.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("v2-comparison")
class TokenCleanupComparisonTest extends CompatibilityTestBase {
  @Autowired RefreshTokenRepository refresh;
  @Autowired PasswordResetTokenRepository reset;
  private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");
  private com.fasterxml.jackson.databind.JsonNode baseline;

  @BeforeEach
  void seed() throws Exception {
    environment().reset("places/seed.sql");
    String script;
    try (var in = getClass().getResourceAsStream("/notifications/observe-v2.cjs")) {
      script = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    var observed = environment().nodeCheck(script, Map.of("mode", "cleanup"));
    assertThat(observed.has("error")).as(observed.toString()).isFalse();
    baseline = observed.path("rows");
    long day = 86400000L;
    insert("refresh_tokens", "old-minus", -30 * day - 1, null);
    insert("refresh_tokens", "old-equal", -30 * day, null);
    insert("refresh_tokens", "old-plus", -30 * day + 1, null);
    insert("refresh_tokens", "expired-recent", -1, null);
    insert("refresh_tokens", "future", day, null);
    insert("refresh_tokens", "revoked-future", day, "revokedAt");
    insert("password_reset_tokens", "expired-minus", -1, null);
    insert("password_reset_tokens", "expired-equal", 0, null);
    insert("password_reset_tokens", "future", 1, null);
    insert("password_reset_tokens", "used-future", day, "usedAt");
  }

  void insert(String table, String id, long offset, String marked) throws Exception {
    sql(
        "INSERT INTO "
            + table
            + "(id,\"userId\",\"tokenHash\",\"expiresAt\",\"createdAt\""
            + (marked == null ? "" : ",\"" + marked + "\"")
            + ") VALUES ('probe-"
            + id
            + "','"
            + SharedHarness.Environment.USER_ID
            + "','"
            + id
            + "','"
            + LocalDateTime.ofInstant(NOW.plusMillis(offset), ZoneOffset.UTC)
            + "','2026-01-01'"
            + (marked == null ? "" : ",'2026-11-06 12:00:00'")
            + ")");
  }

  void sql(String query) throws Exception {
    try (var db = environment().connect(SharedHarness.Side.V3)) {
      db.createStatement().execute(query);
    }
  }

  int count(String table) throws Exception {
    try (var db = environment().connect(SharedHarness.Side.V3);
        var r = db.createStatement().executeQuery("SELECT count(*) FROM " + table)) {
      r.next();
      return r.getInt(1);
    }
  }

  List<String> ids(String table) throws Exception {
    var ids = new ArrayList<String>();
    try (var db = environment().connect(SharedHarness.Side.V3);
        var r = db.createStatement().executeQuery("SELECT id FROM " + table + " ORDER BY id")) {
      while (r.next()) ids.add(r.getString(1));
    }
    return ids;
  }

  TokenCleanupService cleanup() {
    return new TokenCleanupService(refresh, reset, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void boundariesAndRepeatedCleanupMatchV2() throws Exception {
    assertThat(cleanup().cleanup()).isEqualTo(new TokenCleanupService.Deleted(2, 2));
    assertThat(mapper.<com.fasterxml.jackson.databind.JsonNode>valueToTree(ids("refresh_tokens")))
        .isEqualTo(baseline.get(0).path("refreshAfter"));
    assertThat(
            mapper.<com.fasterxml.jackson.databind.JsonNode>valueToTree(
                ids("password_reset_tokens")))
        .isEqualTo(baseline.get(0).path("resetAfter"));
    assertThat(cleanup().cleanup()).isEqualTo(new TokenCleanupService.Deleted(0, 0));
    assertThat(count("refresh_tokens")).isEqualTo(baseline.get(1).path("refresh").asInt());
    assertThat(count("password_reset_tokens")).isEqualTo(baseline.get(1).path("reset").asInt());
  }

  @ParameterizedTest
  @ValueSource(strings = {"refresh_tokens", "password_reset_tokens"})
  void failedDeleteDoesNotRollbackOtherTable(String table) throws Exception {
    sql(
        "CREATE FUNCTION token_probe_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected'; END; $$");
    sql(
        "CREATE TRIGGER token_probe_fail BEFORE DELETE ON "
            + table
            + " FOR EACH ROW EXECUTE FUNCTION token_probe_fail()");
    try {
      assertThatThrownBy(() -> cleanup().cleanup()).isInstanceOf(RuntimeException.class);
      var expected = baseline.get(table.equals("refresh_tokens") ? 2 : 3);
      assertThat(count("refresh_tokens")).isEqualTo(expected.path("refresh").asInt());
      assertThat(count("password_reset_tokens")).isEqualTo(expected.path("reset").asInt());
    } finally {
      sql("DROP TRIGGER token_probe_fail ON " + table);
      sql("DROP FUNCTION token_probe_fail()");
    }
  }

  @Test
  void containerDefaultIsUtcAndSeoulScheduleIsAnExplicitDifference() throws Exception {
    var zone =
        environment()
            .nodeCheck(
                "process.stdout.write(JSON.stringify({zone:Intl.DateTimeFormat().resolvedOptions().timeZone,offset:new Date().getTimezoneOffset()}))",
                Map.of());
    assertThat(zone.path("offset").asInt(-1)).isZero();
    assertThat(baseline.get(4).path("nextUtc").asText()).isEqualTo("2026-11-06T03:00:00.000Z");
    assertThat(baseline.get(5).path("nextUtc").asText()).isEqualTo("2026-11-06T18:00:00.000Z");
    environment()
        .knownDifferences
        .add(
            Map.of(
                "case",
                "tokens.cleanup-timezone",
                "kind",
                "intentional",
                "reason",
                "9-5 B: Korean dawn intended; explicit Asia/Seoul 03:00 instead of UTC container 03:00",
                "v2",
                "12:00 Asia/Seoul",
                "v3",
                "03:00 Asia/Seoul"));
  }
}
