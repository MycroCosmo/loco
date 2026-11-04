package com.loco.security;

import java.util.Map;
import com.loco.compat.CompatibilityTestBase;
import com.loco.compat.JsonComparison;
import com.loco.compat.SharedHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import static org.assertj.core.api.Assertions.assertThat;

@org.junit.jupiter.api.Tag("v2-comparison")
class MemberAuthenticationTest extends CompatibilityTestBase {
    private String token;
    @BeforeEach void seed() throws Exception {
        environment().reset("compat/seed.sql");
        token = environment().token("valid", SharedHarness.Environment.USER_ID, "stale@example.test");
    }
    @ParameterizedTest @ValueSource(strings = {"deleted", "missing"})
    void inactiveMembersAre401OnlyOnProtectedRoutes(String state) throws Exception {
        for (var side : SharedHarness.Side.values()) try (var db = environment().connect(side)) {
            db.createStatement().executeUpdate(state.equals("deleted") ? "UPDATE users SET \"deletedAt\"=CURRENT_TIMESTAMP" : "DELETE FROM users");
        }
        for (var method : java.util.List.of(HttpMethod.GET, HttpMethod.PUT)) {
            var left = request(v2Url(), "/api/users/me", method, token, Map.of("nickname", "x".repeat(31)));
            var right = request(v3Url(), "/api/users/me", method, token, Map.of("nickname", "x".repeat(31)));
            assertThat(left.getStatusCode().value()).isEqualTo(401);
            assertThat(right.getStatusCode().value()).isEqualTo(401);
            JsonComparison.compare(left.getBody(), right.getBody(), Map.of());
            assertThat(right.getBody()).isEqualTo(mapper.valueToTree(Map.of("statusCode", 401, "message", "Unauthorized")));
        }
        for (String base : java.util.List.of(v2Url(), v3Url())) {
            assertThat(request(base, "/api/auth/logout", HttpMethod.POST, token, Map.of()).getStatusCode().value()).isEqualTo(201);
            assertThat(request(base, "/api/__missing-member", HttpMethod.GET, token, null).getStatusCode().value()).isEqualTo(404);
        }
    }
    @Test void protectedPrincipalUsesCurrentDatabaseEmail() throws Exception {
        for (var side : SharedHarness.Side.values()) try (var db = environment().connect(side)) {
            db.createStatement().executeUpdate("UPDATE users SET email='current@example.test'");
        }
        var left = request(v2Url(), "/api/users/me", HttpMethod.GET, token, null);
        var right = request(v3Url(), "/api/users/me", HttpMethod.GET, token, null);
        assertThat(left.getStatusCode().value()).isEqualTo(200); assertThat(right.getStatusCode().value()).isEqualTo(200);
        assertThat(left.getBody().path("email").asText()).isEqualTo("current@example.test");
        assertThat(right.getBody().path("email")).isEqualTo(left.getBody().path("email"));
        assertThat(right.getBody().path("id").asText()).isEqualTo(SharedHarness.Environment.USER_ID);
    }
    @Test void publicAndUnknownRoutesDoNotReadMembersEvenWithValidJwt() throws Exception {
        for (var side : SharedHarness.Side.values()) try (var db = environment().connect(side)) {
            String base = side == SharedHarness.Side.V2 ? v2Url() : v3Url();
            String protectedPath = "/api/users/me";
            db.createStatement().execute("ALTER TABLE users RENAME TO unavailable_users");
            try {
                assertThat(request(base, "/api/auth/logout", HttpMethod.POST, token, Map.of()).getStatusCode().value()).isEqualTo(201);
                assertThat(request(base, "/api/__missing-member", HttpMethod.GET, token, null).getStatusCode().value()).isEqualTo(404);
                assertThat(request(base, protectedPath, HttpMethod.GET, null, null).getStatusCode().value()).isEqualTo(401);
                assertThat(request(base, protectedPath, HttpMethod.GET, token, null).getStatusCode().value()).isEqualTo(500);
                if (side == SharedHarness.Side.V3) {
                    assertThat(request(base, "/api/health", HttpMethod.GET, token, null).getStatusCode().value()).isEqualTo(200);
                    assertThat(request(base, "/api/__test/harness/public-override", HttpMethod.GET, token, null).getStatusCode().value()).isEqualTo(200);
                }
            } finally { db.createStatement().execute("ALTER TABLE unavailable_users RENAME TO users"); }
        }
    }
}
