package com.loco.cmsadmin;

import static org.assertj.core.api.Assertions.*;

import com.cms.core.account.*;
import com.cms.core.permission.*;
import com.cms.core.web.CmsController;
import com.fasterxml.jackson.databind.*;
import com.loco.maps.SqlQueryCounter;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.web.bind.annotation.*;
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
      "loco.notifications.service-account=",
      "loco.uploads.directory="
    })
@Import({SqlQueryCounter.Configuration.class, LocoReadIntegrationTest.Probes.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocoReadIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> db =
      new PostgreSQLContainer<>(
              DockerImageName.parse("postgis/postgis:17-3.6").asCompatibleSubstituteFor("postgres"))
          .withPassword(UUID.randomUUID().toString())
          .withTmpFs(Map.of("/docker-entrypoint-initdb.d", "rw"));

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    // PER_CLASS creates the Spring test instance before JUnit's container beforeAll callback.
    db.start();
    r.add("spring.datasource.url", db::getJdbcUrl);
    r.add("spring.datasource.username", db::getUsername);
    r.add("spring.datasource.password", db::getPassword);
    r.add("loco.jwt.secret", () -> UUID.randomUUID() + "-" + UUID.randomUUID());
  }

  @Autowired JdbcTemplate sql;
  @Autowired ObjectMapper json;
  @Autowired AdminService admins;
  @Autowired RoleService roles;
  @Autowired PermissionCatalog catalog;
  @Autowired TestClock clock;
  @LocalServerPort int port;
  AdminIdentity root;
  HttpClient http;
  String password = UUID.randomUUID().toString();
  String user1 = "00000000-0000-4000-8000-000000000101",
      user2 = "00000000-0000-4000-8000-000000000102";

  @BeforeAll
  void admin() throws Exception {
    var initial = admins.bootstrap("cmsread", "Read root", password);
    admins.changePassword(initial, password, password);
    root = admins.authenticate("cmsread", password);
    http = login("cmsread", password);
  }

  @BeforeEach
  void seed() {
    clock.advance();
    sql.execute("TRUNCATE users CASCADE");
    for (String id : List.of(user1, user2))
      sql.update(
          """
      INSERT INTO users(id,email,nickname,password,"fcmToken","providerId","profileImageUrl","createdAt","updatedAt","deletedAt")
      VALUES (?,?,?,'synthetic-password-hash','synthetic-fcm','synthetic-provider','/uploads/private.webp',
      '2026-01-01','2026-01-01',?)
      """,
          id,
          id + "@example.test",
          id.equals(user1) ? "100%_회원" : "일반회원",
          id.equals(user1) ? null : java.sql.Timestamp.valueOf("2026-02-01 00:00:00"));
    for (int i = 1; i <= 2; i++)
      sql.update(
          """
      INSERT INTO maps(id,"ownerId",title,description,tags,visibility,"inviteCode","coverImageUrl","createdAt","updatedAt")
      VALUES (?,?,?,'private description',ARRAY['tag'],CAST(? AS "Visibility"),'invite-'||?,'/uploads/hidden.webp','2026-01-01','2026-01-01')
      """,
          mapId(i),
          user1,
          "Map " + i,
          i == 1 ? "PRIVATE" : "PUBLIC",
          i);
    SqlQueryCounter.reset();
  }

  String mapId(int i) {
    return "00000000-0000-4000-8000-00000000020" + i;
  }

  @Test
  void mapsIncludePrivateButNeverInviteCodeOrImages() throws Exception {
    var before = snapshot();
    assertThat(ok("/maps").path("items").size()).isEqualTo(2);
    var detail = ok("/maps/" + mapId(1));
    assertThat(detail.path("visibility").asText()).isEqualTo("PRIVATE");
    assertThat(detail.path("description").asText()).isEqualTo("private description");
    assertThat(detail.toString())
        .doesNotContain("inviteCode", "invite-", "coverImageUrl", "/uploads/", "email");
    assertThat(ok("/maps?visibility=PRIVATE&tag=tag&ownerId=" + user1).path("items").size())
        .isEqualTo(1);
    assertThat(ok("/maps?tag=ta").path("items").size()).isZero();
    assertThat(ok("/maps?search=" + encode("Map 2")).path("items").get(0).path("id").asText())
        .isEqualTo(mapId(2));
    assertThat(get(http, "/admin/api/loco/maps?visibility=BAD").statusCode()).isEqualTo(400);
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void mapReadIsExplicitAndRevocationIsImmediate() throws Exception {
    String name = "map" + UUID.randomUUID().toString().substring(0, 8);
    var member = admins.createAccount(root, name, "Map reader", password);
    admins.changePassword(member, password, password);
    member = admins.authenticate(name, password);
    var role = roles.create(root, name, "Map reader", "READ_ONLY", Set.of("loco.maps"));
    roles.assign(root, member.id(), Set.of(role.id()), member.authVersion());
    var reader = login(name, password);
    assertThat(get(reader, "/admin/api/loco/maps/" + mapId(1)).statusCode()).isEqualTo(200);
    assertThat(get(reader, "/admin/api/loco/users").statusCode()).isEqualTo(403);
    assertThat(catalog.definition("loco.maps").label()).contains("비공개 포함");
    roles.permissions(root, role.id(), Map.of(), role.version());
    var denied = get(reader, "/admin/api/loco/maps");
    assertThat(denied.statusCode()).isEqualTo(403);
    assertThat(json.readTree(denied.body()).path("code").asText()).isEqualTo("PERMISSION_DENIED");
  }

  HttpClient login(String name, String password) throws Exception {
    var client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    var csrf =
        client.send(
            HttpRequest.newBuilder(uri("/admin/api/core/auth/csrf")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    String token =
        csrf.headers().allValues("set-cookie").stream()
            .filter(c -> c.startsWith("XSRF-TOKEN="))
            .findFirst()
            .orElseThrow()
            .split(";", 2)[0]
            .substring(11);
    var response =
        client.send(
            HttpRequest.newBuilder(uri("/admin/api/core/auth/login"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", token)
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        json.writeValueAsString(Map.of("loginId", name, "password", password))))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    return client;
  }

  URI uri(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }

  HttpResponse<String> get(HttpClient client, String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  JsonNode ok(String path) throws Exception {
    var r = get(http, "/admin/api/loco" + path);
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    return json.readTree(r.body());
  }

  String encode(String value) {
    return URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
  }

  Map<String, List<String>> snapshot() {
    var result = new TreeMap<String, List<String>>();
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
      result.put(
          table,
          sql.queryForList(
              "SELECT to_jsonb(t)::text FROM public." + table + " t ORDER BY to_jsonb(t)::text",
              String.class));
    result.put(
        "history",
        sql.queryForList(
            "SELECT to_jsonb(t)::text FROM cms.change_history t ORDER BY id", String.class));
    return result;
  }

  @Test
  void usersAreMaskedAndReadsDoNotWrite() throws Exception {
    var before = snapshot();
    SqlQueryCounter.reset();
    var page = ok("/users");
    assertThat(page.path("items").size()).isEqualTo(2);
    assertThat(page.path("items").get(0).path("emailMasked").asText()).isEqualTo("0***@***");
    assertThat(page.toString())
        .doesNotContain(
            "@example.test", "password", "fcmToken", "providerId", "profileImageUrl", "synthetic-");
    assertThat(ok("/users/" + user2).path("deletedAt").isNull()).isFalse();
    assertThat(
            SqlQueryCounter.statements().stream()
                .filter(s -> s.contains("FROM public.users"))
                .count())
        .isEqualTo(2);
    assertThat(SqlQueryCounter.statements())
        .allMatch(s -> s.toLowerCase(Locale.ROOT).startsWith("select"));
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void usersCursorSurvivesDeletionAndRejectsOtherFilters() throws Exception {
    var first = ok("/users?size=1");
    String cursor = first.path("nextCursor").asText();
    assertThat(first.path("items").get(0).path("id").asText()).isEqualTo(user2);
    sql.update("DELETE FROM users WHERE id=?", user2);
    assertThat(
            ok("/users?size=1&cursor=" + encode(cursor)).path("items").get(0).path("id").asText())
        .isEqualTo(user1);
    assertThat(
            get(http, "/admin/api/loco/users?size=1&status=ACTIVE&cursor=" + encode(cursor))
                .statusCode())
        .isEqualTo(400);
  }

  @Test
  void literalSearchAndStateAndTimeFilters() throws Exception {
    assertThat(ok("/users?search=" + encode("%_")).path("items").size()).isEqualTo(1);
    assertThat(ok("/users?status=ACTIVE").path("items").get(0).path("id").asText())
        .isEqualTo(user1);
    assertThat(ok("/users?status=DELETED").path("items").size()).isEqualTo(1);
    assertThat(ok("/users?provider=GOOGLE").path("items").size()).isZero();
    assertThat(ok("/users?until=2026-01-01T00:00:00Z").path("items").size()).isZero();
    assertThat(ok("/users?from=2026-01-01T00:00:00Z").path("items").size()).isEqualTo(2);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "size=0",
        "size=101",
        "size=1.5",
        "size=1&size=2",
        "id=bad",
        "unknown=x",
        "provider=BAD",
        "status=STOPPED",
        "cursor=broken",
        "from=2026-01-01",
        "from=2026-01-02T00:00:00Z&until=2026-01-01T00:00:00Z"
      })
  void invalidQueryIsCms400(String query) throws Exception {
    var response = get(http, "/admin/api/loco/users?" + query);
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("INVALID_INPUT");
  }

  @Test
  void anonymousAndAppJwtCannotRead() throws Exception {
    assertThat(get(HttpClient.newHttpClient(), "/admin/api/loco/users").statusCode())
        .isEqualTo(401);
    assertThat(get(http, "/api/users/me").statusCode()).isEqualTo(401);
  }

  @Test
  void scopedReadAndHistoryPermissionStaySeparate() throws Exception {
    String name = "reader" + UUID.randomUUID().toString().substring(0, 8);
    var member = admins.createAccount(root, name, "Reader", password);
    admins.changePassword(member, password, password);
    member = admins.authenticate(name, password);
    var role = roles.create(root, name, "회원 조회", "READ_ONLY", Set.of("loco.users"));
    roles.assign(root, member.id(), Set.of(role.id()), member.authVersion());
    var reader = login(name, password);
    assertThat(get(reader, "/admin/api/loco/users").statusCode()).isEqualTo(200);
    assertThat(get(reader, "/admin/api/core/history").statusCode()).isEqualTo(403);
    var h =
        sql.queryForObject(
            "SELECT id FROM cms.change_history ORDER BY occurred_at DESC LIMIT 1", String.class);
    assertThat(get(reader, "/admin/api/core/history/" + h).statusCode()).isEqualTo(403);
    admins.setActive(root, member.id(), false);
    assertThat(get(reader, "/admin/api/loco/users").statusCode()).isEqualTo(401);
  }

  @Test
  void noGrantAndFirstPasswordAreNotReaders() throws Exception {
    String name = "empty" + UUID.randomUUID().toString().substring(0, 8);
    var member = admins.createAccount(root, name, "Empty", password);
    var first = login(name, password);
    assertThat(json.readTree(get(first, "/admin/api/loco/users").body()).path("code").asText())
        .isEqualTo("PASSWORD_CHANGE_REQUIRED");
    admins.changePassword(member, password, password);
    var reader = login(name, password);
    assertThat(json.readTree(get(reader, "/admin/api/loco/users").body()).path("code").asText())
        .isEqualTo("PERMISSION_DENIED");
  }

  @Test
  void cmsAdviceAndMissingResourcesDoNotUseAppShape() throws Exception {
    var failure = get(http, "/admin/api/probe/failure");
    assertThat(failure.statusCode()).isEqualTo(500);
    assertThat(failure.body()).doesNotContain("internal-secret", "\"error\":");
    assertThat(json.readTree(failure.body()).path("code").asText()).isEqualTo("INTERNAL_ERROR");
    assertThat(get(http, "/admin/api/loco/users/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
    assertThat(get(http, "/admin/api/loco/reviews").statusCode()).isEqualTo(404);
    assertThat(catalog.definition("loco.users").allowedActions()).containsExactly(Action.READ);
  }

  @Test
  void everyCmsReadServiceMethodHasAnExplicitReadContract() {
    assertThat(
            Arrays.stream(LocoReadService.class.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                .map(m -> m.getName() + Arrays.toString(m.getParameterTypes()))
                .toList())
        .hasSize(2);
    assertThat(
            LocoReadService.class
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class)
                .readOnly())
        .isTrue();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Probes {
    @Bean
    @Primary
    TestClock cmsReadClock() {
      return new TestClock();
    }

    @Bean
    Probe cmsReadProbe() {
      return new Probe();
    }
  }

  @TestComponent
  @CmsController
  static class Probe {
    @GetMapping("/admin/api/probe/failure")
    public String fail() {
      throw new IllegalStateException("internal-secret");
    }
  }

  static class TestClock extends Clock {
    volatile Instant now = Instant.now();

    void advance() {
      now = now.plusSeconds(61);
    }

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return now;
    }
  }
}
