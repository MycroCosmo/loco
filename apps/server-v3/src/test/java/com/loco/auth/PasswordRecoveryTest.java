package com.loco.auth;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.JsonNode;
import com.loco.compat.DatabaseRequestRace;
import com.loco.compat.DatabaseSnapshot;
import com.loco.compat.JsonComparison;
import com.loco.compat.SharedHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import static org.assertj.core.api.Assertions.assertThat;

class PasswordRecoveryTest extends AuthenticationSupport {
    private static final String EMAIL = SharedHarness.Environment.EMAIL;
    private static final String USER = SharedHarness.Environment.USER_ID;
    private static final String OLD_CODE = "seed00";
    private final String newPassword = UUID.randomUUID().toString();

    @Test
    void mailCodeResetsPasswordRevokesRefreshAndCannotBeReused() throws Exception {
        String seedId = seed("valid");
        for (var side : SharedHarness.Side.values()) try (var db = environment().connect(side)) {
            db.createStatement().executeUpdate("UPDATE users SET nickname='${code}'");
        }
        JsonNode firstMail = null;
        for (var side : SharedHarness.Side.values()) {
            environment().clearMail();
            assertResponse(call(side, "forgot-password", Map.of("email", EMAIL)), 201, "이메일로 재설정 코드를 전송했습니다.");
            var mail = environment().awaitMail(EMAIL);
            assertThat(mail.path("HTML").asText()).contains("<strong>${code}</strong>");
            var matcher = Pattern.compile(">([1-9][0-9]{5})</div>").matcher(mail.path("HTML").asText());
            assertThat(matcher.find()).isTrue();
            String code = matcher.group(1);
            assertThat(mail.path("Subject").asText()).isEqualTo("[LOCO] 비밀번호 재설정 인증 코드");
            var normalized = mapper.createObjectNode();
            normalized.set("From", mail.path("From"));
            normalized.set("To", mail.path("To"));
            normalized.put("Subject", mail.path("Subject").asText());
            normalized.put("HTML", mail.path("HTML").asText().replace(code, "CODE").trim());
            if (firstMail == null) firstMail = normalized; else JsonComparison.compare(firstMail, normalized, Map.of());
            try (var db = environment().connect(side); var query = db.prepareStatement("SELECT \"expiresAt\",\"createdAt\",\"usedAt\" FROM password_reset_tokens WHERE \"tokenHash\"=?")) {
                query.setString(1, TokenIssuer.hash(code));
                try (var row = query.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getTimestamp(3)).isNull();
                    long seconds = java.time.Duration.between(row.getTimestamp(2).toLocalDateTime(), row.getTimestamp(1).toLocalDateTime()).toSeconds();
                    assertThat(seconds).isBetween(895L, 901L);
                }
            }
            assertResponse(call(side, "reset-password", resetBody(code)), 201, "비밀번호가 재설정되었습니다.");
            assertResponse(call(side, "reset-password", resetBody(code)), 401, "유효하지 않거나 만료된 코드입니다.");
            try (var db = environment().connect(side); var rows = db.createStatement().executeQuery("SELECT count(*) FROM refresh_tokens WHERE \"revokedAt\" IS NULL")) {
                rows.next(); assertThat(rows.getInt(1)).isZero();
            }
        }
        compare(seedId, Map.of(EMAIL, newPassword));
        for (var side : SharedHarness.Side.values()) {
            assertThat(call(side, "login", Map.of("email", EMAIL, "password", newPassword)).getStatusCode().value()).isEqualTo(201);
        }
    }

    @Test
    void smtpFailureLeavesInvalidatedOldCodeAndNewCodeCommitted() throws Exception {
        String id = seed("valid");
        try {
            environment().mailRequest("PUT", "/api/v1/chaos", Map.of("Sender", Map.of("ErrorCode", 550, "Probability", 100)));
            for (var side : SharedHarness.Side.values()) {
                var response = call(side, "forgot-password", Map.of("email", EMAIL));
                assertThat(response.getStatusCode().value()).isEqualTo(500);
                JsonComparison.compare(response.getBody(), mapper.readTree("{\"statusCode\":500,\"message\":\"Internal server error\"}"), Map.of());
                try (var db = environment().connect(side); var row = db.createStatement().executeQuery("SELECT count(*), count(\"usedAt\") FROM password_reset_tokens")) {
                    row.next(); assertThat(row.getInt(1)).isEqualTo(2); assertThat(row.getInt(2)).isEqualTo(1);
                }
            }
            assertThat(environment().mailRequest("GET", "/api/v1/messages").path("messages").size()).isZero();
            compare(id, Map.of());
        } finally { environment().mailRequest("PUT", "/api/v1/chaos", Map.of()); }
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "deleted", "oauth"})
    void forgotDoesNotRevealIneligibleAccounts(String state) throws Exception {
        seed(state);
        for (var side : SharedHarness.Side.values()) try (var db = environment().connect(side)) {
            var before = DatabaseSnapshot.capture(db, rules(Map.of()));
            assertResponse(call(side, "forgot-password", Map.of("email", EMAIL)), 201, "이메일로 재설정 코드를 전송했습니다.");
            before.assertUnchanged(DatabaseSnapshot.capture(db, rules(Map.of())));
        }
        assertThat(environment().mailRequest("GET", "/api/v1/messages").path("messages").size()).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "deleted", "oauth", "expired", "used", "wrong"})
    void invalidResetDoesNotChangeDatabase(String state) throws Exception {
        seed(state);
        for (var side : SharedHarness.Side.values()) try (var db = environment().connect(side)) {
            var before = DatabaseSnapshot.capture(db, rules(Map.of()));
            String message = Set.of("missing", "deleted", "oauth").contains(state) ? "유효하지 않은 요청입니다." : "유효하지 않거나 만료된 코드입니다.";
            assertResponse(call(side, "reset-password", resetBody(state.equals("wrong") ? "wrong0" : OLD_CODE)), 401, message);
            before.assertUnchanged(DatabaseSnapshot.capture(db, rules(Map.of())));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"forgot-password", "reset-password"})
    void validationErrorsAreExactArrays(String path) throws Exception {
        seed("valid");
        var left = call(SharedHarness.Side.V2, path, Map.of("extra", true));
        var right = call(SharedHarness.Side.V3, path, Map.of("extra", true));
        assertThat(left.getStatusCode().value()).isEqualTo(400);
        assertThat(right.getStatusCode().value()).isEqualTo(400);
        assertThat(left.getBody().path("message").isArray()).isTrue();
        JsonComparison.compare(left.getBody(), right.getBody(), Map.of());
    }

    @Test
    void simultaneousResetConsumesCodeOnlyOnce() throws Exception {
        String id = seed("valid");
        environment().budget.freshWindow("/api/auth/reset-password");
        JsonNode failure = null;
        for (var side : SharedHarness.Side.values()) {
            var responses = DatabaseRequestRace.run(environment(), side, "password_reset_tokens", id,
                    () -> call(side, "reset-password", resetBody(OLD_CODE)));
            assertThat(responses.stream().map(r -> r.getStatusCode().value()).sorted().toList()).isEqualTo(List.of(201, 401));
            var loser = responses.stream().filter(r -> r.getStatusCode().value() == 401).findFirst().orElseThrow();
            if (failure == null) failure = loser.getBody(); else JsonComparison.compare(failure, loser.getBody(), Map.of());
            try (var db = environment().connect(side); var row = db.createStatement().executeQuery("SELECT (SELECT count(*) FROM password_reset_tokens WHERE \"usedAt\" IS NOT NULL), (SELECT count(*) FROM refresh_tokens WHERE \"revokedAt\" IS NOT NULL)")) {
                row.next(); assertThat(row.getInt(1)).isEqualTo(1); assertThat(row.getInt(2)).isEqualTo(1);
            }
        }
        compare(id, Map.of(EMAIL, newPassword));
    }

    @Test
    void revocationFailureRollsBackCodeAndPassword() throws Exception {
        seed("valid");
        try {
            for (var side : SharedHarness.Side.values()) try (var db = environment().connect(side)) {
                db.createStatement().execute("CREATE FUNCTION reset_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'reset failure'; END; $$");
                db.createStatement().execute("CREATE TRIGGER reset_fail BEFORE UPDATE ON refresh_tokens FOR EACH ROW EXECUTE FUNCTION reset_fail()");
                var before = DatabaseSnapshot.capture(db, rules(Map.of()));
                var response = call(side, "reset-password", resetBody(OLD_CODE));
                assertThat(response.getStatusCode().value()).isEqualTo(500);
                JsonComparison.compare(response.getBody(), mapper.readTree("{\"statusCode\":500,\"message\":\"Internal server error\"}"), Map.of());
                before.assertUnchanged(DatabaseSnapshot.capture(db, rules(Map.of())));
            }
        } finally {
            for (var side : SharedHarness.Side.values()) try (var db = environment().connect(side)) {
                db.createStatement().execute("DROP TRIGGER IF EXISTS reset_fail ON refresh_tokens");
                db.createStatement().execute("DROP FUNCTION IF EXISTS reset_fail()");
            }
        }
    }

    private String seed(String state) throws Exception {
        environment().reset("compat/seed.sql");
        String id = UUID.randomUUID().toString();
        for (var side : SharedHarness.Side.values()) try (var db = environment().connect(side)) {
            try (var insert = db.prepareStatement("INSERT INTO password_reset_tokens(id,\"userId\",\"tokenHash\",\"expiresAt\",\"usedAt\") VALUES (?,?,?,?,?)")) {
                insert.setString(1, id); insert.setString(2, USER); insert.setString(3, TokenIssuer.hash(OLD_CODE));
                insert.setTimestamp(4, Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(state.equals("expired") ? -1 : 15)));
                insert.setTimestamp(5, state.equals("used") ? Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC)) : null);
                insert.executeUpdate();
            }
            try (var insert = db.prepareStatement("INSERT INTO refresh_tokens(id,\"userId\",\"tokenHash\",\"expiresAt\") VALUES (?,?,?,?)")) {
                insert.setString(1, UUID.randomUUID().toString()); insert.setString(2, USER);
                insert.setString(3, TokenIssuer.hash("seed-refresh")); insert.setTimestamp(4, Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).plusDays(1)));
                insert.executeUpdate();
            }
            switch (state) {
                case "missing" -> db.createStatement().executeUpdate("DELETE FROM users");
                case "deleted" -> db.createStatement().executeUpdate("UPDATE users SET \"deletedAt\"=CURRENT_TIMESTAMP");
                case "oauth" -> db.createStatement().executeUpdate("UPDATE users SET provider='KAKAO'");
            }
        }
        return id;
    }
    private DatabaseSnapshot.Rules rules(Map<String, String> passwords) {
        return new DatabaseSnapshot.Rules(Map.of("password_reset_tokens", List.of("__sort_role")), Map.of("password_reset_tokens",
                "SELECT t.*, CASE WHEN \"tokenHash\"='" + TokenIssuer.hash(OLD_CODE) + "' THEN 'old' ELSE 'new' END AS \"__sort_role\" FROM password_reset_tokens t"), passwords);
    }
    private void compare(String id, Map<String, String> passwords) throws Exception {
        try (var left = environment().connect(SharedHarness.Side.V2); var right = environment().connect(SharedHarness.Side.V3)) {
            var rules = rules(passwords);
            DatabaseSnapshot.compare(DatabaseSnapshot.capture(left, rules), DatabaseSnapshot.capture(right, rules), Set.of(USER, id), rules);
        }
    }
    private Map<String, String> resetBody(String code) { return Map.of("email", EMAIL, "code", code, "newPassword", newPassword); }
    private ResponseEntity<JsonNode> call(SharedHarness.Side side, String action, Object body) {
        return request(side == SharedHarness.Side.V2 ? v2Url() : v3Url(), "/api/auth/" + action, HttpMethod.POST, null, body);
    }
    private void assertResponse(ResponseEntity<JsonNode> response, int status, String message) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().path("message").asText()).isEqualTo(message);
        assertThat(response.getBody().size()).isEqualTo(status == 201 ? 1 : 3);
    }
}
