package com.loco.compat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@org.junit.jupiter.api.Tag("v2-comparison")
class HarnessInfrastructureTest extends CompatibilityTestBase {
    private static final DatabaseSnapshot.Rules DEFAULT = DatabaseSnapshot.Rules.defaults();

    @BeforeEach void reset() throws Exception { environment().reset("compat/seed.sql"); }

    @Test
    @DisplayName("하네스 자체 검증: HTTP 쓰기 → 생성 ID 제외 → DB 상태 비교")
    void writeRequestUsesIndependentDatabasesAndComparesNewIds() throws Exception {
        for (String path : List.of("/api/feedback")) {
            assertThat(request(v3Url(), path, HttpMethod.POST, null, Map.of()).getStatusCode().value()).isEqualTo(404);
        }
        assertThat(request(v3Url(), "/api/users/me", HttpMethod.GET, null, null).getStatusCode().value()).isEqualTo(401);
        runCase(ComparisonCase.named("쓰기 비교 대역")
                .step(ComparisonStep.named("피드백 작성").method(HttpMethod.POST).path("/api/feedback")
                        .v3Path("/api/__test/harness/feedback").bodyResource("compat/feedback.json")
                        .expectedStatus(201).authenticated().valueException("/id", "각 DB에서 생성한 UUID")
                        .build()).build());
        for (var side : SharedHarness.Side.values()) {
            try (var connection = environment().connect(side);
                 var result = connection.createStatement().executeQuery("SELECT count(*) FROM feedbacks")) {
                result.next();
                assertThat(result.getInt(1)).isEqualTo(1);
            }
        }
        environment().reset("compat/seed.sql");
        for (var side : SharedHarness.Side.values()) {
            try (var connection = environment().connect(side);
                 var result = connection.createStatement().executeQuery(
                         "SELECT (SELECT count(*) FROM feedbacks), (SELECT count(*) FROM users), "
                         + "(SELECT version FROM flyway_schema_history WHERE success)")) {
                result.next();
                assertThat(result.getInt(1)).isZero();
                assertThat(result.getInt(2)).isEqualTo(1);
                assertThat(result.getString(3)).isEqualTo("1");
            }
        }
    }

    @Test
    @DisplayName("연속 요청: 서버별 이전 응답을 다음 본문·경로에 전달하고 DB 유지")
    void scenarioReusesEachServersResponseWithoutResettingBetweenSteps() throws Exception {
        var capturedIds = new ArrayList<String>();
        runCase(ComparisonCase.named("쓰기 → 이전 메시지로 쓰기 → 생성 ID 경로 요청")
                .step(ComparisonStep.named("첫 쓰기").method(HttpMethod.POST).path("/api/feedback")
                        .v3Path("/api/__test/harness/feedback").authenticated().expectedStatus(201)
                        .bodyResource("compat/feedback.json").valueException("/id", "서버별 생성 ID")
                        .capture("firstId", "/id").capture("message", "/message").build())
                .step(ComparisonStep.named("이전 응답으로 두 번째 쓰기").method(HttpMethod.POST)
                        .path("/api/feedback").v3Path("/api/__test/harness/feedback")
                        .authenticated().expectedStatus(201)
                        .body(values -> Map.of("content", values.text("message")))
                        .valueException("/id", "서버별 생성 ID").build())
                .step(ComparisonStep.named("서버별 ID로 없는 경로 조회").method(HttpMethod.GET)
                        .path(values -> {
                            capturedIds.add(values.text("firstId"));
                            return "/api/__comparison_missing/" + values.text("firstId");
                        }).expectedStatus(404).valueException("/message", "경로에 서버별 생성 ID 포함").build())
                .build());
        assertThat(capturedIds).hasSize(2).doesNotHaveDuplicates();
        for (var side : SharedHarness.Side.values()) {
            try (var connection = environment().connect(side);
                 var statement = connection.prepareStatement("SELECT content FROM feedbacks WHERE id=?")) {
                statement.setString(1, capturedIds.get(side.ordinal()));
                try (var result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("comparison harness write probe");
                }
                try (var result = connection.createStatement().executeQuery("SELECT count(*) FROM feedbacks")) {
                    result.next();
                    assertThat(result.getInt(1)).isEqualTo(2);
                }
            }
        }
    }

    @Test
    void uuidAliasesPreserveReferencesAcrossTablesAndRejectWrongParent() throws Exception {
        String secondMapV3 = null;
        for (var side : SharedHarness.Side.values()) {
            String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
            if (side == SharedHarness.Side.V3) secondMapV3 = second;
            insertMap(side, first, "alpha");
            insertMap(side, second, "beta");
            execute(side, """
                    INSERT INTO places (id,"mapId","createdById",name,address,latitude,longitude,"updatedAt")
                    VALUES (?, ?, ?, 'place', 'address', 37, 127, CURRENT_TIMESTAMP)
                    """, UUID.randomUUID().toString(), first, SharedHarness.Environment.USER_ID);
        }
        compare(DEFAULT);
        execute(SharedHarness.Side.V3, "UPDATE places SET \"mapId\"=?", secondMapV3);
        assertThatThrownBy(() -> compare(DEFAULT)).isInstanceOf(AssertionError.class);
    }

    @Test
    void tiedRowsFailUntilANonIdJoinedSortKeyIsSpecified() throws Exception {
        for (var side : SharedHarness.Side.values()) {
            for (String title : List.of("alpha", "beta")) {
                String mapId = UUID.randomUUID().toString();
                insertMap(side, mapId, title);
                execute(side, "INSERT INTO favorites (id,\"userId\",\"mapId\") VALUES (?,?,?)",
                        UUID.randomUUID().toString(), SharedHarness.Environment.USER_ID, mapId);
            }
        }
        assertThatThrownBy(() -> compare(DEFAULT)).isInstanceOf(AssertionError.class).hasMessageContaining("모호");
        var rules = new DatabaseSnapshot.Rules(Map.of("favorites", List.of("__sort_map")), Map.of("favorites", """
                SELECT f.*, m.title AS "__sort_map" FROM favorites f JOIN maps m ON m.id=f."mapId"
                """));
        compare(rules);
    }

    @Test
    void timeValueDifferencesAreAllowedButNullAndUnexpectedMutationsAreNot() throws Exception {
        DatabaseSnapshot before = snapshot(SharedHarness.Side.V3, DEFAULT);
        execute(SharedHarness.Side.V3, "UPDATE users SET \"updatedAt\"='2026-02-01'");
        compare(DEFAULT);
        assertThatThrownBy(() -> before.assertUnchanged(snapshot(SharedHarness.Side.V3, DEFAULT)))
                .isInstanceOf(AssertionError.class);
        execute(SharedHarness.Side.V3, "UPDATE users SET \"deletedAt\"=CURRENT_TIMESTAMP");
        assertThatThrownBy(() -> compare(DEFAULT)).isInstanceOf(AssertionError.class);
    }

    @Test
    void customSortQueryCannotHideBusinessColumnsOrRows() {
        var missingColumn = new DatabaseSnapshot.Rules(Map.of(), Map.of("users", "SELECT id FROM users"));
        assertThatThrownBy(() -> compare(missingColumn)).isInstanceOf(AssertionError.class).hasMessageContaining("컬럼 누락");
        var missingRow = new DatabaseSnapshot.Rules(Map.of(), Map.of("users", "SELECT * FROM users WHERE false"));
        assertThatThrownBy(() -> compare(missingRow)).isInstanceOf(AssertionError.class).hasMessageContaining("행 누락");
    }

    @Test
    void tokenHashValuesCanDifferButTheirFormatMustRemainValid() throws Exception {
        for (var side : SharedHarness.Side.values()) {
            execute(side, """
                    INSERT INTO refresh_tokens (id,"userId","tokenHash","expiresAt")
                    VALUES (?, ?, ?, CURRENT_TIMESTAMP + INTERVAL '1 day')
                    """, UUID.randomUUID().toString(), SharedHarness.Environment.USER_ID,
                    side == SharedHarness.Side.V2 ? "a".repeat(64) : "b".repeat(64));
        }
        compare(DEFAULT);
        execute(SharedHarness.Side.V3, "UPDATE refresh_tokens SET \"tokenHash\"='invalid'");
        assertThatThrownBy(() -> compare(DEFAULT)).isInstanceOf(AssertionError.class).hasMessageContaining("해시");
    }

    @Test
    @DisplayName("Mailpit 기반 검증: v2 재설정 메일 수신·코드 해시·우편함 비우기")
    void mailpitCapturesRealV2ResetMailWithoutExternalDelivery() throws Exception {
        var response = request(v2Url(), "/api/auth/forgot-password", HttpMethod.POST, null,
                Map.of("email", SharedHarness.Environment.EMAIL));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        var mail = environment().awaitMail(SharedHarness.Environment.EMAIL);
        assertThat(mail.path("Subject").asText()).isEqualTo("[LOCO] 비밀번호 재설정 인증 코드");
        var codeMatch = Pattern.compile("(?<!\\d)\\d{6}(?!\\d)").matcher(mail.path("HTML").asText());
        assertThat(codeMatch.find()).as("메일의 6자리 코드").isTrue();
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(codeMatch.group().getBytes(StandardCharsets.UTF_8)));
        try (var connection = environment().connect(SharedHarness.Side.V2);
             var result = connection.createStatement().executeQuery(
                     "SELECT \"tokenHash\", \"userId\", \"expiresAt\">\"createdAt\" FROM password_reset_tokens")) {
            assertThat(result.next()).isTrue();
            // 실패 시 원문 코드나 해시를 출력하지 않는다.
            assertThat(digest.equals(result.getString(1))).isTrue();
            assertThat(result.getString(2)).isEqualTo(SharedHarness.Environment.USER_ID);
            assertThat(result.getBoolean(3)).isTrue();
        }
        try (var connection = environment().connect(SharedHarness.Side.V3);
             var result = connection.createStatement().executeQuery("SELECT count(*) FROM password_reset_tokens")) {
            result.next();
            assertThat(result.getInt(1)).isZero();
        }
        environment().clearMail();
        assertThat(environment().mailRequest("GET", "/api/v1/messages").path("messages").size()).isZero();
    }

    private void insertMap(SharedHarness.Side side, String id, String title) throws Exception {
        execute(side, """
                INSERT INTO maps (id,"ownerId",title,"updatedAt") VALUES (?,?,?,CURRENT_TIMESTAMP)
                """, id, SharedHarness.Environment.USER_ID, title);
    }
    private void execute(SharedHarness.Side side, String sql, Object... values) throws Exception {
        try (var connection = environment().connect(side); var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
        }
    }
    private DatabaseSnapshot snapshot(SharedHarness.Side side, DatabaseSnapshot.Rules rules) throws Exception {
        try (var connection = environment().connect(side)) { return DatabaseSnapshot.capture(connection, rules); }
    }
    private void compare(DatabaseSnapshot.Rules rules) throws Exception {
        DatabaseSnapshot.compare(snapshot(SharedHarness.Side.V2, rules), snapshot(SharedHarness.Side.V3, rules),
                java.util.Set.of(SharedHarness.Environment.USER_ID), rules);
    }
}
