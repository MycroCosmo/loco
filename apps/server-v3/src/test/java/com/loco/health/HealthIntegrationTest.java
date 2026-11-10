package com.loco.health;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "loco.uploads.directory=",
      "loco.notifications.service-account=",
      "loco.cleanup.enabled=false"
    })
class HealthIntegrationTest {
  private static final String JWT_SECRET = UUID.randomUUID().toString() + UUID.randomUUID();

  @Container
  static final PostgreSQLContainer<?> database =
      new PostgreSQLContainer<>(
              DockerImageName.parse("postgis/postgis:17-3.6").asCompatibleSubstituteFor("postgres"))
          .withPassword(UUID.randomUUID().toString())
          // 공간 확장 도입 전에는 이미지에 포함된 자동 설치 스크립트를 실행하지 않는다.
          .withTmpFs(Map.of("/docker-entrypoint-initdb.d", "rw"));

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("loco.jwt.secret", () -> JWT_SECRET);
    registry.add("spring.datasource.url", database::getJdbcUrl);
    registry.add("spring.datasource.username", database::getUsername);
    registry.add("spring.datasource.password", database::getPassword);
  }

  @LocalServerPort int port;

  @Autowired TestRestTemplate client;

  @Test
  void frontendScreenKeysMatchTheActualHostCatalog(
      @Autowired com.cms.core.permission.PermissionCatalog catalog) throws Exception {
    var source = java.nio.file.Files.readString(java.nio.file.Path.of("../admin/src/screens.ts"));
    var keys =
        java.util.regex.Pattern.compile("key: '([^']+)'")
            .matcher(source)
            .results()
            .map(match -> match.group(1))
            .toList();
    assertThat(keys).containsExactlyElementsOf(com.loco.cmsinfra.AdminScreens.KEYS);
    keys.forEach(key -> catalog.screen(key, key));
  }

  @Test
  void adminShellAndHashedAssetsArePublicButApisAreNot() {
    for (String page : com.loco.cmsinfra.AdminUiConfiguration.PAGES) {
      var shell = client.getForEntity(url(page), String.class);
      assertThat(shell.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(shell.getBody()).contains("<div id=\"app\">");
      assertThat(shell.getHeaders().getCacheControl()).contains("no-cache");
    }
    var html = client.getForObject(url("/admin/login"), String.class);
    var matcher =
        java.util.regex.Pattern.compile("src=\"(/admin/assets/[^\"]+\\.js)\"").matcher(html);
    assertThat(matcher.find()).isTrue();
    var asset = client.getForEntity(url(matcher.group(1)), String.class);
    assertThat(asset.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(asset.getHeaders().getCacheControl()).contains("immutable");
    assertThat(client.getForEntity(url("/admin/assets/missing.js"), String.class).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    for (String api : java.util.List.of("/admin/api/core/accounts", "/admin/api/core/missing")) {
      var response = client.getForEntity(url(api), String.class);
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
      assertThat(response.getBody()).doesNotContain("<html");
    }
  }

  @Test
  void validAppJwtCannotAuthenticateAdministrator(
      @Autowired com.loco.auth.TokenIssuer tokens, @Autowired com.loco.users.UserRepository users)
      throws Exception {
    var user =
        users.saveAndFlush(
            com.loco.users.User.local(UUID.randomUUID() + "@example.test", "not-used", "Boundary"));
    var token = tokens.pair(user, "unused").accessToken();
    var headers = new org.springframework.http.HttpHeaders();
    headers.setBearerAuth(token);
    assertThat(
            client
                .exchange(
                    url("/api/users/me"),
                    org.springframework.http.HttpMethod.GET,
                    new org.springframework.http.HttpEntity<>(headers),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(
            client
                .exchange(
                    url("/admin/api/core/auth/me"),
                    org.springframework.http.HttpMethod.GET,
                    new org.springframework.http.HttpEntity<>(headers),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void administratorSessionCannotAuthenticateAppAndUsesSecureCookieByDefault(
      @Autowired com.cms.core.account.AdminService admins) throws Exception {
    String password = UUID.randomUUID().toString();
    admins.bootstrap("boundary", "Boundary", password);
    var http = java.net.http.HttpClient.newHttpClient();
    var csrf =
        http.send(
            java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create(url("/admin/api/core/auth/csrf")))
                .GET()
                .build(),
            java.net.http.HttpResponse.BodyHandlers.ofString());
    String csrfCookie =
        csrf.headers().allValues("set-cookie").stream()
            .filter(c -> c.startsWith("XSRF-TOKEN="))
            .findFirst()
            .orElseThrow()
            .split(";", 2)[0];
    String sessionCookie =
        csrf.headers().allValues("set-cookie").stream()
            .filter(c -> c.startsWith("CMSSESSION="))
            .findFirst()
            .orElseThrow();
    assertThat(sessionCookie).contains("Secure", "HttpOnly", "SameSite=Strict", "Path=/admin");
    var login =
        http.send(
            java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create(url("/admin/api/core/auth/login")))
                .header("Cookie", csrfCookie + "; " + sessionCookie.split(";", 2)[0])
                .header("X-XSRF-TOKEN", csrfCookie.substring("XSRF-TOKEN=".length()))
                .header("Content-Type", "application/json")
                .POST(
                    java.net.http.HttpRequest.BodyPublishers.ofString(
                        "{\"loginId\":\"boundary\",\"password\":\"" + password + "\"}"))
                .build(),
            java.net.http.HttpResponse.BodyHandlers.ofString());
    assertThat(login.statusCode()).isEqualTo(200);
    String authenticated =
        login.headers().allValues("set-cookie").stream()
            .filter(c -> c.startsWith("CMSSESSION="))
            .findFirst()
            .orElseThrow()
            .split(";", 2)[0];
    assertThat(authenticated).isNotEqualTo(sessionCookie.split(";", 2)[0]);
    for (var path : Map.of("/admin/api/core/auth/me", 200, "/api/users/me", 401).entrySet()) {
      var response =
          http.send(
              java.net.http.HttpRequest.newBuilder(java.net.URI.create(url(path.getKey())))
                  .header("Cookie", authenticated)
                  .GET()
                  .build(),
              java.net.http.HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).as(path.getKey()).isEqualTo(path.getValue());
    }
  }

  @Test
  void adminDoesNotInheritLocoParserHelmetOrCors() {
    var headers = new org.springframework.http.HttpHeaders();
    headers.setOrigin("http://localhost:3000");
    var admin =
        client.exchange(
            url("/admin/api/core/auth/csrf"),
            org.springframework.http.HttpMethod.OPTIONS,
            new org.springframework.http.HttpEntity<>(headers),
            String.class);
    assertThat(admin.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(admin.getHeaders().getAccessControlAllowOrigin()).isNull();
    assertThat(admin.getHeaders().getFirst("X-Download-Options")).isNull();
    var similarlyNamed =
        client.exchange(
            url("/administrator"),
            org.springframework.http.HttpMethod.OPTIONS,
            new org.springframework.http.HttpEntity<>(headers),
            String.class);
    assertThat(similarlyNamed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(similarlyNamed.getHeaders().getAccessControlAllowOrigin())
        .isEqualTo("http://localhost:3000");
    headers.setContentType(MediaType.APPLICATION_JSON);
    var malformed =
        client.exchange(
            url("/admin/api/core/auth/login"),
            org.springframework.http.HttpMethod.POST,
            new org.springframework.http.HttpEntity<>("{broken", headers),
            String.class);
    assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(malformed.getHeaders().getFirst("X-Download-Options")).isNull();
  }

  @Test
  void healthIsPublicAndReturnsExactJson() throws Exception {
    ResponseEntity<String> response = client.getForEntity(url("/api/health"), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType())
        .isNotNull()
        .matches(type -> type.isCompatibleWith(MediaType.APPLICATION_JSON));
    JSONAssert.assertEquals("{\"status\":\"ok\"}", response.getBody(), true);
  }

  @Test
  void healthWithoutApiPrefixDoesNotExist() {
    ResponseEntity<String> response = client.getForEntity(url("/health"), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void actuatorIsNotPublic() {
    ResponseEntity<String> response =
        client.getForEntity(url("/api/actuator/health"), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void emptyUploadDirectoryDoesNotExposeWorkingDirectory() {
    assertThat(java.nio.file.Files.exists(java.nio.file.Path.of("README.md"))).isTrue();
    assertThat(client.getForEntity(url("/uploads/README.md"), String.class).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void compatibilityTestEndpointsAreNotPartOfTheApplication() {
    assertThat(
            client
                .getForEntity(url("/api/__test/harness/restricted"), String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void cmsCoreLoadsWithoutChangingThePublicBaseline(
      @Autowired com.cms.core.CmsCoreConfiguration core, @Autowired EntityManager entityManager) {
    assertThat(core).isNotNull();
    assertThat(
            entityManager
                .createNativeQuery(
                    "SELECT count(*) FROM public.flyway_schema_history WHERE version IN ('1','2') AND success",
                    Long.class)
                .getSingleResult())
        .isEqualTo(2L);
    assertThat(
            entityManager
                .createNativeQuery(
                    "SELECT count(*) FROM pg_namespace WHERE nspname='cms'", Long.class)
                .getSingleResult())
        .isEqualTo(1L);
  }

  @Test
  void databaseHasOnlyTheApprovedSpatialExtension(@Autowired EntityManager entityManager) {
    assertThat(
            entityManager
                .createNativeQuery(
                    "SELECT extname::text FROM pg_extension WHERE extname <> 'plpgsql'",
                    String.class)
                .getSingleResult())
        .isEqualTo("postgis");
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
