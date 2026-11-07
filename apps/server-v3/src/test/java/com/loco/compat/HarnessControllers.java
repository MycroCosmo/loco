package com.loco.compat;

import com.loco.security.AuthenticatedApi;
import com.loco.security.PublicApi;
import com.loco.web.ApiController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@TestConfiguration(proxyBeanMethods = false)
@Import(HarnessControllers.ProbeController.class)
public class HarnessControllers {
  @org.springframework.context.annotation.Bean
  HarnessRateLimitTime rateLimitTime() {
    return new HarnessRateLimitTime();
  }

  @org.springframework.context.annotation.Bean
  @org.springframework.context.annotation.Primary
  com.loco.security.RequestRateLimiter comparisonRateLimiter(HarnessRateLimitTime time) {
    return time.limiter();
  }

  @TestComponent
  @ApiController
  @AuthenticatedApi
  @Import(PublicProbeController.class)
  public static class ProbeController {
    @GetMapping("/__test/harness/public-override")
    @PublicApi
    public Map<String, String> publicOverride() {
      return Map.of("status", "ok");
    }

    @GetMapping("/__test/harness/restricted")
    @PreAuthorize("hasAuthority('unassigned')")
    public Map<String, String> restricted() {
      return Map.of("status", "ok");
    }
  }

  @TestComponent
  @ApiController
  @PublicApi
  public static class PublicProbeController {
    @PostMapping("/__test/harness/auth/login")
    public Map<String, String> validate(@Valid @RequestBody LoginProbe body) {
      return Map.of("status", "ok");
    }

    @GetMapping("/__test/harness/method-protected")
    @AuthenticatedApi
    public Map<String, String> protectedMethod() {
      return Map.of("status", "ok");
    }
  }

  public record LoginProbe(
      @Email(message = "email must be an email") String email,
      @Size(min = 6, message = "password must be longer than or equal to 6 characters")
          String password) {}
}
