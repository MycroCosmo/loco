package com.loco.notifications;

import static org.assertj.core.api.Assertions.*;

import com.loco.compat.CompatibilityTestBase;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.*;

@Tag("v2-comparison")
class FcmRetryComparisonTest extends CompatibilityTestBase {
  @Test
  void observesOriginalSdkRetryAndRealBackoffOnLoopbackOnly() throws Exception {
    String script;
    try (var in = getClass().getResourceAsStream("/notifications/retry-v2.cjs")) {
      script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    var result = environment().nodeCheck(script, Map.of());
    assertThat(result.has("error")).as(result.toString()).isFalse();
    assertThat(result.path("rows").size()).isEqualTo(8);
    var path = Path.of("build/reports/notifications/retry-v2.json");
    Files.createDirectories(path.getParent());
    mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), result);
    assertThat(result.path("rows").get(0).path("delaysMillis"))
        .isEqualTo(mapper.readTree("[0,1000,2000,4000]"));
  }
}
