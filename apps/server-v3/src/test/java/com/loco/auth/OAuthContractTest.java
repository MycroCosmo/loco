package com.loco.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.loco.compat.DatabaseSnapshot;
import com.loco.compat.JsonComparison;
import com.loco.compat.ScenarioValues;
import com.loco.compat.SharedHarness;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@Import(OAuthContractTest.MockConfig.class)
@TestPropertySource(
    properties = {
      "KAKAO_REST_API_KEY=test-client",
      "NAVER_CLIENT_ID=test-client",
      "NAVER_CLIENT_SECRET=test-secret"
    })
@DisplayName("v3 계약 테스트: OAuth 외부 응답 mock (v2 HTTP 비교 아님)")
class OAuthContractTest extends AuthenticationSupport {
  @Override
  protected boolean usesV2Http() {
    return false;
  }

  static final class ProviderMock {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class MockConfig {
    @Bean
    ProviderMock providerMock() {
      return new ProviderMock();
    }

    @Bean
    @Primary
    RestClient.Builder oauthBuilder(ProviderMock mock) {
      return mock.builder;
    }
  }

  @Autowired ProviderMock mock;
  @Autowired org.springframework.core.env.ConfigurableEnvironment configuration;

  @BeforeEach
  void seed() throws Exception {
    environment().reset("compat/seed.sql");
    mock.server.reset();
  }

  @AfterEach
  void verifyProvider() {
    mock.server.verify();
  }

  @ParameterizedTest
  @ValueSource(strings = {"kakao", "naver"})
  void newProviderAccountCreatesUserAndValidTokens(String provider) throws Exception {
    profile(provider, "new@example.test", "새 회원", "https://example.test/image");
    var response = call(provider, input());
    assertThat(response.getStatusCode().value()).isEqualTo(201);
    var captures = new ScenarioValues();
    captures.capture(
        response.getBody(), Map.of("oauthAccess", "/accessToken", "oauthRefresh", "/refreshToken"));
    assertPair(SharedHarness.Side.V3, captures, "oauth", "new@example.test");
    try (var db = environment().connect(SharedHarness.Side.V3);
        var row =
            db.createStatement()
                .executeQuery(
                    "SELECT provider,\"providerId\",password,nickname,\"profileImageUrl\" FROM users WHERE email='new@example.test'")) {
      assertThat(row.next()).isTrue();
      assertThat(row.getString(1)).isEqualTo(provider.toUpperCase());
      assertThat(row.getString(2)).isEqualTo("123");
      assertThat(row.getString(3)).isNull();
      assertThat(row.getString(4)).isEqualTo("새 회원");
      assertThat(row.getString(5)).isEqualTo("https://example.test/image");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"kakao", "naver"})
  void missingOptionalProfileUsesV2Fallbacks(String provider) throws Exception {
    profile(provider, null, null, null);
    assertThat(call(provider, input()).getStatusCode().value()).isEqualTo(201);
    try (var db = environment().connect(SharedHarness.Side.V3);
        var row =
            db.createStatement()
                .executeQuery("SELECT email,nickname FROM users WHERE \"providerId\"='123'")) {
      assertThat(row.next()).isTrue();
      assertThat(row.getString(1)).isEqualTo(provider + "_123@oauth.local");
      assertThat(row.getString(2)).isEqualTo(provider.equals("kakao") ? "카카오 사용자" : "네이버 사용자");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"LOCAL", "KAKAO", "NAVER", "deleted"})
  void existingEmailNeverAutomaticallyLinksAnAccount(String state) throws Exception {
    try (var db = environment().connect(SharedHarness.Side.V3)) {
      db.createStatement()
          .executeUpdate(
              state.equals("deleted")
                  ? "UPDATE users SET \"deletedAt\"=CURRENT_TIMESTAMP"
                  : "UPDATE users SET provider='"
                      + state
                      + "', \"providerId\"='other-provider-id'");
      var rules = DatabaseSnapshot.Rules.defaults();
      var before = DatabaseSnapshot.capture(db, rules);
      profile("kakao", SharedHarness.Environment.EMAIL, "새 프로필", null);
      var response = call("kakao", input());
      assertThat(response.getStatusCode().value()).isEqualTo(409);
      JsonComparison.compare(
          response.getBody(),
          mapper.valueToTree(
              Map.of(
                  "statusCode",
                  409,
                  "error",
                  "Conflict",
                  "message",
                  "이미 가입된 이메일입니다. 기존 계정으로 로그인한 뒤 계정을 연결해주세요.")),
          Map.of());
      before.assertUnchanged(DatabaseSnapshot.capture(db, rules));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"active", "deleted"})
  void knownProviderUpdatesProfileBeforeDeletedCheckAndPreservesMissingImage(String state)
      throws Exception {
    try (var db = environment().connect(SharedHarness.Side.V3)) {
      db.createStatement()
          .executeUpdate(
              "UPDATE users SET provider='KAKAO',\"providerId\"='123',\"profileImageUrl\"='old-image',\"updatedAt\"='2000-01-01'"
                  + (state.equals("deleted") ? ",\"deletedAt\"=CURRENT_TIMESTAMP" : ""));
      profile("kakao", "changed@example.test", "바뀐 이름", null);
      var response = call("kakao", input());
      assertThat(response.getStatusCode().value()).isEqualTo(state.equals("deleted") ? 401 : 201);
      if (state.equals("deleted"))
        assertThat(response.getBody().path("message").asText()).isEqualTo("탈퇴한 계정입니다.");
      try (var row =
          db.createStatement()
              .executeQuery(
                  "SELECT id,email,nickname,\"profileImageUrl\",\"updatedAt\">'2000-01-01',(SELECT count(*) FROM refresh_tokens) FROM users")) {
        row.next();
        assertThat(row.getString(1)).isEqualTo(SharedHarness.Environment.USER_ID);
        assertThat(row.getString(2)).isEqualTo("changed@example.test");
        assertThat(row.getString(3)).isEqualTo("바뀐 이름");
        assertThat(row.getString(4)).isEqualTo("old-image");
        assertThat(row.getBoolean(5)).isTrue();
        assertThat(row.getInt(6)).isEqualTo(state.equals("deleted") ? 0 : 1);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"http-error", "no-access", "invalid-json", "network"})
  void providerFailuresDoNotCreateAccounts(String kind) throws Exception {
    var expectation = mock.server.expect(requestTo("https://kauth.kakao.com/oauth/token"));
    switch (kind) {
      case "http-error" ->
          expectation.andRespond(
              withStatus(HttpStatus.UNAUTHORIZED)
                  .contentType(MediaType.APPLICATION_JSON)
                  .body("{\"error_description\":\"provider refused\"}"));
      case "no-access" -> expectation.andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
      case "invalid-json" -> expectation.andRespond(withSuccess("{", MediaType.APPLICATION_JSON));
      case "network" ->
          expectation.andRespond(withException(new java.io.IOException("test network failure")));
    }
    try (var db = environment().connect(SharedHarness.Side.V3)) {
      var rules = DatabaseSnapshot.Rules.defaults();
      var before = DatabaseSnapshot.capture(db, rules);
      var response = call("kakao", input());
      int status = kind.equals("http-error") || kind.equals("no-access") ? 401 : 500;
      assertThat(response.getStatusCode().value()).isEqualTo(status);
      assertThat(response.getBody().size()).isEqualTo(status == 401 ? 3 : 2);
      assertThat(response.getBody().path("message").asText())
          .isEqualTo(
              switch (kind) {
                case "http-error" -> "provider refused";
                case "no-access" -> "카카오 인증에 실패했습니다.";
                default -> "Internal server error";
              });
      before.assertUnchanged(DatabaseSnapshot.capture(db, rules));
    }
  }

  @Test
  void naverRequiresProviderId() {
    token("naver");
    mock.server
        .expect(requestTo("https://openapi.naver.com/v1/nid/me"))
        .andRespond(withSuccess("{\"response\":{}}", MediaType.APPLICATION_JSON));
    var response = call("naver", input());
    assertThat(response.getStatusCode().value()).isEqualTo(401);
    assertThat(response.getBody().path("message").asText()).isEqualTo("네이버 사용자 정보를 가져오지 못했습니다.");
  }

  @Test
  void unsupportedProviderAndInvalidDtoDoNotContactProvider() {
    var unsupported = call("google", input());
    assertThat(unsupported.getStatusCode().value()).isEqualTo(400);
    assertThat(unsupported.getBody().path("message").asText()).isEqualTo("지원하지 않는 간편 로그인입니다.");
    var invalid = call("kakao", Map.of("code", 3, "redirectUri", "bad uri", "extra", true));
    assertThat(invalid.getStatusCode().value()).isEqualTo(400);
    assertThat(invalid.getBody().path("message").toString())
        .isEqualTo(
            "[\"property extra should not exist\",\"code must be a string\",\"redirectUri must be a URL address\"]");
  }

  @Test
  void missingCredentialsReturnGeneric500WithoutExternalRequest() {
    configuration
        .getPropertySources()
        .addFirst(
            new org.springframework.core.env.MapPropertySource(
                "missing-oauth-key", Map.of("KAKAO_REST_API_KEY", "")));
    try {
      var response = call("kakao", input());
      assertThat(response.getStatusCode().value()).isEqualTo(500);
      assertThat(response.getBody())
          .isEqualTo(
              mapper.valueToTree(Map.of("statusCode", 500, "message", "Internal server error")));
    } finally {
      configuration.getPropertySources().remove("missing-oauth-key");
    }
  }

  @Test
  void existingProviderEmailCollisionRollsBackProfileUpdate() throws Exception {
    try (var db = environment().connect(SharedHarness.Side.V3)) {
      db.createStatement()
          .executeUpdate(
              "INSERT INTO users(id,email,nickname,provider,\"providerId\",\"updatedAt\") VALUES ('22222222-2222-4222-8222-222222222222','oauth@example.test','기존','KAKAO','123',CURRENT_TIMESTAMP)");
      var rules = DatabaseSnapshot.Rules.defaults();
      var before = DatabaseSnapshot.capture(db, rules);
      profile("kakao", SharedHarness.Environment.EMAIL, "변경 시도", null);
      var response = call("kakao", input());
      assertThat(response.getStatusCode().value()).isEqualTo(500);
      assertThat(response.getBody())
          .isEqualTo(
              mapper.valueToTree(Map.of("statusCode", 500, "message", "Internal server error")));
      before.assertUnchanged(DatabaseSnapshot.capture(db, rules));
    }
  }

  private Map<String, String> input() {
    return Map.of(
        "code", "code +&", "redirectUri", "http://localhost/callback", "state", "state +&");
  }

  private org.springframework.http.ResponseEntity<com.fasterxml.jackson.databind.JsonNode> call(
      String provider, Object body) {
    return request(v3Url(), "/api/auth/oauth/" + provider, HttpMethod.POST, null, body);
  }

  private void token(String provider) {
    if (provider.equals("kakao")) {
      mock.server
          .expect(requestTo("https://kauth.kakao.com/oauth/token"))
          .andExpect(method(HttpMethod.POST))
          .andExpect(
              content()
                  .formData(
                      new org.springframework.util.LinkedMultiValueMap<>(
                          Map.of(
                              "grant_type",
                              java.util.List.of("authorization_code"),
                              "client_id",
                              java.util.List.of("test-client"),
                              "redirect_uri",
                              java.util.List.of("http://localhost/callback"),
                              "code",
                              java.util.List.of("code +&")))))
          .andRespond(
              withSuccess("{\"access_token\":\"mock-access\"}", MediaType.APPLICATION_JSON));
    } else {
      mock.server
          .expect(requestTo(startsWith("https://nid.naver.com/oauth2.0/token?")))
          .andExpect(method(HttpMethod.GET))
          .andExpect(queryParam("client_id", "test-client"))
          .andExpect(queryParam("client_secret", "test-secret"))
          .andExpect(queryParam("code", "code+%2B%26"))
          .andExpect(queryParam("state", "state+%2B%26"))
          .andRespond(
              withSuccess("{\"access_token\":\"mock-access\"}", MediaType.APPLICATION_JSON));
    }
  }

  private void profile(String provider, String email, String nickname, String image)
      throws Exception {
    token(provider);
    var details = mapper.createObjectNode();
    if (email != null) details.put("email", email);
    com.fasterxml.jackson.databind.node.ObjectNode response;
    if (provider.equals("kakao")) {
      var profile = details.putObject("profile");
      if (nickname != null) profile.put("nickname", nickname);
      if (image != null) profile.put("profile_image_url", image);
      response = mapper.createObjectNode().put("id", 123);
      response.set("kakao_account", details);
    } else {
      details.put("id", "123");
      if (nickname != null) details.put("nickname", nickname);
      if (image != null) details.put("profile_image", image);
      response = mapper.createObjectNode();
      response.set("response", details);
    }
    mock.server
        .expect(
            requestTo(
                provider.equals("kakao")
                    ? "https://kapi.kakao.com/v2/user/me"
                    : "https://openapi.naver.com/v1/nid/me"))
        .andExpect(header("Authorization", "Bearer mock-access"))
        .andRespond(withSuccess(response.toString(), MediaType.APPLICATION_JSON));
  }
}
