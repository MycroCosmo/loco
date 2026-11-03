package com.loco.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.loco.web.ApiProblem;

public final class AuthInputs {
    public record Register(String email, String password, String nickname) { }
    public record Login(String email, String password) { }
    private AuthInputs() { }

    public static Register register(JsonNode body) {
        var input = new Input(body, Set.of("email", "password", "nickname"));
        input.email();
        input.string("password", 6, 50);
        input.string("nickname", 2, 30);
        input.finish();
        return new Register(input.text("email"), input.text("password"), input.text("nickname"));
    }
    public static Login login(JsonNode body) {
        var input = new Input(body, Set.of("email", "password"));
        input.email();
        input.string("password", 6, null);
        input.finish();
        return new Login(input.text("email"), input.text("password"));
    }
    private static final class Input {
        private final JsonNode body;
        private final List<String> errors = new ArrayList<>();
        Input(JsonNode body, Set<String> allowed) {
            this.body = body == null ? MissingNode.getInstance() : body;
            this.body.fieldNames().forEachRemaining(name -> {
                // class-transformer가 DTO 변환 시 버리는 키와 동일하게 처리한다.
                if (!allowed.contains(name) && !name.equals("__proto__") && !name.equals("constructor")) {
                    errors.add("property " + name + " should not exist");
                }
            });
            if (this.body.isArray()) for (int i = 0; i < this.body.size(); i++) errors.add("property " + i + " should not exist");
        }
        String text(String name) { return body.path(name).isTextual() ? body.path(name).asText() : null; }
        void email() { if (!V2Email.valid(text("email"))) errors.add("email must be an email"); }
        void string(String name, Integer min, Integer max) {
            String value = text(name);
            int size = value == null ? -1 : value.codePointCount(0, value.length());
            // class-validator의 DTO decorator 평가 순서: MaxLength → MinLength → IsString.
            if (max != null && (value == null || size > max)) errors.add(name + " must be shorter than or equal to " + max + " characters");
            if (min != null && (value == null || size < min)) errors.add(name + " must be longer than or equal to " + min + " characters");
            if (value == null) errors.add(name + " must be a string");
        }
        void finish() { if (!errors.isEmpty()) throw ApiProblem.invalid(errors); }
    }
}
