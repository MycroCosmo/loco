package com.loco.auth;

import java.time.*;
import org.springframework.stereotype.Service;

/** 각각의 repository 삭제가 독립 커밋한다. 한쪽 실패에도 다른 쪽을 실행한다. */
@Service
public class TokenCleanupService {
  private final RefreshTokenRepository refresh;
  private final PasswordResetTokenRepository reset;
  private final Clock clock;

  public TokenCleanupService(
      RefreshTokenRepository refresh, PasswordResetTokenRepository reset, Clock clock) {
    this.refresh = refresh;
    this.reset = reset;
    this.clock = clock;
  }

  public record Deleted(int refreshTokens, int resetTokens) {}

  public Deleted cleanup() {
    int refreshCount = 0, resetCount = 0;
    RuntimeException failure = null;
    var cutoff =
        LocalDateTime.ofInstant(clock.instant().minus(Duration.ofDays(30)), ZoneOffset.UTC);
    var resetCutoff = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    try {
      refreshCount = refresh.cleanup(cutoff);
    } catch (RuntimeException error) {
      failure = error;
    }
    try {
      resetCount = reset.cleanup(resetCutoff);
    } catch (RuntimeException error) {
      if (failure == null) failure = error;
      else failure.addSuppressed(error);
    }
    if (failure != null) throw failure;
    return new Deleted(refreshCount, resetCount);
  }
}
