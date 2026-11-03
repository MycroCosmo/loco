package com.loco.compat;

import java.util.stream.Stream;
import org.springframework.http.HttpMethod;

public final class FoundationCases {
    private FoundationCases() { }
    public static Stream<ComparisonCase> cases() {
        return Stream.of(
                ComparisonCase.named("0번 묶음: 없는 경로 404").databaseUnchanged()
                        .step(ComparisonStep.named("없는 경로").method(HttpMethod.GET)
                                .path("/api/__comparison_missing").expectedStatus(404).build()).build(),
                ComparisonCase.named("0번 묶음: 미인증 보호 경로 401 (테스트 컨트롤러)").databaseUnchanged()
                        .step(ComparisonStep.named("미인증").method(HttpMethod.GET).path("/api/users/me")
                                .v3Path("/api/__test/harness/users/me").expectedStatus(401).build()).build());
    }
}
