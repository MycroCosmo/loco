package com.loco.compat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;

@org.junit.jupiter.api.Tag("v2-comparison")
class RateLimitCasesTest extends CompatibilityTestBase {
  static Stream<Arguments> limits() {
    return Stream.of(
        Arguments.of("register", 5, 400),
        Arguments.of("login", 10, 400),
        Arguments.of("forgot-password", 3, 400),
        Arguments.of("reset-password", 5, 400),
        Arguments.of("oauth/kakao", 10, 400),
        Arguments.of("refresh", 120, 400),
        Arguments.of("logout", 120, 201));
  }

  @ParameterizedTest(name = "필수 429 비교: {0} {1}회/분")
  @MethodSource("limits")
  void limitsResponsesAndHeadersMatchV2(String action, int limit, int normalStatus)
      throws Exception {
    environment().reset("compat/seed.sql");
    String path = "/api/auth/" + action;
    for (int i = 0; i < limit; i++) {
      var left = request(v2Url(), path, HttpMethod.POST, null, Map.of(), true);
      var right = request(v3Url(), path, HttpMethod.POST, null, Map.of(), true);
      assertThat(left.getStatusCode().value()).isEqualTo(normalStatus);
      assertThat(right.getStatusCode().value()).isEqualTo(normalStatus);
      JsonComparison.compare(left.getBody(), right.getBody(), Map.of());
      for (var response : java.util.List.of(left, right)) {
        assertThat(response.getHeaders().getFirst("X-RateLimit-Limit"))
            .isEqualTo(Integer.toString(limit));
        assertThat(response.getHeaders().getFirst("X-RateLimit-Remaining"))
            .isEqualTo(Integer.toString(limit - i - 1));
        assertThat(Long.parseLong(response.getHeaders().getFirst("X-RateLimit-Reset")))
            .isBetween(1L, 60L);
      }
    }
    String blockedPath = action.startsWith("oauth/") ? "/api/auth/oauth/naver" : path;
    var left = request(v2Url(), blockedPath, HttpMethod.POST, null, Map.of(), true);
    var right = request(v3Url(), blockedPath, HttpMethod.POST, null, Map.of(), true);
    assertThat(left.getStatusCode().value()).isEqualTo(429);
    assertThat(right.getStatusCode().value()).isEqualTo(429);
    JsonComparison.compare(left.getBody(), right.getBody(), Map.of());
    assertThat(right.getBody())
        .isEqualTo(
            mapper.valueToTree(
                Map.of("statusCode", 429, "message", "ThrottlerException: Too Many Requests")));
    for (var response : java.util.List.of(left, right)) {
      assertThat(Long.parseLong(response.getHeaders().getFirst("Retry-After"))).isBetween(59L, 60L);
      assertThat(response.getHeaders().getFirst("X-RateLimit-Remaining")).isNull();
    }
  }

  @Test
  @DisplayName("깨진 JSON은 Guard 전 400이며 로그인 제한을 소모하지 않음")
  void malformedJsonDoesNotConsumeQuota() throws Exception {
    environment().reset("compat/seed.sql");
    for (int i = 0; i < 11; i++) {
      var left = request(v2Url(), "/api/auth/login", HttpMethod.POST, null, "{", true);
      var right = request(v3Url(), "/api/auth/login", HttpMethod.POST, null, "{", true);
      assertThat(left.getStatusCode().value()).isEqualTo(400);
      assertThat(right.getStatusCode().value()).isEqualTo(400);
      JsonComparison.compare(
          left.getBody(), right.getBody(), Map.of("/message", "JSON 런타임 파서 문구 차이"));
      assertThat(right.getHeaders().getFirst("X-RateLimit-Limit")).isNull();
    }
    for (String base : java.util.List.of(v2Url(), v3Url())) {
      var response = request(base, "/api/auth/login", HttpMethod.POST, null, Map.of(), true);
      assertThat(response.getStatusCode().value()).isEqualTo(400);
      assertThat(response.getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("9");
    }
  }

  @Test
  void globalLimitRunsBeforeMemberAuthenticationAndIgnoresForwardedIp() {
    for (String base : java.util.List.of(v2Url(), v3Url())) {
      String path = "/api/users/me";
      for (int i = 0; i < 120; i++) {
        var response = request(base, path, HttpMethod.GET, null, null, true);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getHeaders().getFirst("X-RateLimit-Remaining"))
            .isEqualTo(Integer.toString(119 - i));
      }
      var headers = new org.springframework.http.HttpHeaders();
      headers.set("X-Forwarded-For", "203.0.113.25");
      var response =
          client.exchange(
              java.net.URI.create(base + path),
              HttpMethod.GET,
              new org.springframework.http.HttpEntity<>(headers),
              com.fasterxml.jackson.databind.JsonNode.class);
      assertThat(response.getStatusCode().value()).isEqualTo(429);
      assertThat(response.getBody())
          .isEqualTo(
              mapper.valueToTree(
                  Map.of("statusCode", 429, "message", "ThrottlerException: Too Many Requests")));
    }
  }

  @Test
  void jsonSizeAndTrailingTokensAreRejectedBeforeQuota() throws Exception {
    String oversized = "{\"extra\":\"" + "x".repeat(10 * 1024 * 1024) + "\"}";
    for (String body : java.util.List.of(oversized, "{}{}")) {
      int status = body == oversized ? 413 : 400;
      var left = request(v2Url(), "/api/auth/login", HttpMethod.POST, null, body, true);
      var right = request(v3Url(), "/api/auth/login", HttpMethod.POST, null, body, true);
      assertThat(left.getStatusCode().value()).isEqualTo(status);
      assertThat(right.getStatusCode().value()).isEqualTo(status);
      JsonComparison.compare(
          left.getBody(),
          right.getBody(),
          status == 400 ? Map.of("/message", "JSON 런타임 파서 문구 차이") : Map.of());
      assertThat(right.getHeaders().getFirst("X-RateLimit-Limit")).isNull();
    }
  }
}
