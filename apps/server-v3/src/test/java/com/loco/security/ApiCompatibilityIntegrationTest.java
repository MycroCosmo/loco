package com.loco.security;

import java.util.Base64;
import java.util.Map;

import com.loco.compat.CompatibilityTestBase;
import com.loco.compat.SharedHarness;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@org.junit.jupiter.api.Tag("v2-comparison")
class ApiCompatibilityIntegrationTest extends CompatibilityTestBase {
    String validToken;
    String userId = SharedHarness.Environment.USER_ID;
    String email = SharedHarness.Environment.EMAIL;

    @BeforeEach
    void prepareSeedAndToken() throws Exception {
        environment().reset("compat/seed.sql");
        validToken = token("valid");
    }

    @Test
    void tokenIssuedByV2LoginAuthenticatesInV3WithoutSessionOrCsrfToken() throws Exception {
        var login = request(v2Url(), "/api/auth/login", HttpMethod.POST, null,
                Map.of("email", email, "password", environment().password));
        assertThat(login.getStatusCode().value()).isEqualTo(201);
        validToken = login.getBody().path("accessToken").asText();
        String[] parts = validToken.split("\\.");
        assertThat(mapper.readTree(Base64.getUrlDecoder().decode(parts[0])).path("alg").asText())
                .isEqualTo("HS256");
        var claims = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(claims.path("exp").asLong() - claims.path("iat").asLong()).isEqualTo(7 * 24 * 60 * 60);
        var response = request(v3Url(), "/api/__test/harness/users/me", HttpMethod.GET, validToken, null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().path("userId").asText()).isEqualTo(userId);
        assertThat(response.getBody().path("email").asText()).isEqualTo(email);
        assertThat(response.getBody().path("session").asBoolean()).isFalse();
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
        var write = request(v3Url(), "/api/__test/harness/users/me", HttpMethod.PUT, validToken, Map.of("nickname", "ok"));
        assertThat(write.getStatusCode().value()).isEqualTo(200);
        assertThat(write.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(request(v3Url(), "/api/__test/harness/users/me", HttpMethod.GET, null, null)
                .getStatusCode().value()).isEqualTo(401);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "expired", "wrong-signature", "malformed", "no-sub", "future-nbf"})
    void protectedRouteMatchesV2Unauthorized(String kind) throws Exception {
        String token = token(kind);
        var expected = request(v2Url(), "/api/users/me", HttpMethod.GET, token, null);
        assertThat(expected.getStatusCode().value()).isEqualTo(401);
        assertThat(expected.getBody()).isEqualTo(mapper.readTree("{\"statusCode\":401,\"message\":\"Unauthorized\"}"));
        assertSame(expected, request(v3Url(), "/api/__test/harness/users/me", HttpMethod.GET, token, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "expired", "wrong-signature", "malformed", "valid"})
    void unknownRouteStays404RegardlessOfToken(String kind) throws Exception {
        String token = token(kind);
        String path = "/api/__missing?q=hello%20world";
        var expected = request(v2Url(), path, HttpMethod.GET, token, null);
        assertThat(expected.getStatusCode().value()).isEqualTo(404);
        assertSame(expected, request(v3Url(), path, HttpMethod.GET, token, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "malformed", "valid"})
    void wrongMethodIs404LikeV2(String kind) throws Exception {
        String token = token(kind);
        var expected = request(v2Url(), "/api/users/me", HttpMethod.POST, token, Map.of());
        assertThat(expected.getStatusCode().value()).isEqualTo(404);
        assertSame(expected, request(v3Url(), "/api/users/me", HttpMethod.POST, token, Map.of()));
        var probe = request(v3Url(), "/api/__test/harness/users/me", HttpMethod.POST, token, Map.of());
        assertThat(probe.getStatusCode().value()).isEqualTo(404);
        assertThat(probe.getBody().path("message").asText()).isEqualTo("Cannot POST /api/__test/harness/users/me");
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "malformed"})
    void publicValidationReturnsV2MessageArray(String kind) throws Exception {
        var body = Map.of("email", "bad", "password", "x");
        var expected = request(v2Url(), "/api/auth/login", HttpMethod.POST, token(kind), body);
        assertThat(expected.getStatusCode().value()).isEqualTo(400);
        assertThat(expected.getBody().path("message").size()).isEqualTo(2);
        assertSame(expected, request(v3Url(), "/api/auth/login", HttpMethod.POST, token(kind), body));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "malformed", "valid"})
    void authenticationPrecedesProtectedDtoValidation(String kind) throws Exception {
        String token = token(kind);
        var body = Map.of("nickname", "a".repeat(31));
        var expected = request(v2Url(), "/api/users/me", HttpMethod.PUT, token, body);
        assertThat(expected.getStatusCode().value()).isEqualTo(kind.equals("valid") ? 400 : 401);
        assertSame(expected, request(v3Url(), "/api/__test/harness/users/me", HttpMethod.PUT, token, body));
    }

    @Test
    void validTokenDoesNotBypassMethodAuthorization() {
        var response = request(v3Url(), "/api/__test/harness/restricted", HttpMethod.GET, validToken, null);
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody().path("error").asText()).isEqualTo("Forbidden");
    }

    @ParameterizedTest
    @ValueSource(strings = {"HS384", "HS512"})
    void onlyConfiguredHs256AlgorithmIsAccepted(String algorithm) throws Exception {
        assertThat(request(v3Url(), "/api/__test/harness/users/me", HttpMethod.GET, token(algorithm), null)
                .getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void publicMethodOverridesProtectedControllerAndProtectedMethodOverridesPublicController() {
        assertThat(request(v3Url(), "/api/__test/harness/public-override", HttpMethod.GET, "not-a-jwt", null)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(request(v3Url(), "/api/__test/harness/method-protected", HttpMethod.GET, null, null)
                .getStatusCode().value()).isEqualTo(401);
    }

    String token(String kind) throws Exception {
        return environment().token(kind, userId, email);
    }

    void assertSame(ResponseEntity<JsonNode> expected, ResponseEntity<JsonNode> actual) {
        assertThat(actual.getStatusCode()).isEqualTo(expected.getStatusCode());
        assertThat(actual.getBody()).isEqualTo(expected.getBody());
        assertThat(actual.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
    }
}
