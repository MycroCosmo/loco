package com.loco.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "loco.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class TokenCleanupSchedule {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(TokenCleanupSchedule.class);
  private final TokenCleanupService cleanup;

  public TokenCleanupSchedule(TokenCleanupService cleanup) {
    this.cleanup = cleanup;
  }

  @Scheduled(cron = "${loco.cleanup.cron:0 0 3 * * *}", zone = "${loco.cleanup.zone:Asia/Seoul}")
  public void run() {
    try {
      var result = cleanup.cleanup();
      log.info("Token cleanup: refresh={}, reset={}", result.refreshTokens(), result.resetTokens());
    } catch (RuntimeException error) {
      log.warn("Token cleanup failed: cause={}", error.getClass().getSimpleName());
    }
  }
}
