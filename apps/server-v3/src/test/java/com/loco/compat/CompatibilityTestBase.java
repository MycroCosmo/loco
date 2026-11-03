package com.loco.compat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SharedHarness.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.flyway.target=1")
@Import(HarnessControllers.class)
public abstract class CompatibilityTestBase {
    private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10)).version(java.net.http.HttpClient.Version.HTTP_1_1).build();
    @LocalServerPort protected int port;
    @Autowired protected TestRestTemplate client;
    @Autowired protected ObjectMapper mapper;
    @Autowired private HarnessRateLimitTime rateLimitTime;

    protected boolean usesV2Http() { return true; }

    @BeforeEach
    void configureHttpClient() {
        if (usesV2Http()) environment().restartV2ForCase();
        rateLimitTime.nextWindow();
        environment().v3Budget.clearWindow();
        var factory = new JdkClientHttpRequestFactory(HTTP);
        factory.setReadTimeout(java.time.Duration.ofSeconds(10));
        client.getRestTemplate().setRequestFactory(factory);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        var env = SharedHarness.environment();
        registry.add("spring.datasource.url", () -> env.url(SharedHarness.Side.V3));
        registry.add("spring.datasource.username", env::username);
        registry.add("spring.datasource.password", env::databasePassword);
        registry.add("loco.jwt.secret", () -> env.secret);
        registry.add("spring.mail.host", env::smtpHost);
        registry.add("spring.mail.port", env::smtpPort);
        registry.add("spring.mail.username", () -> "sender@example.test");
        registry.add("spring.mail.password", () -> env.password);
    }

    protected SharedHarness.Environment environment() { return SharedHarness.environment(); }
    protected String v2Url() { return environment().v2Url(); }
    protected String v3Url() { return "http://localhost:" + port; }

    protected ResponseEntity<JsonNode> request(String base, String path, HttpMethod method, String token, Object body) {
        return request(base, path, method, token, body, false);
    }

    protected ResponseEntity<JsonNode> request(String base, String path, HttpMethod method,
                                               String token, Object body, boolean rateLimitProbe) {
        try {
            var budget = base.equals(v2Url()) ? environment().budget : environment().v3Budget;
            if (rateLimitProbe) budget.recordUnchecked(path);
            else budget.before(method.name(), path);
            var headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (token != null) headers.setBearerAuth(token);
            var response = client.exchange(URI.create(base + path), method, new HttpEntity<>(body, headers), JsonNode.class);
            if (!rateLimitProbe && response.getStatusCode().value() == 429) {
                throw new AssertionError("예상하지 않은 429: " + method + " " + path);
            }
            return response;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("요청 제한 대기 중 중단", exception);
        }
    }

    protected Map<SharedHarness.Side, ScenarioValues> runCase(ComparisonCase testCase) throws Exception {
        environment().reset(testCase.seedResource());
        var rules = testCase.databaseRules().apply(new ScenarioValues());
        DatabaseSnapshot beforeV2, beforeV3;
        try (var left = environment().connect(SharedHarness.Side.V2);
             var right = environment().connect(SharedHarness.Side.V3)) {
            beforeV2 = DatabaseSnapshot.capture(left, rules);
            beforeV3 = DatabaseSnapshot.capture(right, rules);
        }
        Set<String> seedIds = beforeV2.seedIds();
        DatabaseSnapshot.compare(beforeV2, beforeV3, seedIds, rules);
        var v2Values = new ScenarioValues();
        var v3Values = new ScenarioValues();
        for (var step : testCase.steps()) {
            try {
                var leftResponse = requestStep(step, SharedHarness.Side.V2, v2Values);
                var rightResponse = requestStep(step, SharedHarness.Side.V3, v3Values);
                assertThat(leftResponse.getStatusCode().value()).as("v2 상태").isEqualTo(step.expectedStatus());
                assertThat(rightResponse.getStatusCode().value()).as("v3 상태").isEqualTo(step.expectedStatus());
                JsonComparison.compare(leftResponse.getBody(), rightResponse.getBody(), step.valueExceptions());
                v2Values.capture(leftResponse.getBody(), step.captures());
                v3Values.capture(rightResponse.getBody(), step.captures());
                var leftRules = testCase.databaseRules().apply(v2Values);
                var rightRules = testCase.databaseRules().apply(v3Values);
                try (var left = environment().connect(SharedHarness.Side.V2);
                     var right = environment().connect(SharedHarness.Side.V3)) {
                    var afterV2 = DatabaseSnapshot.capture(left, leftRules);
                    var afterV3 = DatabaseSnapshot.capture(right, rightRules);
                    DatabaseSnapshot.compare(afterV2, afterV3, seedIds, leftRules, rightRules);
                    if (testCase.databaseUnchanged()) {
                        beforeV2.assertUnchanged(afterV2);
                        beforeV3.assertUnchanged(afterV3);
                    }
                }
            } catch (AssertionError error) {
                throw new AssertionError(testCase.name() + " / " + step.name() + ": " + error.getMessage(), error);
            }
        }
        return Map.of(SharedHarness.Side.V2, v2Values, SharedHarness.Side.V3, v3Values);
    }

    private ResponseEntity<JsonNode> requestStep(ComparisonStep step, SharedHarness.Side side,
                                                ScenarioValues values) throws Exception {
        String token = step.authenticated() ? environment().token("valid",
                SharedHarness.Environment.USER_ID, SharedHarness.Environment.EMAIL)
                : step.bearer() == null ? null : step.bearer().apply(values);
        Object body = step.bodyResource() != null
                ? new ClassPathResource(step.bodyResource()).getContentAsString(StandardCharsets.UTF_8)
                : step.body() == null ? null : step.body().apply(values);
        String path = (side == SharedHarness.Side.V2 ? step.path() : step.v3Path()).apply(values);
        if (path == null || !path.startsWith("/api/")) throw new AssertionError("/api/ 아래 요청 경로 필요");
        return request(side == SharedHarness.Side.V2 ? v2Url() : v3Url(), path, step.method(), token, body);
    }
}
