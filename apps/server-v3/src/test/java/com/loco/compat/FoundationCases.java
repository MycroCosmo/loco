package com.loco.compat;

import java.util.Map;
import java.util.stream.Stream;
import org.springframework.http.HttpMethod;

public final class FoundationCases {
    private FoundationCases() { }
    public static Stream<ComparisonCase> cases() {
        return Stream.of(
                new ComparisonCase("0번 묶음: 없는 경로 404", HttpMethod.GET, "/api/__comparison_missing",
                        null, 404, false, "compat/seed.sql", Map.of(), DatabaseSnapshot.Rules.defaults(), true),
                new ComparisonCase("0번 묶음: 미인증 보호 경로 401 (테스트 컨트롤러)", HttpMethod.GET, "/api/users/me",
                        null, 401, false, "compat/seed.sql", Map.of(), DatabaseSnapshot.Rules.defaults(), true));
    }
}
