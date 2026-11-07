package com.loco.favorites;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.*;

/** v3 검색 구현이 아니라 원본 v2와 루프백 가짜 외부 서버의 계약 관측이다. */
@Tag("v2-comparison")
class KakaoObservationTest extends SubmissionTestSupport {
  @Test
  void capturesOnlyLoopbackFakeUpstreamResponses() throws Exception {
    seed();
    String script =
        new String(
            getClass().getResourceAsStream("/kakao/observe-fake.cjs").readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
    var value = environment().nodeCheck(script, Map.of("user", user(1)));
    assertThat(value.has("error")).as(value.toPrettyString()).isFalse();
    assertThat(value.path("transport").path("realKakaoCalls").asInt(-1)).isZero();
    assertThat(value.path("transport").path("apiKeyUsed").asBoolean(true)).isFalse();
    assertThat(value.path("rows").size()).isGreaterThan(40);
    Path target = Path.of("build/reports/kakao/http-v2.json");
    Files.createDirectories(target.getParent());
    mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), value);
    if (!Boolean.getBoolean("kakao.observe"))
      try (var in = getClass().getResourceAsStream("/kakao/http-v2.json")) {
        assertThat(value).isEqualTo(mapper.readTree(in));
      }
  }
}
