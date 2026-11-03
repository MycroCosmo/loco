package com.loco.auth;

import java.util.Map;
import java.util.stream.Stream;
import com.loco.compat.ComparisonCase;
import com.loco.compat.ComparisonStep;
import com.loco.compat.SharedHarness;
import org.springframework.http.HttpMethod;

public final class AuthenticationCases {
    private AuthenticationCases() { }
    public static Stream<ComparisonCase> errors() {
        return Stream.of(
                error("회원가입 DTO·추가 필드", "register", Map.of("email", "bad", "password", "x", "nickname", "a", "extra", true), 400),
                error("회원가입 중복 이메일", "register", Map.of("email", SharedHarness.Environment.EMAIL, "password", "123456", "nickname", "중복"), 409),
                error("로그인 누락 값", "login", Map.of(), 400),
                error("로그인 추가 필드", "login", Map.of("email", "user@example.test", "password", "123456", "extra", true), 400),
                error("로그인 없는 회원", "login", Map.of("email", "missing@example.test", "password", "123456"), 401),
                error("로그인 틀린 비밀번호", "login", Map.of("email", SharedHarness.Environment.EMAIL, "password", "incorrect"), 401));
    }
    private static ComparisonCase error(String name, String path, Object body, int status) {
        return ComparisonCase.named("1번 묶음: " + name).databaseUnchanged()
                .step(ComparisonStep.named(name).method(HttpMethod.POST).path("/api/auth/" + path)
                        .body(ignored -> body).expectedStatus(status).build()).build();
    }
}
