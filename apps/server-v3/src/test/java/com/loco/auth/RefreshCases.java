package com.loco.auth;

import com.loco.compat.ComparisonCase;
import com.loco.compat.ComparisonStep;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.http.HttpMethod;

public final class RefreshCases {
  private RefreshCases() {}

  public static Stream<ComparisonCase> cases() {
    return Stream.of(
        sample("재발급 누락", "refresh", Map.of(), 400),
        sample("재발급 null", "refresh", "{\"refreshToken\":null}", 400),
        sample("재발급 타입·추가 필드", "refresh", Map.of("refreshToken", 3, "extra", true), 400),
        sample("재발급 빈 토큰", "refresh", Map.of("refreshToken", ""), 401),
        sample("재발급 미등록 토큰", "refresh", Map.of("refreshToken", "unknown"), 401),
        sample("로그아웃 생략", "logout", Map.of(), 201),
        sample("로그아웃 null", "logout", "{\"refreshToken\":null}", 201),
        sample("로그아웃 빈 토큰", "logout", Map.of("refreshToken", ""), 201),
        sample("로그아웃 미등록 토큰", "logout", Map.of("refreshToken", "unknown"), 201),
        sample("로그아웃 타입", "logout", Map.of("refreshToken", 3), 400),
        sample("로그아웃 추가 필드", "logout", Map.of("extra", true), 400));
  }

  private static ComparisonCase sample(String name, String action, Object body, int status) {
    return ComparisonCase.named("1번 묶음: " + name)
        .databaseUnchanged()
        .step(
            ComparisonStep.named(action)
                .method(HttpMethod.POST)
                .path("/api/auth/" + action)
                .body(v -> body)
                .bearer(v -> "malformed-jwt")
                .expectedStatus(status)
                .build())
        .build();
  }
}
