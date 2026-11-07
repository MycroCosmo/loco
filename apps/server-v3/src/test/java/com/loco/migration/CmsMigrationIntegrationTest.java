package com.loco.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.cms.core.CmsCoreConfiguration;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class CmsMigrationIntegrationTest {
  @Container
  final PostgreSQLContainer<?> database =
      new PostgreSQLContainer<>(
              DockerImageName.parse("postgis/postgis:17-3.6").asCompatibleSubstituteFor("postgres"))
          .withPassword(UUID.randomUUID().toString())
          .withTmpFs(Map.of("/docker-entrypoint-initdb.d", "rw"));

  @Test
  void emptyDatabaseUsesProductionLocationsAndOnePublicHistory() {
    host()
        .run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(CmsCoreConfiguration.class);
              var flyway = context.getBean(Flyway.class);
              assertThat(flyway.info().applied())
                  .extracting(info -> info.getVersion().getVersion())
                  .containsExactly("1", "2");
              assertThat(flyway.migrate().migrationsExecuted).isZero();
              assertThat(sql("SELECT count(*) FROM pg_namespace WHERE nspname='cms'"))
                  .isEqualTo("1");
              assertThat(
                      sql(
                          "SELECT count(*) FROM information_schema.tables WHERE table_schema='cms'"))
                  .isEqualTo("0");
              assertThat(
                      sql(
                          "SELECT count(*) FROM information_schema.tables WHERE table_name='flyway_schema_history' AND table_schema='public'"))
                  .isEqualTo("1");
              assertThat(sql("SELECT count(*) FROM pg_extension WHERE extname='postgis'"))
                  .isEqualTo("0");
            });
  }

  @Test
  void upgradingV1PreservesPublicSchemaDataAndBaselineChecksum() throws Exception {
    baseline();
    sql(
        "INSERT INTO public.users (id,email,nickname,\"updatedAt\") VALUES "
            + "('11111111-1111-4111-8111-111111111111','cms-upgrade@example.test','existing',CURRENT_TIMESTAMP)");
    String data = sql("SELECT row_to_json(users)::text FROM public.users");
    String schema = publicColumns();
    String history =
        sql("SELECT row_to_json(h)::text FROM public.flyway_schema_history h WHERE version='1'");
    host()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var flyway = context.getBean(Flyway.class);
              assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("2");
              assertThat(sql("SELECT row_to_json(users)::text FROM public.users")).isEqualTo(data);
              assertThat(publicColumns()).isEqualTo(schema);
              assertThat(
                      sql(
                          "SELECT row_to_json(h)::text FROM public.flyway_schema_history h WHERE version='1'"))
                  .isEqualTo(history);
              flyway.validate();
            });
  }

  @Test
  void unmanagedCmsSchemaIsNotSilentlyBaselined() throws Exception {
    baseline();
    sql("CREATE SCHEMA cms");
    sql("CREATE TABLE cms.unmanaged (id integer PRIMARY KEY)");
    sql("INSERT INTO cms.unmanaged VALUES (7)");
    host()
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure()).hasStackTraceContaining("already exists");
              assertThat(sql("SELECT id FROM cms.unmanaged")).isEqualTo("7");
              assertThat(sql("SELECT count(*) FROM public.flyway_schema_history WHERE version='2'"))
                  .isEqualTo("0");
            });
  }

  private void baseline() {
    Flyway.configure()
        .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
        .locations("classpath:db/migration")
        .target("1")
        .load()
        .migrate();
  }

  private ApplicationContextRunner host() {
    return new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class))
        .withUserConfiguration(CmsCoreConfiguration.class)
        .withInitializer(
            context -> {
              try {
                context
                    .getEnvironment()
                    .getPropertySources()
                    .addLast(
                        new YamlPropertySourceLoader()
                            .load("production", new ClassPathResource("application.yml"))
                            .getFirst());
              } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException(exception);
              }
            })
        .withPropertyValues(
            "spring.flyway.target=2",
            "DB_URL=" + database.getJdbcUrl(),
            "DB_USERNAME=" + database.getUsername(),
            "DB_PASSWORD=" + database.getPassword());
  }

  private String publicColumns() throws Exception {
    return sql(
        "SELECT json_agg(c ORDER BY table_name,ordinal_position)::text FROM "
            + "(SELECT table_name,column_name,ordinal_position,data_type,is_nullable,column_default "
            + "FROM information_schema.columns WHERE table_schema='public') c");
  }

  private String sql(String statement) throws Exception {
    try (var connection = database.createConnection("");
        var query = connection.createStatement()) {
      if (!query.execute(statement)) return "";
      try (var result = query.getResultSet()) {
        assertThat(result.next()).isTrue();
        return result.getString(1);
      }
    }
  }
}
