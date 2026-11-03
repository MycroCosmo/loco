package com.loco.migration;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@org.junit.jupiter.api.Tag("v2-comparison")
class FlywayBaselineIntegrationTest {

    @Container
    final PostgreSQLContainer<?> database = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.6").asCompatibleSubstituteFor("postgres"))
            .withPassword(UUID.randomUUID().toString())
            .withTmpFs(Map.of("/docker-entrypoint-initdb.d", "rw"));

    @TempDir
    Path directory;

    Path schema;

    @BeforeEach
    void copyPrismaSchema() throws Exception {
        // Prisma가 v2의 .env를 읽지 않도록 스키마만 격리된 디렉터리에 복사한다.
        schema = Files.copy(Path.of(System.getProperty("prisma.schema")), directory.resolve("schema.prisma"));
    }

    @Test
    void emptyDatabaseMigratesOnceAndMatchesPrismaSchema() throws Exception {
        Path regenerated = directory.resolve("generated.sql");
        assertThat(prisma("migrate", "diff", "--from-empty", "--to-schema-datamodel", schema.toString(),
                "--script", "--output", regenerated.toString()).exitCode()).isZero();
        try (var migration = getClass().getResourceAsStream("/db/migration/V1__baseline.sql")) {
            assertThat(migration).isNotNull();
            assertThat(normalizeNewlines(Files.readString(regenerated)))
                    .isEqualTo(normalizeNewlines(new String(migration.readAllBytes(), StandardCharsets.UTF_8)));
        }

        Flyway flyway = flyway(false);
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        var applied = flyway.info().applied();
        assertThat(applied).hasSize(1);
        assertThat(applied[0].getVersion().getVersion()).isEqualTo("1");
        assertThat(applied[0].getType().name()).isEqualTo("SQL");
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertSchemaMatchesPrisma(true);
    }

    @Test
    void existingDatabaseRequiresOptInAndPreservesData() throws Exception {
        var push = prisma("db", "push", "--schema", schema.toString(), "--skip-generate");
        assertThat(push.exitCode()).as(push.output()).isZero();
        assertSchemaMatchesPrisma(false);

        String userId = UUID.randomUUID().toString();
        sql("INSERT INTO users (id, email, nickname, \"updatedAt\") VALUES ('" + userId
                + "', 'existing@example.test', 'existing user', CURRENT_TIMESTAMP)");
        String originalUser = sql("SELECT row_to_json(users)::text FROM users");

        assertThatThrownBy(() -> flyway(false).migrate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("non-empty")
                .hasMessageContaining("schema history");
        assertThat(sql("SELECT row_to_json(users)::text FROM users")).isEqualTo(originalUser);

        Flyway adopted = flyway(true);
        assertThat(adopted.migrate().migrationsExecuted).isZero();
        var applied = adopted.info().applied();
        assertThat(applied).hasSize(1);
        assertThat(applied[0].getVersion().getVersion()).isEqualTo("1");
        assertThat(applied[0].getType().name()).isEqualTo("BASELINE");
        assertThat(sql("SELECT row_to_json(users)::text FROM users")).isEqualTo(originalUser);
        assertSchemaMatchesPrisma(true);
        assertThat(flyway(false).migrate().migrationsExecuted).isZero();
    }

    private Flyway flyway(boolean baselineOnMigrate) {
        return Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .locations("classpath:db/migration")
                .target("1")
                .baselineVersion("1")
                .baselineOnMigrate(baselineOnMigrate)
                .load();
    }

    private void assertSchemaMatchesPrisma(boolean hasFlywayHistory) throws Exception {
        var diff = prisma("migrate", "diff", "--from-schema-datasource", schema.toString(),
                "--to-schema-datamodel", schema.toString(), "--script", "--exit-code");
        assertThat(diff.exitCode()).as(diff.output()).isEqualTo(hasFlywayHistory ? 2 : 0);
        String statements = diff.output().lines().map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("--"))
                .collect(Collectors.joining("\n"));
        // 관리 테이블 하나만 차이로 허용하며, 차이 SQL은 실행하지 않는다.
        assertThat(statements).isEqualTo(hasFlywayHistory ? "DROP TABLE \"flyway_schema_history\";" : "");
    }

    private CommandResult prisma(String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("node", System.getProperty("prisma.cli")));
        command.addAll(List.of(arguments));
        Path output = Files.createTempFile(directory, "prisma-", ".log");
        var builder = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().put("DATABASE_URL", new URI("postgresql",
                database.getUsername() + ":" + database.getPassword(), database.getHost(),
                database.getMappedPort(5432), "/" + database.getDatabaseName(), "schema=public", null).toString());
        builder.environment().put("CHECKPOINT_DISABLE", "1");
        Process process = builder.start();
        boolean completed = process.waitFor(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroyForcibly();
        }
        assertThat(completed).as("Prisma command completed within two minutes").isTrue();
        return new CommandResult(process.exitValue(), Files.readString(output));
    }

    private String sql(String statement) throws Exception {
        var result = database.execInContainer("psql", "-U", database.getUsername(), "-d",
                database.getDatabaseName(), "-v", "ON_ERROR_STOP=1", "-Atc", statement);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
        return result.getStdout().trim();
    }

    private static String normalizeNewlines(String value) {
        return value.replace("\r\n", "\n");
    }

    private record CommandResult(int exitCode, String output) {
    }
}
