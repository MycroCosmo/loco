package com.loco.compat;

import java.util.stream.Stream;
import org.springframework.http.HttpMethod;

public final class FoundationCases {
  private FoundationCases() {}

  public static Stream<ComparisonCase> cases() {
    return Stream.of(
        ComparisonCase.named("0번 묶음: 없는 경로 404")
            .databaseUnchanged()
            .step(
                ComparisonStep.named("없는 경로")
                    .method(HttpMethod.GET)
                    .path("/api/__comparison_missing")
                    .expectedStatus(404)
                    .build())
            .build(),
        ComparisonCase.named("0번 묶음: 실제 사용자 API 미인증 401")
            .databaseUnchanged()
            .step(
                ComparisonStep.named("미인증")
                    .method(HttpMethod.GET)
                    .path("/api/users/me")
                    .expectedStatus(401)
                    .build())
            .build());
  }
}
