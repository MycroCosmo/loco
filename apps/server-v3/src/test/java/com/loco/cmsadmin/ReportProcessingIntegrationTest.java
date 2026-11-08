package com.loco.cmsadmin;

import static org.assertj.core.api.Assertions.*;

import com.cms.core.account.*;
import com.cms.core.permission.*;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "cms.cookie.secure=false",
      "cms.storage.enabled=false",
      "loco.cleanup.enabled=false",
      "loco.notifications.service-account="
    })
@Import(ReportProcessingIntegrationTest.TimeConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportProcessingIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> db =
      new PostgreSQLContainer<>(
              DockerImageName.parse("postgis/postgis:17-3.6").asCompatibleSubstituteFor("postgres"))
          .withPassword(UUID.randomUUID().toString())
          .withTmpFs(Map.of("/docker-entrypoint-initdb.d", "rw"));

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    db.start();
    r.add("spring.datasource.url", db::getJdbcUrl);
    r.add("spring.datasource.username", db::getUsername);
    r.add("spring.datasource.password", db::getPassword);
    r.add("loco.jwt.secret", () -> "synthetic-" + UUID.randomUUID() + UUID.randomUUID());
  }

  @Autowired JdbcTemplate sql;
  @Autowired ObjectMapper json;
  @Autowired AdminService admins;
  @Autowired RoleService roles;
  @Autowired TestClock clock;
  @LocalServerPort int port;
  final String report = "00000000-0000-4000-8000-000000000801",
      user = "00000000-0000-4000-8000-000000000802",
      password = UUID.randomUUID().toString();
  AdminIdentity root, second;
  String managerRoleId;
  static final Set<String> WRITE_CONTRACTS =
      Set.of(
          "com.loco.cmsadmin.ReportProcessingService#transition[class com.cms.core.account.AdminIdentity, class java.lang.String, class com.loco.cmsadmin.ReportProcessingService$Command]");
  Client one, two;

  record Client(HttpClient http, CookieManager cookies) {}

  @BeforeAll
  void accounts() {
    var initial = admins.bootstrap("root", "Root", password);
    admins.changePassword(initial, password, password);
    root = admins.authenticate("root", password);
    var member = admins.createAccount(root, "second", "Second", password);
    admins.changePassword(member, password, password);
    second = admins.authenticate("second", password);
    var role = roles.create(root, "REPORT_MANAGERS", "Managers", "MANAGER", Set.of("loco.reports"));
    managerRoleId = role.id();
    roles.assign(root, second.id(), Set.of(role.id()), second.authVersion());
    second = admins.authenticate("second", password);
    sql.update(
        "INSERT INTO users(id,email,nickname,\"updatedAt\") VALUES (?,'synthetic@example.test','Reporter',CURRENT_TIMESTAMP)",
        user);
  }

  @BeforeEach
  void seed() throws Exception {
    clock.advance();
    var current = admins.authenticate("second", password);
    roles.assign(root, current.id(), Set.of(managerRoleId), current.authVersion());
    resetProcessing();
    sql.execute("TRUNCATE reports");
    sql.update(
        "INSERT INTO reports(id,\"reporterId\",\"targetType\",\"targetId\",reason) VALUES (?,?,'USER',?,'reason')",
        report,
        user,
        user);
    one = login("root");
    two = login("second");
  }

  void resetProcessing() {
    sql.execute("TRUNCATE cms.loco_report_processing");
    sql.update("DELETE FROM cms.change_history WHERE resource_type='loco.report'");
  }

  URI uri(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }

  Client login(String login) throws Exception {
    var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    var c = new Client(HttpClient.newBuilder().cookieHandler(cookies).build(), cookies);
    get(c, "/admin/api/core/auth/csrf");
    assertThat(
            send(
                    c,
                    "/admin/api/core/auth/login",
                    "POST",
                    json.writeValueAsString(Map.of("loginId", login, "password", password)),
                    true)
                .statusCode())
        .isEqualTo(200);
    get(c, "/admin/api/core/auth/csrf");
    return c;
  }

  HttpResponse<String> get(Client c, String path) throws Exception {
    return c.http()
        .send(
            HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> send(Client c, String path, String method, String body, boolean csrf)
      throws Exception {
    var request = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json");
    if (csrf)
      request.header(
          "X-XSRF-TOKEN",
          c.cookies().getCookieStore().getCookies().stream()
              .filter(k -> k.getName().equals("XSRF-TOKEN"))
              .findFirst()
              .orElseThrow()
              .getValue());
    return c.http()
        .send(
            request.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
  }

  String command(String expected, String next, String note) throws Exception {
    var body = new LinkedHashMap<String, Object>();
    body.put("expectedStatus", expected);
    body.put("nextStatus", next);
    body.put("note", note);
    return json.writeValueAsString(body);
  }

  HttpResponse<String> put(Client c, String expected, String next, String note) throws Exception {
    return raw(c, command(expected, next, note));
  }

  HttpResponse<String> raw(Client c, String body) throws Exception {
    return send(c, "/admin/api/loco/reports/" + report + "/processing", "PUT", body, true);
  }

  JsonNode detail() throws Exception {
    var response = get(one, "/admin/api/loco/reports/" + report);
    assertThat(response.statusCode()).isEqualTo(200);
    return json.readTree(response.body());
  }

  long histories() {
    return sql.queryForObject(
        "SELECT count(*) FROM cms.change_history WHERE resource_type='loco.report'", Long.class);
  }

  List<String> publicSnapshot() {
    var rows = new ArrayList<String>();
    for (String table :
        List.of(
            "users",
            "maps",
            "map_members",
            "places",
            "place_marker_cells",
            "place_reviews",
            "favorites",
            "feedbacks",
            "reports",
            "refresh_tokens",
            "password_reset_tokens"))
      rows.addAll(
          sql.queryForList(
              "SELECT to_jsonb(t)::text FROM public." + table + " t ORDER BY to_jsonb(t)::text",
              String.class));
    return rows;
  }

  @Test
  void absenceMeansReceivedAndTransitionsKeepPublicAndSecretsOutOfHistory() throws Exception {
    var before = publicSnapshot();
    assertThat(detail().path("status").asText()).isEqualTo("RECEIVED");
    assertThat(sql.queryForObject("SELECT count(*) FROM cms.loco_report_processing", Integer.class))
        .isZero();
    assertThat(put(one, "RECEIVED", "IN_REVIEW", "private-first-note").statusCode()).isEqualTo(200);
    String finalNote = "private-final-note-" + UUID.randomUUID();
    assertThat(put(two, "IN_REVIEW", "RESOLVED", finalNote).statusCode()).isEqualTo(200);
    var value = detail();
    assertThat(value.path("note").asText()).isEqualTo(finalNote);
    assertThat(value.path("handledBy").asText()).isEqualTo(second.id());
    assertThat(value.path("handledAt").asText()).isEqualTo(clock.instant().toString());
    var rows =
        sql.queryForList(
            "SELECT to_jsonb(h)::text FROM cms.change_history h WHERE resource_type='loco.report' ORDER BY occurred_at,id",
            String.class);
    assertThat(rows)
        .hasSize(2)
        .allSatisfy(
            s ->
                assertThat(s)
                    .doesNotContain(
                        "private-first-note",
                        finalNote,
                        password,
                        "password_hash",
                        "csrfToken",
                        "fcmToken"));
    var snapshots =
        sql.queryForList(
            "SELECT before_data::text,after_data::text,actor_id,payload_version FROM cms.change_history WHERE resource_type='loco.report'");
    for (var h : snapshots) {
      assertThat(h.get("payload_version")).isEqualTo(1);
      for (String column : List.of("before_data", "after_data"))
        assertThat(json.readTree(h.get(column).toString()).properties())
            .extracting(Map.Entry::getKey)
            .containsExactlyInAnyOrder("status", "hasNote", "handledBy", "handledAt");
    }
    assertThat(publicSnapshot()).isEqualTo(before);
    assertThat(
            json.readTree(get(one, "/admin/api/loco/reports?status=RESOLVED").body()).path("items"))
        .hasSize(1);
    assertThat(get(one, "/admin/api/loco/reports").body()).doesNotContain(finalNote);
  }

  @Test
  void forbidsSkipReopenSameStatusAndOldExpected() throws Exception {
    assertThat(raw(one, command("RECEIVED", "RESOLVED", "memo")).statusCode()).isEqualTo(409);
    assertThat(histories()).isZero();
    assertThat(sql.queryForObject("SELECT count(*) FROM cms.loco_report_processing", Integer.class))
        .isZero();
    assertThat(put(one, "RECEIVED", "IN_REVIEW", null).statusCode()).isEqualTo(200);
    var stale = put(two, "RECEIVED", "IN_REVIEW", "other");
    assertThat(stale.statusCode()).isEqualTo(409);
    assertThat(stale.body()).contains("STALE_REPORT_STATE");
    assertThat(put(one, "IN_REVIEW", "IN_REVIEW", "edit").statusCode()).isEqualTo(409);
    assertThat(put(one, "IN_REVIEW", "DISMISSED", "reason").statusCode()).isEqualTo(200);
    assertThat(put(one, "DISMISSED", "IN_REVIEW", "reopen").statusCode()).isEqualTo(409);
    assertThat(histories()).isEqualTo(2);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "[]",
        "{}",
        "{\"expectedStatus\":1,\"nextStatus\":\"IN_REVIEW\"}",
        "{\"expectedStatus\":\"RECEIVED\",\"nextStatus\":\"OTHER\"}",
        "{\"expectedStatus\":\"RECEIVED\",\"nextStatus\":\"IN_REVIEW\",\"note\":42}",
        "{\"expectedStatus\":\"RECEIVED\",\"nextStatus\":\"IN_REVIEW\",\"handledBy\":\"fake\"}"
      })
  void invalidInputsNeverWrite(String body) throws Exception {
    assertThat(raw(one, body).statusCode()).isEqualTo(400);
    assertThat(histories()).isZero();
    assertThat(detail().path("status").asText()).isEqualTo("RECEIVED");
  }

  @Test
  void unicodeNoteBoundaryAndTerminalNoteRequired() throws Exception {
    assertThat(put(one, "RECEIVED", "IN_REVIEW", "😀".repeat(1001)).statusCode()).isEqualTo(400);
    assertThat(put(one, "RECEIVED", "IN_REVIEW", null).statusCode()).isEqualTo(200);
    assertThat(put(one, "IN_REVIEW", "RESOLVED", "  ").statusCode()).isEqualTo(400);
    assertThat(put(one, "IN_REVIEW", "RESOLVED", "😀".repeat(1000)).statusCode()).isEqualTo(200);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void historyInsertFailureRestoresInitialOrExistingState(boolean existing) throws Exception {
    if (existing)
      assertThat(put(one, "RECEIVED", "IN_REVIEW", "original").statusCode()).isEqualTo(200);
    var before = detail();
    long count = histories();
    var original = publicSnapshot();
    sql.execute(
        "ALTER TABLE cms.change_history ADD CONSTRAINT reject_report_history CHECK (resource_type<>'loco.report') NOT VALID");
    try {
      assertThat(
              put(
                      one,
                      existing ? "IN_REVIEW" : "RECEIVED",
                      existing ? "RESOLVED" : "IN_REVIEW",
                      "must-rollback")
                  .statusCode())
          .isGreaterThanOrEqualTo(400);
      assertThat(detail()).isEqualTo(before);
      assertThat(histories()).isEqualTo(count);
      assertThat(publicSnapshot()).isEqualTo(original);
      if (!existing)
        assertThat(
                sql.queryForObject(
                    "SELECT count(*) FROM cms.loco_report_processing", Integer.class))
            .isZero();
    } finally {
      sql.execute("ALTER TABLE cms.change_history DROP CONSTRAINT reject_report_history");
    }
  }

  @Test
  void csrfAndReadOnlyCannotWriteAndRevocationTakesEffect() throws Exception {
    assertThat(
            send(
                    one,
                    "/admin/api/loco/reports/" + report + "/processing",
                    "PUT",
                    command("RECEIVED", "IN_REVIEW", null),
                    false)
                .statusCode())
        .isEqualTo(403);
    var role =
        roles.create(
            root,
            "READ" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT),
            "Read",
            "READ_ONLY",
            Set.of("loco.reports"));
    var current = admins.authenticate("second", password);
    roles.assign(root, current.id(), Set.of(role.id()), current.authVersion());
    assertThat(put(two, "RECEIVED", "IN_REVIEW", null).statusCode()).isEqualTo(401);
    var reader = login("second");
    assertThat(put(reader, "RECEIVED", "IN_REVIEW", null).statusCode()).isEqualTo(403);
    assertThat(histories()).isZero();
  }

  @Test
  void missingOriginalCannotProcessAndHistorySurvivesDeletion() throws Exception {
    assertThat(put(one, "RECEIVED", "IN_REVIEW", null).statusCode()).isEqualTo(200);
    sql.update("DELETE FROM reports WHERE id=?", report);
    assertThat(put(one, "IN_REVIEW", "RESOLVED", "gone").statusCode()).isEqualTo(404);
    assertThat(histories()).isEqualTo(1);
    assertThat(sql.queryForObject("SELECT count(*) FROM cms.loco_report_processing", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void forcedRacesHaveOneWinnerAndOneHistoryForTwentyRoundsEach() throws Exception {
    var reportRows = new ArrayList<Map<String, Object>>();
    for (boolean initial : List.of(true, false)) {
      int ok = 0, conflict = 0;
      long start = System.nanoTime();
      for (int i = 0; i < 20; i++) {
        resetProcessing();
        if (!initial)
          assertThat(put(one, "RECEIVED", "IN_REVIEW", null).statusCode()).isEqualTo(200);
        long baseline = histories();
        var responses =
            race(
                initial,
                command(
                    initial ? "RECEIVED" : "IN_REVIEW",
                    initial ? "IN_REVIEW" : "RESOLVED",
                    "one-" + i),
                command(
                    initial ? "RECEIVED" : "IN_REVIEW",
                    initial ? "IN_REVIEW" : "DISMISSED",
                    "two-" + i));
        assertThat(responses)
            .extracting(HttpResponse::statusCode)
            .containsExactlyInAnyOrder(200, 409);
        ok++;
        conflict++;
        assertThat(histories()).isEqualTo(baseline + 1);
        var winner =
            responses.stream().filter(r -> r.statusCode() == 200).findFirst().orElseThrow();
        var dto = json.readTree(winner.body());
        assertThat(detail().path("note")).isEqualTo(dto.path("note"));
        assertThat(detail().path("handledBy")).isEqualTo(dto.path("handledBy"));
        assertThat(
                sql.queryForObject(
                    "SELECT count(*) FROM cms.loco_report_processing", Integer.class))
            .isEqualTo(1);
      }
      reportRows.add(
          Map.of(
              "scenario",
              initial ? "initial-insert" : "terminal-transition",
              "rounds",
              20,
              "success",
              ok,
              "conflict",
              conflict,
              "lostWrites",
              0,
              "extraHistory",
              0,
              "seconds",
              (System.nanoTime() - start) / 1e9));
    }
    var path = Path.of("build/reports/cms-report/races.json");
    Files.createDirectories(path.getParent());
    json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), reportRows);
  }

  List<HttpResponse<String>> race(boolean initial, String first, String secondBody)
      throws Exception {
    try (var gate =
            DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
        var pool = Executors.newFixedThreadPool(2)) {
      gate.setAutoCommit(false);
      try (var q =
          gate.prepareStatement(
              initial
                  ? "INSERT INTO cms.loco_report_processing(report_id,status) VALUES (?,'RECEIVED')"
                  : "SELECT report_id FROM cms.loco_report_processing WHERE report_id=? FOR UPDATE")) {
        q.setString(1, report);
        q.execute();
      }
      var a = pool.submit(() -> raw(one, first));
      var b = pool.submit(() -> raw(two, secondBody));
      try {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (sql.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query ILIKE '%cms.loco_report_processing%'",
                Integer.class)
            < 2) {
          if (System.nanoTime() > deadline)
            throw new AssertionError("Both HTTP mutations must reach the database gate");
          Thread.sleep(10);
        }
      } finally {
        gate.rollback();
      }
      return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
    }
  }

  @TestConfiguration
  static class TimeConfig {
    @Bean
    @Primary
    TestClock testClock() {
      return new TestClock();
    }
  }

  static class TestClock extends Clock {
    private volatile Instant time = Instant.parse("2026-11-08T00:00:00Z");

    void advance() {
      time = time.plusSeconds(61);
    }

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId z) {
      return Clock.fixed(time, z);
    }

    public Instant instant() {
      return time;
    }
  }
}
