package com.loco.auth;

import java.util.Map;
import com.loco.compat.ComparisonCase;
import com.loco.compat.ComparisonStep;
import com.loco.compat.DatabaseSnapshot;
import com.loco.compat.JsonComparison;
import com.loco.compat.SharedHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import static org.assertj.core.api.Assertions.assertThat;

@org.junit.jupiter.api.Tag("v2-comparison")
class AuthenticationFlowTest extends AuthenticationSupport {
    @Test
    void registerThenLoginPreservesBothIssuedTokensAndUserState() throws Exception {
        String email = "created@example.test", password = environment().password;
        var values = runCase(ComparisonCase.named("회원가입 → 로그인")
                .databaseRules(v -> tokenRules(v, Map.of(email, password)))
                .step(issue("register", Map.of("email", email, "password", password, "nickname", "가입회원")).build())
                .step(issue("login", Map.of("email", email, "password", password)).build()).build());
        for (var side : SharedHarness.Side.values()) {
            assertPair(side, values.get(side), "register", email);
            assertPair(side, values.get(side), "login", email);
        }
    }

    @Test
    void seedLoginTokensWorkAcrossV2AndV3WithoutBearerAuthentication() throws Exception {
        var values = runCase(ComparisonCase.named("시드 회원 로그인")
                .step(issue("login", Map.of("email", SharedHarness.Environment.EMAIL, "password", environment().password)).build())
                .build());
        for (var side : SharedHarness.Side.values()) assertPair(side, values.get(side), "login", SharedHarness.Environment.EMAIL);
        assertThat(request(v2Url(), "/api/users/me", HttpMethod.GET, values.get(SharedHarness.Side.V3).text("loginAccess"), null)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(request(v3Url(), "/api/users/me", HttpMethod.GET, values.get(SharedHarness.Side.V2).text("loginAccess"), null)
                .getStatusCode().value()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"deleted", "no-password"})
    void unavailableAccountsAreRejectedWithoutCreatingTokens(String state) throws Exception {
        environment().reset("compat/seed.sql");
        com.fasterxml.jackson.databind.JsonNode expected = null;
        for (var side : SharedHarness.Side.values()) {
            try (var connection = environment().connect(side)) {
                connection.createStatement().executeUpdate(state.equals("deleted")
                        ? "UPDATE users SET \"deletedAt\"=CURRENT_TIMESTAMP"
                        : "UPDATE users SET password=NULL, provider='KAKAO'");
                var before = DatabaseSnapshot.capture(connection, DatabaseSnapshot.Rules.defaults());
                var response = request(side == SharedHarness.Side.V2 ? v2Url() : v3Url(), "/api/auth/login", HttpMethod.POST,
                        null, Map.of("email", SharedHarness.Environment.EMAIL, "password", environment().password));
                assertThat(response.getStatusCode().value()).isEqualTo(401);
                assertThat(response.getBody()).isNotNull();
                if (expected == null) expected = response.getBody(); else JsonComparison.compare(expected, response.getBody(), Map.of());
                before.assertUnchanged(DatabaseSnapshot.capture(connection, DatabaseSnapshot.Rules.defaults()));
            }
        }
    }

    @Test
    void unhandledDatabaseFailureUsesTheV2TwoField500Response() throws Exception {
        environment().reset("compat/seed.sql");
        try {
            for (var side : SharedHarness.Side.values()) try (var connection = environment().connect(side)) {
                connection.createStatement().execute("""
                        CREATE FUNCTION auth_test_fail() RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN RAISE EXCEPTION 'auth test database failure'; END; $$
                        """);
                connection.createStatement().execute("CREATE TRIGGER auth_test_fail BEFORE INSERT ON users FOR EACH ROW WHEN (NEW.email='failure@example.test') EXECUTE FUNCTION auth_test_fail()");
            }
            runCase(ComparisonCase.named("회원가입 DB 오류").databaseUnchanged()
                    .step(ComparisonStep.named("500").method(HttpMethod.POST).path("/api/auth/register")
                            .body(v -> Map.of("email", "failure@example.test", "password", environment().password, "nickname", "오류"))
                            .expectedStatus(500).build()).build());
        } finally {
            for (var side : SharedHarness.Side.values()) try (var connection = environment().connect(side)) {
                connection.createStatement().execute("DROP TRIGGER IF EXISTS auth_test_fail ON users");
                connection.createStatement().execute("DROP FUNCTION IF EXISTS auth_test_fail()");
            }
        }
    }

    @Test
    void malformedJsonKeepsStatusAndStructureWithAnExplicitMessageException() throws Exception {
        for (String body : java.util.List.of("{", "null", "true")) {
            runCase(ComparisonCase.named("깨진 JSON 로그인").databaseUnchanged()
                    .step(ComparisonStep.named("파싱 오류").method(HttpMethod.POST).path("/api/auth/login")
                            .body(v -> body).expectedStatus(400)
                            .valueException("/message", "JSON 런타임 파서 문구 차이").build()).build());
        }
    }

}
