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
    public record Refresh(String refreshToken) { }
    public record Logout(String refreshToken) { }
    public record Forgot(String email) { }
    public record Reset(String email, String code, String newPassword) { }
    public record OAuth(String code, String redirectUri, String state) { }
    private AuthInputs() { }

    public static OAuth oauth(JsonNode body) {
        var input = new Input(body, Set.of("code", "redirectUri", "state"));
        input.string("code", null, null);
        if (!V2Url.valid(input.text("redirectUri"))) input.errors.add("redirectUri must be a URL address");
        input.string("redirectUri", null, null);
        if (!input.body.path("state").isMissingNode() && !input.body.path("state").isNull()) input.string("state", null, null);
        input.finish();
        return new OAuth(input.text("code"), input.text("redirectUri"), input.text("state"));
    }

    public static Forgot forgot(JsonNode body) {
        var input = new Input(body, Set.of("email"));
        input.recoveryEmail();
        input.finish();
        return new Forgot(input.text("email"));
    }
    public static Reset reset(JsonNode body) {
        var input = new Input(body, Set.of("email", "code", "newPassword"));
        input.recoveryEmail();
        String code = input.text("code");
        if (code == null || code.codePointCount(0, code.length()) != 6) input.errors.add("코드는 6자리여야 합니다.");
        input.string("code", null, null);
        String password = input.text("newPassword");
        if (password == null || password.codePointCount(0, password.length()) < 6) input.errors.add("새 비밀번호는 6자 이상이어야 합니다.");
        input.string("newPassword", null, null);
        input.finish();
        return new Reset(input.text("email"), code, password);
    }

    public static JsonNode httpBody(JsonNode body) {
        // Express JSON parser의 strict 모드처럼 JSON 원시 값은 DTO 검증 전에 거절한다.
        if (body != null && !body.isObject() && !body.isArray()) throw ApiProblem.badRequest("Malformed JSON");
        return body;
    }

    public static Refresh refresh(JsonNode body) {
        var input = new Input(body, Set.of("refreshToken"));
        input.string("refreshToken", null, null);
        input.finish();
        return new Refresh(input.text("refreshToken"));
    }
    public static Logout logout(JsonNode body) {
        var input = new Input(body, Set.of("refreshToken"));
        if (!input.body.path("refreshToken").isMissingNode() && !input.body.path("refreshToken").isNull()) {
            input.string("refreshToken", null, null);
        }
        input.finish();
        return new Logout(input.text("refreshToken"));
    }

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
        void recoveryEmail() { if (!V2Email.valid(text("email"))) errors.add("올바른 이메일을 입력하세요."); }
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
