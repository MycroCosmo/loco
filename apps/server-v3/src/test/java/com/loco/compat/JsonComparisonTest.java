package com.loco.compat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonComparisonTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void explicitLeafExceptionsKeepArrayShapeAndOtherValues() throws Exception {
    JsonComparison.compare(
        json.readTree("{\"items\":[{\"id\":\"a\",\"name\":\"same\"}]}"),
        json.readTree("{\"items\":[{\"id\":\"b\",\"name\":\"same\"}]}"),
        Map.of("/items/*/id", "생성 ID"));
  }

  @Test
  void missingIgnoredKeyStillFails() throws Exception {
    var left = json.readTree("{\"id\":\"a\"}");
    var right = json.readTree("{}");
    assertThatThrownBy(() -> JsonComparison.compare(left, right, Map.of("/id", "생성 ID")))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void ignoredTypeOrNullMismatchStillFails() throws Exception {
    var left = json.readTree("{\"id\":\"a\"}");
    var right = json.readTree("{\"id\":null}");
    assertThatThrownBy(() -> JsonComparison.compare(left, right, Map.of("/id", "생성 ID")))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void misspelledRuleFailsEvenWhenBodiesMatch() throws Exception {
    var body = json.readTree("{\"id\":\"a\"}");
    assertThatThrownBy(() -> JsonComparison.compare(body, body, Map.of("/typo", "오타")))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void ignoredValueDoesNotHideArrayLength() throws Exception {
    var left = json.readTree("{\"ids\":[\"a\"]}");
    var right = json.readTree("{\"ids\":[\"b\",\"c\"]}");
    assertThatThrownBy(() -> JsonComparison.compare(left, right, Map.of("/ids/*", "생성 ID")))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void malformedJsonMessageExceptionIsCaseSpecific() throws Exception {
    var left =
        json.readTree("{\"statusCode\":400,\"message\":\"parser A\",\"error\":\"Bad Request\"}");
    var right =
        json.readTree("{\"statusCode\":400,\"message\":\"parser B\",\"error\":\"Bad Request\"}");
    JsonComparison.compare(left, right, Map.of("/message", "깨진 JSON: 런타임 파서 문구 차이"));
    assertThatThrownBy(() -> JsonComparison.compare(left, right, Map.of()))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void arrayOrderAndNonIgnoredFieldsRemainStrict() throws Exception {
    var left = json.readTree("{\"message\":[\"first\",\"second\"]}");
    var right = json.readTree("{\"message\":[\"second\",\"first\"]}");
    assertThatThrownBy(() -> JsonComparison.compare(left, right, Map.of()))
        .isInstanceOf(AssertionError.class);
  }
}
