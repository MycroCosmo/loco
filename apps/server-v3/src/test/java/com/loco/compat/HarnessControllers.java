package com.loco.compat;

import java.util.Map;
import com.loco.security.AuthenticatedApi;
import com.loco.security.PublicApi;
import com.loco.security.JwtPrincipal;
import com.loco.web.ApiController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

@TestConfiguration(proxyBeanMethods = false)
@Import(HarnessControllers.ProbeController.class)
public class HarnessControllers {
    @org.springframework.context.annotation.Bean
    HarnessRateLimitTime rateLimitTime() { return new HarnessRateLimitTime(); }
    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Primary
    com.loco.security.RequestRateLimiter comparisonRateLimiter(HarnessRateLimitTime time) { return time.limiter(); }
    @TestComponent
    @ApiController
    @AuthenticatedApi
    @Import(PublicProbeController.class)
    public static class ProbeController {
        private final JdbcTemplate jdbc;
        public ProbeController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

        // 하네스의 쓰기/DB 비교 동작을 검증하는 테스트 전용 대역이며 운영 API가 아니다.
        @PostMapping("/__test/harness/feedback")
        @ResponseStatus(HttpStatus.CREATED)
        public Map<String, String> writeProbe(@AuthenticationPrincipal JwtPrincipal user,
                                              @RequestBody Map<String, String> body) {
            String id = java.util.UUID.randomUUID().toString();
            jdbc.update("INSERT INTO feedbacks (id, \"userId\", content) VALUES (?, ?, ?)",
                    id, user.userId(), body.get("content"));
            return Map.of("message", "피드백이 제출되었습니다.", "id", id);
        }
        @GetMapping("/__test/harness/users/me")
        public Map<String, Object> me(@AuthenticationPrincipal JwtPrincipal user, HttpServletRequest request) {
            return Map.of("userId", user.userId(), "email", user.email(), "session", request.getSession(false) != null);
        }

        @PutMapping("/__test/harness/users/me")
        public Map<String, String> update(@Valid @RequestBody UpdateProbe body) { return Map.of("status", "ok"); }

        @GetMapping("/__test/harness/public-override")
        @PublicApi
        public Map<String, String> publicOverride() { return Map.of("status", "ok"); }

        @GetMapping("/__test/harness/restricted")
        @PreAuthorize("hasAuthority('unassigned')")
        public Map<String, String> restricted() { return Map.of("status", "ok"); }
    }

    @TestComponent
    @ApiController
    @PublicApi
    public static class PublicProbeController {
        @PostMapping("/__test/harness/auth/login")
        public Map<String, String> validate(@Valid @RequestBody LoginProbe body) { return Map.of("status", "ok"); }

        @GetMapping("/__test/harness/method-protected")
        @AuthenticatedApi
        public Map<String, String> protectedMethod() { return Map.of("status", "ok"); }
    }

    public record LoginProbe(@Email(message = "email must be an email") String email,
            @Size(min = 6, message = "password must be longer than or equal to 6 characters") String password) { }
    public record UpdateProbe(@Size(max = 30,
            message = "nickname must be shorter than or equal to 30 characters") String nickname) { }
}
