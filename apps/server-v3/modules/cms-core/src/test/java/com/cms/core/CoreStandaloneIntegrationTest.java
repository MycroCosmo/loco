package com.cms.core;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManagerFactory;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(
    classes = CoreStandaloneIntegrationTest.Host.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.flyway.default-schema=public",
      "spring.flyway.locations=classpath:db/cms",
      "spring.flyway.target=2",
      "spring.flyway.baseline-on-migrate=false",
      "spring.jpa.hibernate.ddl-auto=none"
    })
class CoreStandaloneIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> database =
      new PostgreSQLContainer<>(
              DockerImageName.parse("postgis/postgis:17-3.6").asCompatibleSubstituteFor("postgres"))
          .withPassword(UUID.randomUUID().toString())
          .withTmpFs(Map.of("/docker-entrypoint-initdb.d", "rw"));

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", database::getJdbcUrl);
    properties.add("spring.datasource.username", database::getUsername);
    properties.add("spring.datasource.password", database::getPassword);
  }

  @Autowired CmsCoreConfiguration core;
  @Autowired Flyway flyway;
  @Autowired EntityManagerFactory entities;
  @Autowired DataSource dataSource;

  @Test
  void bootsAndMigratesWithoutLocoOrItsConfiguration() throws Exception {
    assertThat(core).isNotNull();
    assertThat(entities.isOpen()).isTrue();
    assertThat(flyway.info().applied()).hasSize(1);
    assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("2");
    assertThat(flyway.migrate().migrationsExecuted).isZero();
    try (var connection = dataSource.getConnection();
        var sql = connection.createStatement();
        var result =
            sql.executeQuery(
                "SELECT EXISTS(SELECT FROM pg_namespace WHERE nspname='cms'), "
                    + "to_regclass('public.users') IS NULL, "
                    + "to_regclass('public.flyway_schema_history') IS NOT NULL, "
                    + "NOT EXISTS(SELECT FROM pg_extension WHERE extname='postgis')")) {
      assertThat(result.next()).isTrue();
      for (int column = 1; column <= 4; column++) assertThat(result.getBoolean(column)).isTrue();
    }
  }

  @SpringBootConfiguration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @Import(CmsCoreConfiguration.class)
  static class Host {}
}
