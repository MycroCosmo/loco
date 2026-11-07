package com.loco.compat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class ScenarioCaseTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void builderRejectsIncompleteOrAmbiguousCasesAndFreezesSteps() {
    assertThatThrownBy(() -> ComparisonCase.named("empty").build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> ComparisonStep.named("no status").method(HttpMethod.GET).path("/api/x").build())
        .isInstanceOf(IllegalArgumentException.class);
    var step =
        ComparisonStep.named("request").method(HttpMethod.POST).path("/api/x").expectedStatus(201);
    assertThatThrownBy(() -> step.bodyResource("x.json").body(v -> Map.of()).build())
        .isInstanceOf(IllegalArgumentException.class);
    var request =
        ComparisonStep.named("get")
            .method(HttpMethod.GET)
            .path("/api/x")
            .expectedStatus(200)
            .build();
    var builder = ComparisonCase.named("one").step(request);
    var built = builder.build();
    builder.step(request);
    assertThat(built.steps()).hasSize(1);
    assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void missingNullDuplicateAndNonTextCapturesFailWithoutLeakingValues() throws Exception {
    var values = new ScenarioValues();
    var response = json.readTree("{\"token\":\"sensitive\",\"empty\":null,\"number\":1}");
    assertThatThrownBy(() -> values.capture(response, Map.of("missing", "/missing")))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("누락");
    assertThatThrownBy(() -> values.capture(response, Map.of("empty", "/empty")))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("null");
    assertThatThrownBy(() -> values.text("future")).isInstanceOf(AssertionError.class);
    values.capture(response, Map.of("token", "/token", "number", "/number"));
    assertThatThrownBy(() -> values.capture(response, Map.of("token", "/token")))
        .isInstanceOf(AssertionError.class)
        .hasMessageNotContaining("sensitive");
    assertThatThrownBy(() -> values.text("number")).isInstanceOf(AssertionError.class);
  }

  @Test
  void capturedTokensFeedOnlyTheirOwnServersHeadersAndJsonBodies() throws Exception {
    var left = new ScenarioValues();
    var right = new ScenarioValues();
    var captures = Map.of("access", "/accessToken", "refresh", "/refreshToken");
    left.capture(
        json.readTree("{\"accessToken\":\"left-access\",\"refreshToken\":\"left-refresh\"}"),
        captures);
    right.capture(
        json.readTree("{\"accessToken\":\"right-access\",\"refreshToken\":\"right-refresh\"}"),
        captures);
    var step =
        ComparisonStep.named("refresh")
            .method(HttpMethod.POST)
            .path("/api/auth/refresh")
            .expectedStatus(201)
            .bearer(v -> v.text("access"))
            .body(v -> Map.of("refreshToken", v.text("refresh")))
            .build();
    assertThat(step.bearer().apply(left)).isEqualTo("left-access");
    assertThat(step.bearer().apply(right)).isEqualTo("right-access");
    assertThat(step.body().apply(left)).isEqualTo(Map.of("refreshToken", "left-refresh"));
    assertThat(step.body().apply(right)).isEqualTo(Map.of("refreshToken", "right-refresh"));
  }
}
