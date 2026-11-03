package com.loco.compat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;

public final class SharedHarness implements BeforeAllCallback {
    private static Environment shared;
    private static RuntimeException startupFailure;
    public static synchronized Environment environment() {
        if (startupFailure != null) throw startupFailure;
        if (shared == null) {
            try { shared = new Environment(); }
            catch (RuntimeException exception) { startupFailure = exception; throw exception; }
        }
        return shared;
    }
    @Override public void beforeAll(ExtensionContext context) {
        context.getRoot().getStore(ExtensionContext.Namespace.create(SharedHarness.class))
                .getOrComputeIfAbsent(Environment.class, ignored -> environment());
    }

    public enum Side { V2, V3 }

    public static final class Environment implements ExtensionContext.Store.CloseableResource {
        public static final String USER_ID = "11111111-1111-4111-8111-111111111111";
        public static final String EMAIL = "compat@example.test";
        public final String secret = UUID.randomUUID().toString() + UUID.randomUUID();
        public final String password = UUID.randomUUID().toString();
        public final AuthRequestBudget budget = new AuthRequestBudget();
        public final ObjectMapper json = new ObjectMapper();
        private final long started = System.nanoTime();
        private final Network network = Network.newNetwork();
        private final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
                DockerImageName.parse("postgis/postgis:17-3.6").asCompatibleSubstituteFor("postgres"))
                .withPassword(UUID.randomUUID().toString()).withNetwork(network).withNetworkAliases("db")
                .withTmpFs(Map.of("/docker-entrypoint-initdb.d", "rw"));
        private final GenericContainer<?> mailpit = new GenericContainer<>("axllent/mailpit:v1.27.4")
                .withNetwork(network).withNetworkAliases("mailpit").withExposedPorts(8025, 1025)
                .withEnv("MP_ENABLE_CHAOS", "true")
                .withEnv("MP_SMTP_AUTH_ACCEPT_ANY", "1").withEnv("MP_SMTP_AUTH_ALLOW_INSECURE", "1")
                .waitingFor(Wait.forHttp("/api/v1/info").forPort(8025));
        private GenericContainer<?> v2;
        private String passwordHash;
        private int v2Builds;
        private int v2Starts;
        public final List<Map<String, Object>> knownDifferences = new ArrayList<>();
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        private Environment() {
            try {
                postgres.start();
                try (var connection = postgres.createConnection("")) {
                    connection.createStatement().execute("CREATE DATABASE loco_v2_test");
                    connection.createStatement().execute("CREATE DATABASE loco_v3_test");
                }
                for (Side side : Side.values()) {
                    Flyway.configure().dataSource(url(side), username(), databasePassword())
                            .locations("classpath:db/migration").target("1").load().migrate();
                }
                mailpit.start();
                v2 = new GenericContainer<>(v2Image()).withNetwork(network).withExposedPorts(3000)
                        .withEnv("DATABASE_URL", "postgresql://db:5432/loco_v2_test?schema=public")
                        .withEnv("DB_USERNAME", username()).withEnv("DB_PASSWORD", databasePassword())
                        .withEnv("JWT_SECRET", secret).withEnv("HOST", "0.0.0.0").withEnv("PORT", "3000")
                        .withEnv("SMTP_HOST", "mailpit").withEnv("SMTP_PORT", "1025")
                        .withEnv("SMTP_SECURE", "false").withEnv("SMTP_USER", "sender@example.test")
                        .withEnv("SMTP_PASS", UUID.randomUUID().toString())
                        .waitingFor(Wait.forLogMessage(".*LOCO server running.*\\n", 1))
                        .withStartupTimeout(Duration.ofMinutes(2));
                v2.start();
                v2Starts++;
                var hash = v2.execInContainer("node", "-e",
                        "require('bcrypt').hash(process.argv[1],10).then(h=>process.stdout.write(h))", password);
                if (hash.getExitCode() != 0 || !hash.getStdout().matches("\\$2[aby]\\$10\\$.{53}")) {
                    throw new IllegalStateException("테스트 bcrypt 해시 생성 실패");
                }
                passwordHash = hash.getStdout();
                reset("compat/seed.sql");
            } catch (Exception exception) {
                stopContainers();
                throw new IllegalStateException("공유 비교 환경 준비 실패", exception);
            }
        }

        private ImageFromDockerfile v2Image() {
            v2Builds++;
            Path root = Path.of(System.getProperty("prisma.schema")).getParent().getParent().getParent().getParent();
            var image = new ImageFromDockerfile()
                    .withFileFromPath("Dockerfile", root.resolve("infra/docker/server-v2.Dockerfile"))
                    .withFileFromPath("infra/docker/start-v2.cjs", root.resolve("infra/docker/start-v2.cjs"));
            for (String file : List.of("package.json", "package-lock.json", "tsconfig.json", "tsconfig.build.json",
                    "nest-cli.json", "prisma/schema.prisma", "src")) {
                image.withFileFromPath("apps/server/" + file, root.resolve("apps/server/" + file));
            }
            return image;
        }

        public String url(Side side) {
            return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432)
                    + (side == Side.V2 ? "/loco_v2_test" : "/loco_v3_test");
        }
        public String username() { return postgres.getUsername(); }
        public String databasePassword() { return postgres.getPassword(); }
        public String v2Url() { return "http://" + v2.getHost() + ":" + v2.getMappedPort(3000); }
        public String smtpHost() { return mailpit.getHost(); }
        public int smtpPort() { return mailpit.getMappedPort(1025); }
        public JsonNode nodeCheck(String script, Object input) throws Exception {
            var result = v2.execInContainer("node", "-e", script, json.writeValueAsString(input));
            if (result.getExitCode() != 0) throw new AssertionError("v2 라이브러리 교차 검증 실행 실패");
            return json.readTree(result.getStdout());
        }
        public Connection connect(Side side) throws Exception {
            return DriverManager.getConnection(url(side), username(), databasePassword());
        }

        public void reset(String seed) throws Exception {
            for (Side side : Side.values()) {
                try (var connection = connect(side)) {
                    connection.setAutoCommit(false);
                    var tables = DatabaseSnapshot.tables(connection);
                    connection.createStatement().execute("TRUNCATE TABLE " + String.join(",",
                            tables.stream().map(DatabaseSnapshot::quote).toList()) + " RESTART IDENTITY");
                    ScriptUtils.executeSqlScript(connection, new ClassPathResource(seed));
                    try (var statement = connection.prepareStatement("UPDATE users SET password=? WHERE id=?")) {
                        statement.setString(1, passwordHash);
                        statement.setString(2, USER_ID);
                        statement.executeUpdate();
                    }
                    connection.commit();
                }
            }
            clearMail();
        }

        public String token(String kind, String userId, String email) throws Exception {
            if (kind.equals("missing")) return null;
            if (kind.equals("malformed")) return "not-a-jwt";
            Instant now = Instant.now();
            var claims = new JWTClaimsSet.Builder().claim("email", email)
                    .issueTime(Date.from(now.minusSeconds(60)))
                    .expirationTime(Date.from(kind.equals("expired") ? now.minusSeconds(1) : now.plusSeconds(604740)));
            if (!kind.equals("no-sub")) claims.subject(userId);
            if (kind.equals("future-nbf")) claims.notBeforeTime(Date.from(now.plusSeconds(3600)));
            JWSAlgorithm algorithm = kind.startsWith("HS") ? JWSAlgorithm.parse(kind) : JWSAlgorithm.HS256;
            var jwt = new SignedJWT(new JWSHeader.Builder(algorithm).type(JOSEObjectType.JWT).build(), claims.build());
            String key = kind.equals("wrong-signature") ? UUID.randomUUID().toString().repeat(2) : secret;
            jwt.sign(new MACSigner(key.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        }

        public void clearMail() throws Exception { mailRequest("DELETE", "/api/v1/messages"); }
        public JsonNode mailRequest(String method, String path) throws Exception {
            return mailRequest(method, path, null);
        }
        public JsonNode mailRequest(String method, String path, Object body) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://" + mailpit.getHost() + ":"
                    + mailpit.getMappedPort(8025) + path)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new AssertionError("Mailpit HTTP " + response.statusCode());
            return method.equals("DELETE") || response.body().isBlank() ? json.nullNode() : json.readTree(response.body());
        }
        public JsonNode awaitMail(String recipient) throws Exception {
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            do {
                var messages = mailRequest("GET", "/api/v1/messages").path("messages");
                for (var message : messages) {
                    for (var to : message.path("To")) {
                        if (recipient.equals(to.path("Address").asText())) {
                            return mailRequest("GET", "/api/v1/message/" + message.path("ID").asText());
                        }
                    }
                }
                Thread.sleep(100);
            } while (System.nanoTime() < deadline);
            throw new AssertionError("Mailpit 수신 대기 시간 초과");
        }

        @Override public void close() throws Exception {
            try {
                Path report = Path.of(System.getProperty("compat.report"));
                Files.createDirectories(report.getParent());
                json.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), Map.of(
                        "environmentMillis", Duration.ofNanos(System.nanoTime() - started).toMillis(),
                        "authWaitMillis", budget.waitedMillis(), "v2Builds", v2Builds, "v2Starts", v2Starts,
                        "knownDifferences", knownDifferences,
                        "recommendContainerRestart", budget.waitedMillis() > 180000));
            } finally { stopContainers(); }
        }
        private void stopContainers() {
            try { if (v2 != null) v2.stop(); }
            finally { try { mailpit.stop(); } finally { try { postgres.stop(); } finally { network.close(); } } }
        }
    }
}
