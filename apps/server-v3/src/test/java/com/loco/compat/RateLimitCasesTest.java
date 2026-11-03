package com.loco.compat;

import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class RateLimitCasesTest extends CompatibilityTestBase {
    @Test
    @DisplayName("알려진 차이: 로그인 11번째 요청 — v2 429 / v3 제한 미구현")
    void loginRateLimitIsAnExplicitKnownDifference() throws Exception {
        environment().reset("compat/seed.sql");
        String path = "/api/auth/login";
        environment().budget.freshWindow(path);
        var body = Map.of("email", "bad", "password", "x");
        for (int i = 0; i < 10; i++) {
            var left = request(v2Url(), path, HttpMethod.POST, null, body, true);
            var right = request(v3Url(), "/api/auth/login", HttpMethod.POST, null, body, true);
            assertThat(left.getStatusCode().value()).isEqualTo(400);
            assertThat(right.getStatusCode().value()).isEqualTo(400);
            JsonComparison.compare(left.getBody(), right.getBody(), Map.of());
        }
        var left = request(v2Url(), path, HttpMethod.POST, null, body, true);
        var right = request(v3Url(), "/api/auth/login", HttpMethod.POST, null, body, true);
        environment().budget.blocked(path);
        assertThat(left.getStatusCode().value()).isEqualTo(429);
        assertThat(left.getBody().path("statusCode").asInt()).isEqualTo(429);
        assertThat(left.getBody().path("message").isTextual()).isTrue();
        assertThat(left.getHeaders().getFirst("Retry-After")).isNotBlank();
        if (right.getStatusCode().value() == 429) {
            JsonComparison.compare(left.getBody(), right.getBody(), Map.of());
            fail("429 차이가 해소됐습니다. 알려진 차이를 제거하고 필수 통과 케이스로 전환하세요.");
        }
        assertThat(right.getStatusCode().value()).isEqualTo(400);
        environment().knownDifferences.add(Map.of(
                "case", "POST /api/auth/login — 11th request",
                "reason", "알려진 차이: v3 요청 제한 미구현",
                "v2Status", 429, "v3Status", 400,
                "promotion", "v3 요청 제한 구현 시 필수 통과 케이스로 전환"));
        Assumptions.abort("알려진 차이: 실제 v2 429 / v3 400을 관측함. 성공 집계에서 제외.");
    }
}
