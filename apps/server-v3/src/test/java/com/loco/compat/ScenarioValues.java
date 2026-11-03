package com.loco.compat;

import java.util.HashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;

/** 한 케이스 안의 한 서버 응답 값만 보관한다. 실패 메시지에 실제 값은 출력하지 않는다. */
public final class ScenarioValues {
    private final Map<String, JsonNode> values = new HashMap<>();

    public void capture(JsonNode response, Map<String, String> captures) {
        captures.forEach((alias, pointer) -> {
            JsonNode value = response == null ? null : response.at(pointer);
            if (value == null || value.isMissingNode() || value.isNull()) {
                throw new AssertionError("응답 값 누락/null: " + alias + " (" + pointer + ")");
            }
            if (values.putIfAbsent(alias, value.deepCopy()) != null) {
                throw new AssertionError("응답 값 이름 중복: " + alias);
            }
        });
    }

    public JsonNode value(String alias) {
        JsonNode value = values.get(alias);
        if (value == null) throw new AssertionError("저장하지 않은 응답 값: " + alias);
        return value.deepCopy();
    }

    public String text(String alias) {
        JsonNode value = value(alias);
        if (!value.isTextual() || value.asText().isBlank()) throw new AssertionError("문자열 응답 값 필요: " + alias);
        return value.asText();
    }
}
