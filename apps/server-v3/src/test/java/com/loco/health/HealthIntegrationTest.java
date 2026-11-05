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
    properties = "loco.uploads.directory=")
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
  void databaseHasNoSpatialExtensions(@Autowired EntityManager entityManager) {
    assertThat(
            entityManager
                .createNativeQuery(
                    "SELECT count(*) FROM pg_extension WHERE extname <> 'plpgsql'", Long.class)
                .getSingleResult())
        .isEqualTo(0L);
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
