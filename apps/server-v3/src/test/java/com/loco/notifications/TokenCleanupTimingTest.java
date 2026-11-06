package com.loco.notifications;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.loco.auth.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TokenCleanupTimingTest {
  @Test
  void slowFirstDeleteDoesNotMoveTheSecondExpiryBoundary() {
    var start = Instant.parse("2026-11-06T00:00:00Z");
    var now = new AtomicReference<>(start);
    var clock = mock(Clock.class);
    when(clock.instant()).thenAnswer(call -> now.get());
    var refresh = mock(RefreshTokenRepository.class);
    var reset = mock(PasswordResetTokenRepository.class);
    when(refresh.cleanup(any()))
        .thenAnswer(
            call -> {
              now.set(start.plusSeconds(60));
              return 2;
            });
    when(reset.cleanup(LocalDateTime.ofInstant(start, ZoneOffset.UTC))).thenReturn(3);
    assertThat(new TokenCleanupService(refresh, reset, clock).cleanup())
        .isEqualTo(new TokenCleanupService.Deleted(2, 3));
    verify(reset).cleanup(LocalDateTime.ofInstant(start, ZoneOffset.UTC));
  }
}
