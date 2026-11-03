package com.loco.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.loco.compat.CompatibilityTestBase;
import com.loco.compat.JsonComparison;
import com.loco.web.ApiProblem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.assertThat;

class InputCompatibilityTest extends CompatibilityTestBase {
    @Autowired V2PasswordEncoder passwords;

    @Test
    void bcryptHashesWorkInBothRuntimesIncludingUtf8ByteBoundaries() throws Exception {
        var raw = List.of(environment().password, "가".repeat(24), "a".repeat(71) + "가", "가".repeat(30),
                "p".repeat(72) + "tail", "nul\u0000inside", "😀".repeat(25));
        var input = new ArrayList<Map<String, String>>();
        for (String password : raw) input.add(Map.of("raw", password, "javaHash", passwords.encode(password)));
        var result = environment().nodeCheck("""
                const bcrypt=require('bcrypt');
                Promise.all(JSON.parse(process.argv[1]).map(async x=>({
                  hash:await bcrypt.hash(x.raw,10), matches:await bcrypt.compare(x.raw,x.javaHash)
                }))).then(x=>process.stdout.write(JSON.stringify(x)));
                """, input);
        for (int i = 0; i < raw.size(); i++) {
            assertThat(result.get(i).path("matches").asBoolean()).as("v2 검증 index=%s", i).isTrue();
            assertThat(passwords.matches(raw.get(i), result.get(i).path("hash").asText())).as("v3 검증 index=%s", i).isTrue();
            assertThat(passwords.matches("different", result.get(i).path("hash").asText())).isFalse();
        }
        String hash = passwords.encode("a".repeat(72) + "one");
        assertThat(passwords.matches("a".repeat(72) + "two", hash)).isTrue();
    }

    @Test
    void emailValidationMatchesThePinnedV2Validator() throws Exception {
        var emails = List.of("user@example.test", "a@localhost", "a@b.c", "a@b.12", "a@-b.com", "a@b-.com",
                "a@b_.com", "a..b@example.test", "가나다@도메인.한국", "😀@example.test", "\"a@b\"@example.test",
                "\"\"@example.test", "a+b@example.test", "a".repeat(65) + "@example.test", "a@b.com.",
                "Name <a@b.com>", "a@[127.0.0.1]", "a@ａｂ.com", "a@😀.com", "a\u0000b@example.test");
        var expected = environment().nodeCheck(
                "process.stdout.write(JSON.stringify(JSON.parse(process.argv[1]).map(x=>require('validator').isEmail(x))))", emails);
        for (int i = 0; i < emails.size(); i++) {
            assertThat(V2Email.valid(emails.get(i))).as("email index=%s", i).isEqualTo(expected.get(i).asBoolean());
        }
    }

    @Test
    void validationPipeMessagesMatchForMissingTypesBoundsAndExtraFields() throws Exception {
        var bodies = List.of("{}", "null", "[]", "{\"refreshToken\":\"token\"}", "{\"refreshToken\":null}",
                "{\"refreshToken\":3}", "{\"refreshToken\":\"\"}",
                "{\"email\":\"bad\",\"password\":3,\"nickname\":null,\"extra\":true}",
                "{\"email\":\"a@example.test\",\"password\":\"12345\",\"nickname\":\"a\"}",
                "{\"email\":\"a@example.test\",\"password\":\"123456\",\"nickname\":\"ok\",\"x\":1,\"y\":2}",
                "{\"email\":\"a@example.test\",\"password\":\"" + "a".repeat(51) + "\",\"nickname\":\"" + "가".repeat(31) + "\"}",
                "{\"email\":\"a@example.test\",\"password\":\"123456\",\"nickname\":\"😀😀\"}",
                "{\"email\":\"a@example.test\",\"password\":\"123456\",\"nickname\":\"ok\",\"constructor\":7,\"__proto__\":{}}");
        var input = new ArrayList<Map<String, Object>>();
        for (String type : List.of("register", "login", "refresh", "logout")) {
            for (String body : bodies) input.add(Map.of("type", type, "body", mapper.readTree(body)));
        }
        var expected = environment().nodeCheck("""
                require('reflect-metadata');
                const {ValidationPipe}=require('@nestjs/common');
                const types={register:require('./dist/auth/dto/register.dto').RegisterDto,
                             login:require('./dist/auth/dto/login.dto').LoginDto,
                             refresh:require('./dist/auth/dto/refresh-token.dto').RefreshTokenDto,
                             logout:require('./dist/auth/dto/logout.dto').LogoutDto};
                const pipe=new ValidationPipe({whitelist:true,transform:true,forbidNonWhitelisted:true});
                Promise.all(JSON.parse(process.argv[1]).map(async x=>{
                  try {await pipe.transform(x.body,{type:'body',metatype:types[x.type]});return null;}
                  catch(e){return e.getResponse();}
                })).then(x=>process.stdout.write(JSON.stringify(x)));
                """, input);
        for (int i = 0; i < input.size(); i++) {
            JsonNode actual = mapper.nullNode();
            try {
                JsonNode body = (JsonNode) input.get(i).get("body");
                switch ((String) input.get(i).get("type")) {
                    case "register" -> AuthInputs.register(body);
                    case "login" -> AuthInputs.login(body);
                    case "refresh" -> AuthInputs.refresh(body);
                    case "logout" -> AuthInputs.logout(body);
                }
            } catch (ApiProblem problem) { actual = mapper.valueToTree(problem.body()); }
            JsonComparison.compare(expected.get(i), actual, Map.of());
        }
    }
}
