package com.loco.compat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.http.HttpMethod;

public record ComparisonStep(String name, HttpMethod method, Function<ScenarioValues, String> path,
                             Function<ScenarioValues, String> v3Path, String bodyResource,
                             Function<ScenarioValues, Object> body, Function<ScenarioValues, String> bearer,
                             boolean authenticated, int expectedStatus, Map<String, String> valueExceptions,
                             Map<String, String> captures) {
    public ComparisonStep {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("단계 이름 필요");
        Objects.requireNonNull(method, "HTTP 메서드 필요");
        Objects.requireNonNull(path, "요청 경로 필요");
        if (v3Path == null) v3Path = path;
        if (expectedStatus < 100 || expectedStatus > 599) throw new IllegalArgumentException("기대 상태 필요");
        if (bodyResource != null && body != null) throw new IllegalArgumentException("본문 지정 중복");
        if (authenticated && bearer != null) throw new IllegalArgumentException("인증 지정 중복");
        valueExceptions = Map.copyOf(valueExceptions);
        captures = Map.copyOf(captures);
        captures.forEach((alias, pointer) -> {
            if (alias.isBlank()) throw new IllegalArgumentException("응답 값 이름 필요");
            com.fasterxml.jackson.core.JsonPointer.compile(pointer);
        });
    }
    public static Builder named(String name) { return new Builder(name); }

    public static final class Builder {
        private final String name;
        private HttpMethod method;
        private Function<ScenarioValues, String> path, v3Path, bearer;
        private Function<ScenarioValues, Object> body;
        private String bodyResource;
        private boolean authenticated;
        private int expectedStatus;
        private final Map<String, String> valueExceptions = new LinkedHashMap<>();
        private final Map<String, String> captures = new LinkedHashMap<>();

        private Builder(String name) { this.name = name; }
        public Builder method(HttpMethod value) { method = value; return this; }
        public Builder path(String value) { Objects.requireNonNull(value); return path(ignored -> value); }
        public Builder path(Function<ScenarioValues, String> value) { path = value; return this; }
        public Builder v3Path(String value) { Objects.requireNonNull(value); return v3Path(ignored -> value); }
        public Builder v3Path(Function<ScenarioValues, String> value) { v3Path = value; return this; }
        public Builder bodyResource(String value) { bodyResource = value; return this; }
        public Builder body(Function<ScenarioValues, Object> value) { body = value; return this; }
        public Builder bearer(Function<ScenarioValues, String> value) { bearer = value; return this; }
        public Builder authenticated() { authenticated = true; return this; }
        public Builder expectedStatus(int value) { expectedStatus = value; return this; }
        public Builder valueException(String pointer, String reason) { valueExceptions.put(pointer, reason); return this; }
        public Builder capture(String alias, String pointer) {
            if (captures.putIfAbsent(alias, pointer) != null) throw new IllegalArgumentException("응답 값 이름 중복: " + alias);
            return this;
        }
        public ComparisonStep build() {
            return new ComparisonStep(name, method, path, v3Path, bodyResource, body, bearer,
                    authenticated, expectedStatus, valueExceptions, captures);
        }
    }
}
