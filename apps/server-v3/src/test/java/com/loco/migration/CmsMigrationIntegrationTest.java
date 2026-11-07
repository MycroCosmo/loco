package com.loco.migration;

import static org.assertj.core.api.Assertions.assertThat;

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
              assertThat(context).hasNotFailed();
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

  @Test
  void upgradingV2AddsOnlyCmsTablesAndPreservesPublicHistory() throws Exception {
    baseline();
    host().run(context -> assertThat(context).hasNotFailed());
    String columns = publicColumns();
    String history =
        sql("SELECT json_agg(h ORDER BY installed_rank)::text FROM public.flyway_schema_history h");
    host()
        .withPropertyValues("spring.flyway.target=3")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var flyway = context.getBean(Flyway.class);
              assertThat(flyway.info().applied())
                  .extracting(info -> info.getVersion().getVersion())
                  .containsExactly("1", "2", "3");
              assertThat(publicColumns()).isEqualTo(columns);
              assertThat(
                      sql(
                          "SELECT json_agg(h ORDER BY installed_rank)::text FROM public.flyway_schema_history h WHERE version IN ('1','2')"))
                  .isEqualTo(history);
              assertThat(
                      sql(
                          "SELECT count(*) FROM information_schema.tables WHERE table_schema='cms'"))
                  .isEqualTo("5");
              assertThat(sql("SELECT count(*) FROM cms.admin_account")).isEqualTo("0");
              assertThat(sql("SELECT bootstrap_completed FROM cms.administration_state WHERE id=1"))
                  .isEqualTo("f");
              assertThat(flyway.migrate().migrationsExecuted).isZero();
            });
  }

  @Test
  void upgradingV3PreservesAdminIdentityAssignmentsAndExistingHistory() throws Exception {
    host()
        .withPropertyValues("spring.flyway.target=3")
        .run(context -> assertThat(context).hasNotFailed());
    sql(
        "INSERT INTO cms.admin_account VALUES ('existing-admin','existing','test-only-hash','Existing',true,false,7,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    sql(
        "INSERT INTO cms.admin_account_role SELECT 'existing-admin',id FROM cms.admin_role WHERE code='SYSTEM_ADMIN'");
    String account =
        sql("SELECT row_to_json(a)::text FROM cms.admin_account a WHERE id='existing-admin'");
    String assignments = sql("SELECT json_agg(ar)::text FROM cms.admin_account_role ar");
    String history =
        sql("SELECT json_agg(h ORDER BY installed_rank)::text FROM public.flyway_schema_history h");
    String columns = publicColumns();
    host()
        .withPropertyValues("spring.flyway.target=4")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(
                      sql(
                          "SELECT row_to_json(a)::text FROM cms.admin_account a WHERE id='existing-admin'"))
                  .isEqualTo(account);
              assertThat(sql("SELECT json_agg(ar)::text FROM cms.admin_account_role ar"))
                  .isEqualTo(assignments);
              assertThat(
                      sql(
                          "SELECT json_agg(h ORDER BY installed_rank)::text FROM public.flyway_schema_history h WHERE version IN ('1','2','3')"))
                  .isEqualTo(history);
              assertThat(publicColumns()).isEqualTo(columns);
              assertThat(sql("SELECT revision FROM cms.menu_tree_revision WHERE id=1"))
                  .isEqualTo("0");
              assertThat(sql("SELECT version FROM cms.admin_role WHERE code='SYSTEM_ADMIN'"))
                  .isEqualTo("0");
              assertThat(context.getBean(Flyway.class).migrate().migrationsExecuted).isZero();
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

  @Test
  void upgradingV4AddsFileStateWithoutChangingExistingSchemasOrHistory() throws Exception {
    host()
        .withPropertyValues("spring.flyway.target=4")
        .run(context -> assertThat(context).hasNotFailed());
    String columns = publicColumns();
    String history =
        sql("SELECT json_agg(h ORDER BY installed_rank)::text FROM public.flyway_schema_history h");
    String roles = sql("SELECT json_agg(r ORDER BY id)::text FROM cms.admin_role r");
    host()
        .withPropertyValues("spring.flyway.target=5")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(publicColumns()).isEqualTo(columns);
              assertThat(
                      sql(
                          "SELECT json_agg(h ORDER BY installed_rank)::text FROM public.flyway_schema_history h WHERE version<>'5'"))
                  .isEqualTo(history);
              assertThat(sql("SELECT json_agg(r ORDER BY id)::text FROM cms.admin_role r"))
                  .isEqualTo(roles);
              assertThat(sql("SELECT count(*) FROM cms.stored_file")).isEqualTo("0");
              assertThat(context.getBean(Flyway.class).migrate().migrationsExecuted).isZero();
            });
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
