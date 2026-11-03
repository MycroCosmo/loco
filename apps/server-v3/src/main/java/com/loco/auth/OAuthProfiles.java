package com.loco.auth;

import java.io.IOException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loco.users.Provider;
import com.loco.web.ApiProblem;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

@Service
public class OAuthProfiles {
    public record Profile(Provider provider, String id, String email, String nickname, String image) { }
    private final RestClient http;
    private final Environment env;
    private final ObjectMapper json;
    public OAuthProfiles(RestClient.Builder builder, Environment env, ObjectMapper json) {
        this.http = builder.build(); this.env = env; this.json = json;
    }
    public Profile fetch(String provider, AuthInputs.OAuth input) {
        return switch (provider) {
            case "kakao" -> kakao(input);
            case "naver" -> naver(input);
            default -> throw ApiProblem.badRequest("지원하지 않는 간편 로그인입니다.");
        };
    }
    private Profile kakao(AuthInputs.OAuth input) {
        var params = new LinkedMultiValueMap<String, String>();
        params.add("grant_type", "authorization_code"); params.add("client_id", required("KAKAO_REST_API_KEY"));
        params.add("redirect_uri", input.redirectUri()); params.add("code", input.code());
        String secret = env.getProperty("KAKAO_CLIENT_SECRET");
        if (secret != null && !secret.isEmpty()) params.add("client_secret", secret);
        var token = read(http.post().uri("https://kauth.kakao.com/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).body(params));
        String access = text(token, "access_token", null);
        if (access == null || access.isEmpty()) throw ApiProblem.unauthorized(text(token, "error_description", "카카오 인증에 실패했습니다."));
        var me = read(http.get().uri("https://kapi.kakao.com/v2/user/me").headers(h -> h.setBearerAuth(access)));
        String id = me.has("id") ? me.path("id").asText("null") : "undefined";
        var account = me.path("kakao_account");
        return new Profile(Provider.KAKAO, id, text(account, "email", "kakao_" + id + "@oauth.local"),
                text(account.path("profile"), "nickname", "카카오 사용자"), text(account.path("profile"), "profile_image_url", null));
    }
    private Profile naver(AuthInputs.OAuth input) {
        var params = new java.util.LinkedHashMap<String, String>();
        params.put("grant_type", "authorization_code"); params.put("client_id", required("NAVER_CLIENT_ID"));
        params.put("client_secret", required("NAVER_CLIENT_SECRET")); params.put("code", input.code());
        if (input.state() != null && !input.state().isEmpty()) params.put("state", input.state());
        String query = params.entrySet().stream().map(e -> e.getKey() + "=" + java.net.URLEncoder.encode(e.getValue(), java.nio.charset.StandardCharsets.UTF_8))
                .collect(java.util.stream.Collectors.joining("&"));
        var token = read(http.get().uri(java.net.URI.create("https://nid.naver.com/oauth2.0/token?" + query)));
        String access = text(token, "access_token", null);
        if (access == null || access.isEmpty()) throw ApiProblem.unauthorized(text(token, "error_description", "네이버 인증에 실패했습니다."));
        var me = read(http.get().uri("https://openapi.naver.com/v1/nid/me").headers(h -> h.setBearerAuth(access))).path("response");
        String id = text(me, "id", null);
        if (id == null || id.isEmpty()) throw ApiProblem.unauthorized("네이버 사용자 정보를 가져오지 못했습니다.");
        return new Profile(Provider.NAVER, id, text(me, "email", "naver_" + id + "@oauth.local"),
                text(me, "nickname", "네이버 사용자"), text(me, "profile_image", null));
    }
    private JsonNode read(RestClient.RequestHeadersSpec<?> request) {
        try { return request.exchange((sent, response) -> {
            JsonNode body = json.readTree(response.getBody());
            if (body == null || body.isNull()) throw new IOException("Empty OAuth JSON response");
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw ApiProblem.unauthorized(text(body, "error_description", text(body, "msg",
                        text(body, "message", text(body, "error", "간편 로그인 인증에 실패했습니다.")))));
            }
            return body;
        }); } catch (org.springframework.web.client.RestClientException exception) {
            // 네이버 토큰 URL의 client_secret이 예외 메시지와 서버 로그에 남지 않게 한다.
            throw new IllegalStateException("OAuth provider request failed");
        }
    }
    private String required(String key) {
        String value = env.getProperty(key);
        if (value == null || value.isEmpty()) throw new IllegalStateException(key + " environment variable is required.");
        return value;
    }
    private static String text(JsonNode node, String field, String fallback) {
        return node.path(field).isNull() || node.path(field).isMissingNode() ? fallback : node.path(field).asText();
    }
}
