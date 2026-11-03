package com.loco.auth;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.loco.compat.ComparisonCase;
import com.loco.compat.ComparisonStep;
import com.loco.compat.DatabaseRequestRace;
import com.loco.compat.DatabaseSnapshot;
import com.loco.compat.JsonComparison;
import com.loco.compat.ScenarioValues;
import com.loco.compat.SharedHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import static org.assertj.core.api.Assertions.assertThat;

@org.junit.jupiter.api.Tag("v2-comparison")
class RefreshFlowTest extends AuthenticationSupport {
    private static final DatabaseSnapshot.Rules DEFAULT = DatabaseSnapshot.Rules.defaults();

    @Test
    void loginRefreshOldTokenRejectionAndLogoutFormOneScenario() throws Exception {
        var values = runCase(ComparisonCase.named("로그인 → 재발급 → 이전 토큰 거절 → 로그아웃")
                .databaseRules(v -> tokenRules(v, Map.of()))
                .step(issue("login", Map.of("email", SharedHarness.Environment.EMAIL, "password", environment().password)).build())
                .step(ComparisonStep.named("회전").method(HttpMethod.POST).path("/api/auth/refresh")
                        .body(v -> Map.of("refreshToken", v.text("loginRefresh"))).expectedStatus(201)
                        .valueException("/accessToken", "발급 시각").valueException("/refreshToken", "새 무작위 토큰")
                        .capture("rotatedAccess", "/accessToken").capture("rotatedRefresh", "/refreshToken").build())
                .step(ComparisonStep.named("이전 토큰 재사용").method(HttpMethod.POST).path("/api/auth/refresh")
                        .body(v -> Map.of("refreshToken", v.text("loginRefresh"))).expectedStatus(401).build())
                .step(ComparisonStep.named("로그아웃").method(HttpMethod.POST).path("/api/auth/logout")
                        .body(v -> Map.of("refreshToken", v.text("rotatedRefresh"))).expectedStatus(201).build())
                .step(ComparisonStep.named("로그아웃한 토큰 재사용").method(HttpMethod.POST).path("/api/auth/refresh")
                        .body(v -> Map.of("refreshToken", v.text("rotatedRefresh"))).expectedStatus(401).build()).build());
        for (var side : SharedHarness.Side.values()) {
            assertPair(side, values.get(side), "rotated", SharedHarness.Environment.EMAIL);
            assertThat(values.get(side).text("rotatedRefresh").equals(values.get(side).text("loginRefresh"))).isFalse();
            try (var connection = environment().connect(side);
                 var result = connection.createStatement().executeQuery("SELECT count(*), count(\"revokedAt\") FROM refresh_tokens")) {
                result.next();
                assertThat(result.getLong(1)).isEqualTo(2);
                assertThat(result.getLong(2)).isEqualTo(2);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "revoked", "deleted", "missing-user"})
    void invalidStoredTokensReturn401WithoutChangingDatabase(String state) throws Exception {
        var seed = seed(state);
        JsonNode expected = null;
        for (var side : SharedHarness.Side.values()) try (var connection = environment().connect(side)) {
            var before = DatabaseSnapshot.capture(connection, DEFAULT);
            var response = request(url(side), "/api/auth/refresh", HttpMethod.POST, null, Map.of("refreshToken", seed.raw()));
            assertThat(response.getStatusCode().value()).isEqualTo(401);
            assertThat(response.getBody()).isNotNull();
            if (expected == null) expected = response.getBody(); else JsonComparison.compare(expected, response.getBody(), Map.of());
            before.assertUnchanged(DatabaseSnapshot.capture(connection, DEFAULT));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"valid", "expired"})
    void logoutRevokesOnlyTheSuppliedTokenAndIsIdempotent(String state) throws Exception {
        var seed = seed(state);
        for (var side : SharedHarness.Side.values()) try (var connection = environment().connect(side)) {
            try (var insert = connection.prepareStatement("INSERT INTO refresh_tokens(id,\"userId\",\"tokenHash\",\"expiresAt\") VALUES (?,?,?,?)")) {
                insert.setString(1, UUID.randomUUID().toString());
                insert.setString(2, SharedHarness.Environment.USER_ID);
                insert.setString(3, TokenIssuer.hash("another-token"));
                insert.setTimestamp(4, Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).plusDays(1)));
                insert.executeUpdate();
            }
            var response = request(url(side), "/api/auth/logout", HttpMethod.POST, null, Map.of("refreshToken", seed.raw()));
            assertThat(response.getStatusCode().value()).isEqualTo(201);
            JsonComparison.compare(response.getBody(), mapper.readTree("{\"message\":\"Logged out.\"}"), Map.of());
            try (var result = connection.createStatement().executeQuery("SELECT count(*) FILTER (WHERE \"revokedAt\" IS NULL), count(*) FILTER (WHERE \"revokedAt\" IS NOT NULL) FROM refresh_tokens")) {
                result.next();
                assertThat(result.getInt(1)).isEqualTo(1);
                assertThat(result.getInt(2)).isEqualTo(1);
            }
            var after = DatabaseSnapshot.capture(connection, DEFAULT);
            var again = request(url(side), "/api/auth/logout", HttpMethod.POST, null, Map.of("refreshToken", seed.raw()));
            assertThat(again.getStatusCode().value()).isEqualTo(201);
            JsonComparison.compare(response.getBody(), again.getBody(), Map.of());
            after.assertUnchanged(DatabaseSnapshot.capture(connection, DEFAULT));
        }
    }

    @Test
    void concurrentRefreshAllowsExactlyOneWinnerOnBothServers() throws Exception {
        var seed = seed("valid");
        var snapshots = new EnumMap<SharedHarness.Side, DatabaseSnapshot>(SharedHarness.Side.class);
        var rules = new EnumMap<SharedHarness.Side, DatabaseSnapshot.Rules>(SharedHarness.Side.class);
        JsonNode expectedFailure = null;
        for (var side : SharedHarness.Side.values()) {
            var responses = DatabaseRequestRace.run(environment(), side, "refresh_tokens", seed.id(),
                    () -> request(url(side), "/api/auth/refresh", HttpMethod.POST, null, Map.of("refreshToken", seed.raw())));
            assertThat(responses.stream().map(r -> r.getStatusCode().value()).sorted().toList()).isEqualTo(List.of(201, 401));
            var success = responses.stream().filter(r -> r.getStatusCode().value() == 201).findFirst().orElseThrow();
            var failure = responses.stream().filter(r -> r.getStatusCode().value() == 401).findFirst().orElseThrow();
            assertThat(failure.getBody()).isNotNull();
            if (expectedFailure == null) expectedFailure = failure.getBody(); else JsonComparison.compare(expectedFailure, failure.getBody(), Map.of());
            var values = new ScenarioValues();
            values.capture(mapper.valueToTree(Map.of("refreshToken", seed.raw())), Map.of("seedRefresh", "/refreshToken"));
            values.capture(success.getBody(), Map.of("winnerRefresh", "/refreshToken", "winnerAccess", "/accessToken"));
            assertPair(side, values, "winner", SharedHarness.Environment.EMAIL);
            var sideRules = tokenRules(values, Map.of());
            rules.put(side, sideRules);
            try (var connection = environment().connect(side);
                 var result = connection.createStatement().executeQuery("SELECT count(*), count(\"revokedAt\") FROM refresh_tokens")) {
                result.next();
                assertThat(result.getInt(1)).isEqualTo(2);
                assertThat(result.getInt(2)).isEqualTo(1);
                snapshots.put(side, DatabaseSnapshot.capture(connection, sideRules));
            }
        }
        DatabaseSnapshot.compare(snapshots.get(SharedHarness.Side.V2), snapshots.get(SharedHarness.Side.V3),
                Set.of(SharedHarness.Environment.USER_ID, seed.id()), rules.get(SharedHarness.Side.V2), rules.get(SharedHarness.Side.V3));
    }

    @Test
    void failedReplacementInsertRollsBackOldTokenConsumption() throws Exception {
        var seed = seed("valid");
        try {
            for (var side : SharedHarness.Side.values()) try (var connection = environment().connect(side)) {
                connection.createStatement().execute("""
                        CREATE FUNCTION refresh_test_fail() RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN RAISE EXCEPTION 'refresh replacement failure'; END; $$
                        """);
                connection.createStatement().execute("CREATE TRIGGER refresh_test_fail BEFORE INSERT ON refresh_tokens FOR EACH ROW EXECUTE FUNCTION refresh_test_fail()");
                var before = DatabaseSnapshot.capture(connection, DEFAULT);
                var response = request(url(side), "/api/auth/refresh", HttpMethod.POST, null, Map.of("refreshToken", seed.raw()));
                assertThat(response.getStatusCode().value()).isEqualTo(500);
                JsonComparison.compare(response.getBody(), mapper.readTree("{\"statusCode\":500,\"message\":\"Internal server error\"}"), Map.of());
                before.assertUnchanged(DatabaseSnapshot.capture(connection, DEFAULT));
            }
        } finally {
            for (var side : SharedHarness.Side.values()) try (var connection = environment().connect(side)) {
                connection.createStatement().execute("DROP TRIGGER IF EXISTS refresh_test_fail ON refresh_tokens");
                connection.createStatement().execute("DROP FUNCTION IF EXISTS refresh_test_fail()");
            }
        }
    }

    private record Seed(String id, String raw) { }
    private Seed seed(String state) throws Exception {
        environment().reset("compat/seed.sql");
        var seed = new Seed(UUID.randomUUID().toString(), UUID.randomUUID().toString() + UUID.randomUUID());
        for (var side : SharedHarness.Side.values()) try (var connection = environment().connect(side)) {
            try (var insert = connection.prepareStatement("INSERT INTO refresh_tokens(id,\"userId\",\"tokenHash\",\"expiresAt\",\"revokedAt\") VALUES (?,?,?,?,?)")) {
                insert.setString(1, seed.id());
                insert.setString(2, SharedHarness.Environment.USER_ID);
                insert.setString(3, TokenIssuer.hash(seed.raw()));
                insert.setTimestamp(4, Timestamp.valueOf(state.equals("expired") ? LocalDateTime.now(ZoneOffset.UTC).minusSeconds(10)
                        : LocalDateTime.now(ZoneOffset.UTC).plusDays(1)));
                insert.setTimestamp(5, state.equals("revoked") ? Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC)) : null);
                insert.executeUpdate();
            }
            if (state.equals("deleted")) connection.createStatement().executeUpdate("UPDATE users SET \"deletedAt\"=CURRENT_TIMESTAMP");
            if (state.equals("missing-user")) connection.createStatement().executeUpdate("DELETE FROM users");
        }
        return seed;
    }
    private String url(SharedHarness.Side side) { return side == SharedHarness.Side.V2 ? v2Url() : v3Url(); }
}
