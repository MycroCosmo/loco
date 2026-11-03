package com.loco.security;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loco.web.ApiController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ApiCompatibilityIntegrationTest.ProbeController.class)
class ApiCompatibilityIntegrationTest {
    static final String SECRET = UUID.randomUUID().toString() + UUID.randomUUID();
    static final Network NETWORK = Network.newNetwork();
    static final Path ROOT = Path.of(System.getProperty("prisma.schema")).getParent().getParent().getParent().getParent();

    static final PostgreSQLContainer<?> database = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.6").asCompatibleSubstituteFor("postgres"))
            .withPassword(UUID.randomUUID().toString()).withNetwork(NETWORK).withNetworkAliases("db")
            .withTmpFs(Map.of("/docker-entrypoint-initdb.d", "rw"));

    static final GenericContainer<?> v2 = new GenericContainer<>(v2Image())
            .withNetwork(NETWORK).withExposedPorts(3000)
            .withEnv("DATABASE_URL", "postgresql://db:5432/test?schema=public")
            .withEnv("DB_USERNAME", database.getUsername()).withEnv("DB_PASSWORD", database.getPassword())
            .withEnv("JWT_SECRET", SECRET).withEnv("HOST", "0.0.0.0").withEnv("PORT", "3000")
            .waitingFor(Wait.forHttp("/api/users/me").forStatusCode(401))
            .withStartupTimeout(Duration.ofMinutes(2));

    static ImageFromDockerfile v2Image() {
        var image = new ImageFromDockerfile()
                .withFileFromPath("Dockerfile", ROOT.resolve("infra/docker/server-v2.Dockerfile"))
                .withFileFromPath("infra/docker/start-v2.cjs", ROOT.resolve("infra/docker/start-v2.cjs"));
        // .env, node_modules, uploads를 Docker 빌드 컨텍스트에 포함하지 않는다.
        for (String file : List.of("package.json", "package-lock.json", "tsconfig.json", "tsconfig.build.json",
                "nest-cli.json", "prisma/schema.prisma", "src")) {
            image.withFileFromPath("apps/server/" + file, ROOT.resolve("apps/server/" + file));
        }
        return image;
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        database.start();
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
        registry.add("loco.jwt.secret", () -> SECRET);
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate client;
    @Autowired ObjectMapper mapper;
    String validToken;
    String userId;
    String email;

    @BeforeAll
    void registerWithRealV2() throws Exception {
        // Spring의 Flyway가 임시 DB를 준비한 뒤 실제 v2를 시작한다.
        v2.start();
        email = "compat-" + UUID.randomUUID() + "@example.test";
        String password = UUID.randomUUID().toString();
        var registration = request(v2Url(), "/api/auth/register", HttpMethod.POST, null,
                Map.of("email", email, "password", password, "nickname", "compat"));
        assertThat(registration.getStatusCode().value()).isEqualTo(201);
        var login = request(v2Url(), "/api/auth/login", HttpMethod.POST, null,
                Map.of("email", email, "password", password));
        assertThat(login.getStatusCode().value()).isEqualTo(201);
        validToken = login.getBody().path("accessToken").asText();
        assertThat(validToken).isNotBlank();
        var me = request(v2Url(), "/api/users/me", HttpMethod.GET, validToken, null);
        assertThat(me.getStatusCode().value()).isEqualTo(200);
        userId = me.getBody().path("id").asText();
        assertThat(userId).isNotBlank();
    }

    @AfterAll
    void closeV2AndNetwork() {
        v2.stop();
        database.stop();
        NETWORK.close();
    }

    @Test
    void tokenIssuedByV2LoginAuthenticatesInV3WithoutSessionOrCsrfToken() throws Exception {
        String[] parts = validToken.split("\\.");
        assertThat(mapper.readTree(Base64.getUrlDecoder().decode(parts[0])).path("alg").asText())
                .isEqualTo("HS256");
        var claims = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(claims.path("exp").asLong() - claims.path("iat").asLong()).isEqualTo(7 * 24 * 60 * 60);
        var response = request(v3Url(), "/api/users/me", HttpMethod.GET, validToken, null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().path("userId").asText()).isEqualTo(userId);
        assertThat(response.getBody().path("email").asText()).isEqualTo(email);
        assertThat(response.getBody().path("session").asBoolean()).isFalse();
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
        var write = request(v3Url(), "/api/users/me", HttpMethod.PUT, validToken, Map.of("nickname", "ok"));
        assertThat(write.getStatusCode().value()).isEqualTo(200);
        assertThat(write.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(request(v3Url(), "/api/users/me", HttpMethod.GET, null, null)
                .getStatusCode().value()).isEqualTo(401);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "expired", "wrong-signature", "malformed", "no-sub", "future-nbf"})
    void protectedRouteMatchesV2Unauthorized(String kind) throws Exception {
        String token = token(kind);
        var expected = request(v2Url(), "/api/users/me", HttpMethod.GET, token, null);
        assertThat(expected.getStatusCode().value()).isEqualTo(401);
        assertThat(expected.getBody()).isEqualTo(mapper.readTree("{\"statusCode\":401,\"message\":\"Unauthorized\"}"));
        assertSame(expected, request(v3Url(), "/api/users/me", HttpMethod.GET, token, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "expired", "wrong-signature", "malformed", "valid"})
    void unknownRouteStays404RegardlessOfToken(String kind) throws Exception {
        String token = token(kind);
        String path = "/api/__missing?q=hello%20world";
        var expected = request(v2Url(), path, HttpMethod.GET, token, null);
        assertThat(expected.getStatusCode().value()).isEqualTo(404);
        assertSame(expected, request(v3Url(), path, HttpMethod.GET, token, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "malformed", "valid"})
    void wrongMethodIs404LikeV2(String kind) throws Exception {
        String token = token(kind);
        var expected = request(v2Url(), "/api/users/me", HttpMethod.POST, token, Map.of());
        assertThat(expected.getStatusCode().value()).isEqualTo(404);
        assertSame(expected, request(v3Url(), "/api/users/me", HttpMethod.POST, token, Map.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "malformed"})
    void publicValidationReturnsV2MessageArray(String kind) throws Exception {
        var body = Map.of("email", "bad", "password", "x");
        var expected = request(v2Url(), "/api/auth/login", HttpMethod.POST, token(kind), body);
        assertThat(expected.getStatusCode().value()).isEqualTo(400);
        assertThat(expected.getBody().path("message").size()).isEqualTo(2);
        assertSame(expected, request(v3Url(), "/api/auth/login", HttpMethod.POST, token(kind), body));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "malformed", "valid"})
    void authenticationPrecedesProtectedDtoValidation(String kind) throws Exception {
        String token = token(kind);
        var body = Map.of("nickname", "a".repeat(31));
        var expected = request(v2Url(), "/api/users/me", HttpMethod.PUT, token, body);
        assertThat(expected.getStatusCode().value()).isEqualTo(kind.equals("valid") ? 400 : 401);
        assertSame(expected, request(v3Url(), "/api/users/me", HttpMethod.PUT, token, body));
    }

    @Test
    void validTokenDoesNotBypassMethodAuthorization() {
        var response = request(v3Url(), "/api/restricted", HttpMethod.GET, validToken, null);
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody().path("error").asText()).isEqualTo("Forbidden");
    }

    @ParameterizedTest
    @ValueSource(strings = {"HS384", "HS512"})
    void onlyConfiguredHs256AlgorithmIsAccepted(String algorithm) throws Exception {
        assertThat(request(v3Url(), "/api/users/me", HttpMethod.GET, token(algorithm), null)
                .getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void publicMethodOverridesProtectedControllerAndProtectedMethodOverridesPublicController() {
        assertThat(request(v3Url(), "/api/public-override", HttpMethod.GET, "not-a-jwt", null)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(request(v3Url(), "/api/method-protected", HttpMethod.GET, null, null)
                .getStatusCode().value()).isEqualTo(401);
    }

    String token(String kind) throws Exception {
        if (kind.equals("missing")) return null;
        if (kind.equals("malformed")) return "not-a-jwt";
        if (kind.equals("valid")) return validToken;
        // 비정상 토큰도 v2가 사용하는 JwtService로 만들며 비밀 값은 출력하지 않는다.
        String script = """
                const {JwtService}=require('@nestjs/jwt');
                const {randomBytes}=require('crypto');
                const kind=process.argv[1];
                const payload={sub:process.argv[2],email:process.argv[3]};
                if(kind==='no-sub') delete payload.sub;
                const secret=kind==='wrong-signature'?randomBytes(32).toString('hex'):process.env.JWT_SECRET;
                if(kind==='expired') payload.iat=Math.floor(Date.now()/1000)-60;
                const options={expiresIn:kind==='expired'?59:604800};
                if(kind==='future-nbf') options.notBefore=3600;
                if(kind.startsWith('HS')) options.algorithm=kind;
                process.stdout.write(new JwtService({secret}).sign(payload,options));
                """;
        var result = v2.execInContainer("node", "-e", script, kind, userId, email);
        assertThat(result.getExitCode()).isZero();
        return result.getStdout();
    }

    ResponseEntity<JsonNode> request(String base, String path, HttpMethod method, String token, Object body) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token);
        return client.exchange(java.net.URI.create(base + path), method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    void assertSame(ResponseEntity<JsonNode> expected, ResponseEntity<JsonNode> actual) {
        assertThat(actual.getStatusCode()).isEqualTo(expected.getStatusCode());
        assertThat(actual.getBody()).isEqualTo(expected.getBody());
        assertThat(actual.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
    }

    String v2Url() { return "http://" + v2.getHost() + ":" + v2.getMappedPort(3000); }
    String v3Url() { return "http://localhost:" + port; }

    @TestComponent
    @ApiController
    @AuthenticatedApi
    @Import(PublicProbeController.class)
    public static class ProbeController {
        @GetMapping("/users/me")
        public Map<String, Object> me(@AuthenticationPrincipal JwtPrincipal user, HttpServletRequest request) {
            return Map.of("userId", user.userId(), "email", user.email(), "session", request.getSession(false) != null);
        }

        @PutMapping("/users/me")
        public Map<String, String> update(@Valid @RequestBody UpdateProbe body) { return Map.of("status", "ok"); }

        @GetMapping("/public-override")
        @PublicApi
        public Map<String, String> publicOverride() { return Map.of("status", "ok"); }

        @GetMapping("/restricted")
        @PreAuthorize("hasAuthority('unassigned')")
        public Map<String, String> restricted() { return Map.of("status", "ok"); }
    }

    @TestComponent
    @ApiController
    @PublicApi
    public static class PublicProbeController {
        @PostMapping("/auth/login")
        public Map<String, String> validate(@Valid @RequestBody LoginProbe body) { return Map.of("status", "ok"); }

        @GetMapping("/method-protected")
        @AuthenticatedApi
        public Map<String, String> protectedMethod() { return Map.of("status", "ok"); }
    }

    public record LoginProbe(@Email(message = "email must be an email") String email,
            @Size(min = 6, message = "password must be longer than or equal to 6 characters") String password) { }
    public record UpdateProbe(@Size(max = 30,
            message = "nickname must be shorter than or equal to 30 characters") String nickname) { }
}
