package com.loco.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import com.loco.compat.CompatibilityTestBase;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 원본 v2만 관측한다. Firebase 발송·v3 예약 작업 구현 테스트가 아니다. */
@Tag("v2-comparison")
class NotificationsObservationTest extends CompatibilityTestBase {
  @ParameterizedTest
  @ValueSource(strings = {"service", "call-sites", "cleanup"})
  void observesV2WithoutCredentialsOrExternalTransport(String mode) throws Exception {
    environment().reset("places/seed.sql");
    String script;
    try (var in = getClass().getResourceAsStream("/notifications/observe-v2.cjs")) {
      script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    var result = environment().nodeCheck(script, Map.of("mode", mode));
    assertThat(result.has("error")).as(result.toPrettyString()).isFalse();
    assertThat(result.path("transport").path("blockedProbes").asInt()).isEqualTo(3);
    assertThat(result.path("transport").path("unexpectedAttempts").asInt()).isZero();
    assertThat(result.path("rows").size()).isGreaterThanOrEqualTo(5);
    Path report = Path.of("build/reports/notifications/" + mode + "-v2.json");
    Files.createDirectories(report.getParent());
    mapper.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), result);
    if (!"true".equals(System.getenv("NOTIFICATIONS_OBSERVE")))
      try (var in = getClass().getResourceAsStream("/notifications/" + mode + "-v2.json")) {
        assertThat(result).isEqualTo(mapper.readTree(in));
      }
  }
}
