package com.cms.core;

import static org.assertj.core.api.Assertions.*;

import com.cms.core.account.*;
import com.cms.core.history.*;
import com.cms.core.menu.*;
import com.cms.core.permission.*;
import com.fasterxml.jackson.databind.*;
import java.lang.reflect.Modifier;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.*;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(
    classes = HistoryIntegrationTest.Host.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.flyway.locations=classpath:db/cms",
      "spring.flyway.default-schema=public",
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.jpa.open-in-view=false",
      "cms.cookie.secure=false",
      "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.cms.core.HistoryIntegrationTest$SqlCapture"
    })
class HistoryIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> db =
      new PostgreSQLContainer<>(
              DockerImageName.parse("postgis/postgis:17-3.6").asCompatibleSubstituteFor("postgres"))
          .withPassword(UUID.randomUUID().toString())
          .withTmpFs(Map.of("/docker-entrypoint-initdb.d", "rw"));

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", db::getJdbcUrl);
    r.add("spring.datasource.username", db::getUsername);
    r.add("spring.datasource.password", db::getPassword);
  }

  @Autowired AdminService admins;
  @Autowired RoleService roles;
  @Autowired MenuService menus;
  @Autowired HistoryQueryService history;
  @Autowired HistoryWriter writer;
  @Autowired JdbcTemplate sql;
  @Autowired ObjectMapper json;
  @Autowired PlatformTransactionManager transactions;
  @LocalServerPort int port;
  AdminIdentity root;
  String password;
  static final String CORE = "/admin/api/core";
  final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void setup() {
    sql.execute(
        "TRUNCATE cms.menu_node,cms.role_permission,cms.admin_account_role,cms.admin_account,cms.change_history");
    sql.update("DELETE FROM cms.admin_role WHERE code<>'SYSTEM_ADMIN'");
    sql.update("UPDATE cms.administration_state SET bootstrap_completed=false");
    sql.update("UPDATE cms.menu_tree_revision SET revision=0");
    password = "credential-" + UUID.randomUUID();
    var initial = admins.bootstrap("root", "Root", password);
    admins.changePassword(initial, password, password + "-changed");
    root = admins.authenticate("root", password + "-changed");
    SqlCapture.statements.clear();
  }

  enum Mutation {
    BOOTSTRAP("AdminService.bootstrap/3", "CREATE", "admin-account"),
    ACCOUNT_CREATE("AdminService.createAccount/4", "CREATE", "admin-account"),
    PASSWORD("AdminService.changePassword/3", "PASSWORD_CHANGE", "admin-account"),
    STATUS("AdminService.setActive/3", "STATUS_CHANGE", "admin-account"),
    ROLE_CREATE("RoleService.create/5", "CREATE", "role"),
    ROLE_RENAME("RoleService.rename/4", "RENAME", "role"),
    ROLE_PERMISSIONS("RoleService.permissions/4", "PERMISSIONS_CHANGE", "role"),
    ROLE_DELETE("RoleService.delete/3", "DELETE", "role"),
    ROLE_ASSIGN("RoleService.assign/4", "ROLES_CHANGE", "admin-account"),
    MENU_CREATE("MenuService.create/2", "CREATE", "menu"),
    MENU_UPDATE("MenuService.update/3", "UPDATE", "menu"),
    MENU_MOVE("MenuService.move/3", "MOVE", "menu"),
    MENU_DELETE("MenuService.delete/3", "DELETE", "menu");
    final String method, action, resource;

    Mutation(String method, String action, String resource) {
      this.method = method;
      this.action = action;
      this.resource = resource;
    }
  }

  record Scenario(Runnable run, String actor) {}

  Scenario prepare(Mutation mutation) {
    if (mutation == Mutation.BOOTSTRAP) {
      sql.execute("TRUNCATE cms.admin_account_role,cms.admin_account,cms.change_history");
      sql.update("UPDATE cms.administration_state SET bootstrap_completed=false");
      return new Scenario(() -> admins.bootstrap("initial", "Initial", password), null);
    }
    var member = admins.createAccount(root, "member", "Member", password);
    var role = roles.create(root, "READER", "Reader", "READ_ONLY", Set.of("sample.documents"));
    var first = group("First");
    var second = group("Second");
    Runnable run =
        switch (mutation) {
          case ACCOUNT_CREATE -> () -> admins.createAccount(root, "created", "Created", password);
          case PASSWORD -> () -> admins.changePassword(member, password, password + "-changed");
          case STATUS -> () -> admins.setActive(root, member.id(), false);
          case ROLE_CREATE ->
              () -> roles.create(root, "NEW", "New", "MANAGER", Set.of("sample.documents"));
          case ROLE_RENAME -> () -> roles.rename(root, role.id(), "Renamed", role.version());
          case ROLE_PERMISSIONS ->
              () ->
                  roles.permissions(
                      root, role.id(), Map.of("sample.documents", Action.MANAGE), role.version());
          case ROLE_DELETE -> () -> roles.delete(root, role.id(), role.version());
          case ROLE_ASSIGN ->
              () -> roles.assign(root, member.id(), Set.of(role.id()), member.authVersion());
          case MENU_CREATE -> () -> group("Created");
          case MENU_UPDATE ->
              () ->
                  menus.update(
                      root,
                      first,
                      new MenuService.Details("Updated", null, null, false, revision()));
          case MENU_MOVE ->
              () -> menus.move(root, first, new MenuService.Position(second, null, revision()));
          case MENU_DELETE -> () -> menus.delete(root, first, revision());
          default -> throw new AssertionError();
        };
    return new Scenario(run, mutation == Mutation.PASSWORD ? member.id() : root.id());
  }

  long revision() {
    return sql.queryForObject("SELECT revision FROM cms.menu_tree_revision WHERE id=1", Long.class);
  }

  String group(String title) {
    var ids = menus.list(root).nodes().stream().map(MenuService.Node::id).toList();
    return menus
        .create(
            root,
            new MenuService.Draft(
                title, MenuNode.Kind.GROUP, null, null, null, null, true, revision()))
        .nodes()
        .stream()
        .map(MenuService.Node::id)
        .filter(id -> !ids.contains(id))
        .findFirst()
        .orElseThrow();
  }

  @ParameterizedTest
  @EnumSource(Mutation.class)
  void everySuccessfulWriteHasOneSafeHistory(Mutation mutation) throws Exception {
    var scenario = prepare(mutation);
    var previous = sql.queryForList("SELECT id FROM cms.change_history", String.class);
    scenario.run().run();
    var entries =
        sql.queryForList("SELECT * FROM cms.change_history").stream()
            .filter(row -> !previous.contains(row.get("id")))
            .toList();
    assertThat(entries).hasSize(1);
    var row = entries.getFirst();
    assertThat(row.get("action")).isEqualTo(mutation.action);
    assertThat(row.get("resource_type")).isEqualTo(mutation.resource);
    assertThat(row.get("actor_id")).isEqualTo(scenario.actor());
    assertThat(row.get("payload_version")).isEqualTo(1);
    assertThat(row.get("resource_id")).isNotNull();
    var before = tree(row.get("before_data"));
    var after = tree(row.get("after_data"));
    assertSnapshot(mutation, before, after);
    var hashes = sql.queryForList("SELECT password_hash FROM cms.admin_account", String.class);
    for (var data : List.of(before, after)) {
      String text = data.toString();
      assertThat(text).doesNotContain(password, "$argon2", "{argon2}", "CMSSESSION", "XSRF-TOKEN");
      for (var hash : hashes) assertThat(text).doesNotContain(hash);
      assertSafeKeys(data);
    }
  }

  private JsonNode tree(Object value) throws Exception {
    return value == null ? json.nullNode() : json.readTree(value.toString());
  }

  private void assertSnapshot(Mutation mutation, JsonNode before, JsonNode after) {
    var keys =
        switch (mutation.resource) {
          case "admin-account" ->
              mutation == Mutation.ROLE_ASSIGN
                  ? Set.of("roles")
                  : Set.of("loginId", "displayName", "active", "mustChangePassword", "roles");
          case "role" -> Set.of("code", "name", "permissions");
          case "menu" -> Set.of("node", "sortOrders", "treeRevision");
          default -> throw new AssertionError();
        };
    for (var snapshot : List.of(before, after)) {
      if (snapshot.isNull()) continue;
      var actualKeys = new HashSet<String>();
      snapshot.fieldNames().forEachRemaining(actualKeys::add);
      assertThat(actualKeys).isEqualTo(keys);
    }
    switch (mutation) {
      case BOOTSTRAP, ACCOUNT_CREATE -> {
        assertThat(before.isNull()).isTrue();
        assertThat(after.path("active").asBoolean()).isTrue();
        assertThat(after.path("mustChangePassword").asBoolean()).isTrue();
      }
      case PASSWORD -> {
        assertThat(before.path("mustChangePassword").asBoolean()).isTrue();
        assertThat(after.path("mustChangePassword").asBoolean()).isFalse();
      }
      case STATUS -> {
        assertThat(before.path("active").asBoolean()).isTrue();
        assertThat(after.path("active").asBoolean()).isFalse();
      }
      case ROLE_CREATE -> {
        assertThat(before.isNull()).isTrue();
        assertThat(after.path("permissions").path("sample.documents").asText()).isEqualTo("MANAGE");
      }
      case ROLE_RENAME -> {
        assertThat(before.path("name").asText()).isEqualTo("Reader");
        assertThat(after.path("name").asText()).isEqualTo("Renamed");
      }
      case ROLE_PERMISSIONS -> {
        assertThat(before.path("permissions").path("sample.documents").asText()).isEqualTo("READ");
        assertThat(after.path("permissions").path("sample.documents").asText()).isEqualTo("MANAGE");
      }
      case ROLE_ASSIGN -> {
        assertThat(before.path("roles").isEmpty()).isTrue();
        assertThat(after.path("roles").get(0).asText()).isEqualTo("READER");
      }
      case ROLE_DELETE, MENU_DELETE -> {
        assertThat(before.isObject()).isTrue();
        assertThat(after.isNull()).isTrue();
      }
      case MENU_CREATE -> {
        assertThat(before.isNull()).isTrue();
        assertThat(after.path("node").path("title").asText()).isEqualTo("Created");
      }
      case MENU_UPDATE -> {
        assertThat(before.path("node").path("title").asText()).isEqualTo("First");
        assertThat(after.path("node").path("title").asText()).isEqualTo("Updated");
        assertThat(after.path("node").path("visible").asBoolean()).isFalse();
      }
      case MENU_MOVE -> {
        assertThat(before.path("node").path("parentId").isNull()).isTrue();
        assertThat(after.path("node").path("parentId").isTextual()).isTrue();
        assertThat(after.path("node").path("id")).isEqualTo(before.path("node").path("id"));
      }
    }
  }

  private void assertSafeKeys(JsonNode node) {
    if (node.isObject())
      node.fields()
          .forEachRemaining(
              e -> {
                assertThat(e.getKey().toLowerCase(Locale.ROOT))
                    .isNotIn(
                        "password",
                        "passwordhash",
                        "password_hash",
                        "session",
                        "sessionid",
                        "csrf",
                        "csrftoken",
                        "token",
                        "filebody",
                        "body",
                        "content",
                        "bytes");
                assertSafeKeys(e.getValue());
              });
    if (node.isArray()) node.forEach(this::assertSafeKeys);
  }

  @ParameterizedTest
  @EnumSource(Mutation.class)
  void historyInsertFailureRollsBackEveryBusinessWrite(Mutation mutation) {
    var scenario = prepare(mutation);
    var before = database();
    sql.execute(
        "ALTER TABLE cms.change_history ADD CONSTRAINT fail_history CHECK (false) NOT VALID");
    try {
      assertThatThrownBy(() -> scenario.run().run()).isInstanceOf(RuntimeException.class);
    } finally {
      sql.execute("ALTER TABLE cms.change_history DROP CONSTRAINT fail_history");
    }
    assertThat(database()).isEqualTo(before);
  }

  Map<String, List<String>> database() {
    var result = new TreeMap<String, List<String>>();
    for (var table :
        List.of(
            "admin_account",
            "admin_role",
            "admin_account_role",
            "role_permission",
            "administration_state",
            "menu_node",
            "menu_tree_revision",
            "change_history"))
      result.put(
          table,
          sql.queryForList(
              "SELECT to_jsonb(t)::text FROM cms." + table + " t ORDER BY to_jsonb(t)::text",
              String.class));
    return result;
  }

  @Test
  void serviceInventoryRequiresAnExecutedAuditContractForEveryWrite() throws Exception {
    var reads =
        Set.of(
            "AdminService.normalize/1",
            "AdminService.authenticate/2",
            "AdminService.current/1",
            "AdminService.list/1",
            "RoleService.templates/0",
            "RoleService.list/1",
            "RoleService.get/2",
            "MenuService.list/1",
            "MenuService.navigation/1",
            "MenuService.preview/3",
            "PermissionService.systemAdmin/1",
            "PermissionService.explain/4",
            "PermissionService.require/3",
            "HistoryQueryService.list/2",
            "HistoryQueryService.detail/2",
            "FileService.list/3",
            "FileService.detail/2",
            "FileService.download/2");
    var writes = Arrays.stream(Mutation.values()).map(m -> m.method).collect(Collectors.toSet());
    var methods = new TreeSet<String>();
    var resolver = new PathMatchingResourcePatternResolver();
    for (var resource : resolver.getResources("classpath*:com/cms/core/**/*.class")) {
      String url = resource.getURL().toString();
      if (url.contains("$")) continue;
      String name =
          url.substring(url.indexOf("com/cms/core/")).replace('/', '.').replace(".class", "");
      var type = Class.forName(name);
      if (!type.getSimpleName().endsWith("Service")
          && !type.isAnnotationPresent(org.springframework.stereotype.Service.class)
          && !type.isAnnotationPresent(
              org.springframework.transaction.annotation.Transactional.class)
          && Arrays.stream(type.getDeclaredMethods())
              .noneMatch(
                  m ->
                      m.isAnnotationPresent(
                          org.springframework.transaction.annotation.Transactional.class)))
        continue;
      for (var method : type.getDeclaredMethods())
        if (Modifier.isPublic(method.getModifiers()) && !method.isSynthetic()) {
          String id =
              type.getSimpleName() + "." + method.getName() + "/" + method.getParameterCount();
          assertThat(methods.add(id)).as("Overloads need distinct audit scenarios: " + id).isTrue();
          if (writes.contains(id)) {
            var tx =
                method.getAnnotation(
                    org.springframework.transaction.annotation.Transactional.class);
            assertThat(tx).as(id + " must retain its business transaction").isNotNull();
            assertThat(tx.readOnly()).isFalse();
          }
        }
    }
    var expected = new TreeSet<>(reads);
    expected.addAll(writes);
    // File facades span explicit transactions; their executable contracts test success and
    // rollback.
    expected.addAll(FileServiceIntegrationTest.WRITE_CONTRACTS);
    expected.addAll(Set.of("HistoryWriter.account/5", "HistoryWriter.record/6"));
    assertThat(methods)
        .as(
            "New service methods require an explicit read classification or an executable write scenario")
        .isEqualTo(expected);
  }

  static Stream<Arguments> rejectedPayloads() {
    return Stream.of(
        Arguments.of("admin-account", Map.of("password", "secret")),
        Arguments.of("admin-account", Map.of("passwordHash", "hash")),
        Arguments.of("admin-account", Map.of("sessionId", "session")),
        Arguments.of("admin-account", Map.of("csrfToken", "csrf")),
        Arguments.of("admin-account", Map.of("roles", List.of(Map.of("token", "secret")))),
        Arguments.of("role", Map.of("permissions", Map.of("sample.documents", "secret"))),
        Arguments.of("role", Map.of("name", new byte[] {1, 2, 3})),
        Arguments.of("menu", Map.of("node", Map.of("fileBody", "binary"))),
        Arguments.of("menu", Map.of("sortOrders", Map.of("token", "secret"))),
        Arguments.of("file", Map.of("body", new byte[] {1, 2, 3})),
        Arguments.of("file", Map.of("signedUrl", "https://example.invalid/?signature=secret")),
        Arguments.of("unknown", Map.of("content", "secret")));
  }

  @ParameterizedTest
  @MethodSource("rejectedPayloads")
  void unapprovedFieldsAndNestedObjectsCannotReachJsonb(
      String resource, Map<String, Object> payload) {
    var before = database();
    var tx = new TransactionTemplate(transactions);
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    s -> {
                      sql.update(
                          "UPDATE cms.admin_account SET display_name='Should rollback' WHERE id=?",
                          root.id());
                      writer.record(root.id(), "TEST", resource, root.id(), null, payload);
                    }))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(database()).isEqualTo(before);
  }

  private HistoryQueryService.Filter filter(
      String type, String id, String actor, Instant from, Instant until, String cursor, int size) {
    return new HistoryQueryService.Filter(type, id, actor, from, until, cursor, size);
  }

  @Test
  void filtersCursorTiesDeletionAndUtcRangeAreStable() {
    seed(12);
    var start = Instant.parse("2026-01-01T00:00:00Z");
    var matching =
        history.list(
            root, filter("menu", "resource-0", null, start, start.plusSeconds(12), null, 2));
    assertThat(matching.items()).hasSize(2);
    assertThat(matching.items())
        .allMatch(v -> v.resourceType().equals("menu") && v.resourceId().equals("resource-0"));
    assertThat(history.list(root, filter(null, null, "actor-0", null, null, null, 50)).items())
        .hasSize(1)
        .allMatch(v -> v.actorId().equals("actor-0"));
    var seen = new ArrayList<String>();
    String cursor = null;
    do {
      var page = history.list(root, filter(null, null, null, null, null, cursor, 2));
      seen.addAll(page.items().stream().map(ChangeHistory.View::id).toList());
      cursor = page.nextCursor();
    } while (cursor != null);
    assertThat(seen).hasSize(12).doesNotHaveDuplicates();
    assertThat(seen)
        .containsExactlyElementsOf(
            sql.queryForList(
                "SELECT id FROM cms.change_history ORDER BY occurred_at DESC,id DESC",
                String.class));
    var detail = history.detail(root, seen.getFirst());
    assertThat(detail.resourceId()).startsWith("resource-");
    assertThat(detail.before()).containsEntry("title", "before");
    assertThat(detail.after()).containsEntry("title", "after");
    assertThat(detail.payloadVersion()).isEqualTo(1);
    // A deleted target/actor needs no live FK or current name to remain readable.
    assertThat(
            sql.queryForObject(
                "SELECT count(*) FROM cms.admin_account WHERE id=?", Long.class, detail.actorId()))
        .isZero();
    assertThat(history.list(root, filter("absent", null, null, null, null, null, 50)).items())
        .isEmpty();
    assertThat(
            history
                .list(
                    root,
                    filter(null, null, null, start.plusSeconds(2), start.plusSeconds(4), null, 100))
                .items())
        .allMatch(
            v ->
                !v.occurredAt().isBefore(start.plusSeconds(2))
                    && v.occurredAt().isBefore(start.plusSeconds(4)))
        .hasSize(4);
  }

  @Test
  void malformedFiltersAndMissingDetailAreRejected() {
    for (var f :
        List.of(
            filter(null, null, null, null, null, null, 0),
            filter(null, null, null, null, null, null, 101),
            filter("", null, null, null, null, null, 50),
            filter(null, null, null, Instant.EPOCH, Instant.EPOCH, null, 50),
            filter(null, null, null, null, null, "broken", 50)))
      assertThatThrownBy(() -> history.list(root, f))
          .isInstanceOfSatisfying(AdminProblem.class, e -> assertThat(e.status()).isEqualTo(400));
    assertThatThrownBy(() -> history.detail(root, UUID.randomUUID().toString()))
        .isInstanceOfSatisfying(AdminProblem.class, e -> assertThat(e.status()).isEqualTo(404));
  }

  @Test
  void httpCredentialsAndExtraSecretFieldsNeverEnterHistory() throws Exception {
    String cookies = login("root");
    String csrf =
        Arrays.stream(cookies.split("; "))
            .filter(c -> c.startsWith("XSRF-TOKEN="))
            .findFirst()
            .orElseThrow()
            .substring("XSRF-TOKEN=".length());
    var secrets =
        Map.of(
            "passwordHash",
            "hash-" + UUID.randomUUID(),
            "sessionId",
            "session-" + UUID.randomUUID(),
            "csrfToken",
            "csrf-" + UUID.randomUUID(),
            "fileBody",
            "binary-" + UUID.randomUUID());
    var input = new HashMap<String, Object>(secrets);
    input.put("loginId", "http-created");
    input.put("displayName", "Created via HTTP");
    input.put("password", password);
    input.put("actorId", "forged-actor");
    var response =
        http.send(
            HttpRequest.newBuilder(URI.create(url("/accounts")))
                .header("Cookie", cookies)
                .header("X-XSRF-TOKEN", csrf)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(201);
    String id = json.readTree(response.body()).path("id").asText();
    var row =
        sql.queryForMap(
            "SELECT actor_id,before_data,after_data FROM cms.change_history WHERE resource_id=?",
            id);
    assertThat(row.get("actor_id")).isEqualTo(root.id());
    assertThat(row.get("before_data")).isNull();
    var after = tree(row.get("after_data"));
    assertThat(after.path("displayName").asText()).isEqualTo("Created via HTTP");
    assertSafeKeys(after);
    String text = after.toString();
    assertThat(text).doesNotContain(password, "forged-actor", csrf);
    secrets.values().forEach(secret -> assertThat(text).doesNotContain(secret));
    for (var part : cookies.split("; "))
      assertThat(text).doesNotContain(part.substring(part.indexOf('=') + 1));
    String hash =
        sql.queryForObject(
            "SELECT password_hash FROM cms.admin_account WHERE id=?", String.class, id);
    assertThat(text).doesNotContain(hash);
  }

  @Test
  void onlySystemAdminCanReadListAndDetailThroughHttp() throws Exception {
    var member = admins.createAccount(root, "member", "Member", password);
    admins.changePassword(member, password, password + "-changed");
    var rootCookie = login("root");
    var memberCookie = login("member");
    var id =
        sql.queryForObject(
            "SELECT id FROM cms.change_history ORDER BY occurred_at,id LIMIT 1", String.class);
    for (var path : List.of("/history", "/history/" + id, "/history/absent")) {
      assertThat(get(path, "").statusCode()).isEqualTo(401);
      assertThat(get(path, memberCookie).statusCode()).isEqualTo(403);
    }
    var response = get("/history?size=2", rootCookie);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(json.readTree(response.body()).path("items").size()).isEqualTo(2);
    assertThat(get("/history/" + id, rootCookie).statusCode()).isEqualTo(200);
    for (var query :
        List.of(
            "?size=0",
            "?size=101",
            "?size=bad",
            "?from=bad",
            "?cursor=broken",
            "?from=2026-01-02T00:00:00Z&until=2026-01-01T00:00:00Z"))
      assertThat(get("/history" + query, rootCookie).statusCode()).isEqualTo(400);
    assertThat(get("/history/absent", rootCookie).statusCode()).isEqualTo(404);
    String payloads =
        sql.queryForObject(
            "SELECT jsonb_agg(jsonb_build_array(before_data,after_data))::text FROM cms.change_history",
            String.class);
    for (var cookie : List.of(rootCookie, memberCookie))
      for (var part : cookie.split("; "))
        assertThat(payloads).doesNotContain(part.substring(part.indexOf('=') + 1));
  }

  String login(String name) throws Exception {
    var csrf =
        http.send(
            HttpRequest.newBuilder(URI.create(url("/auth/csrf"))).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    String token = cookie(csrf, "XSRF-TOKEN"), session = cookie(csrf, "CMSSESSION");
    var response =
        http.send(
            HttpRequest.newBuilder(URI.create(url("/auth/login")))
                .header("Cookie", session + "; " + token)
                .header("X-XSRF-TOKEN", token.substring(token.indexOf('=') + 1))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        json.writeValueAsString(
                            Map.of("loginId", name, "password", password + "-changed"))))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    return cookie(response, "CMSSESSION") + "; " + cookie(response, "XSRF-TOKEN");
  }

  String cookie(HttpResponse<?> response, String name) {
    return response.headers().allValues("set-cookie").stream()
        .filter(c -> c.startsWith(name + "=") && !c.startsWith(name + "=;"))
        .map(c -> c.split(";", 2)[0])
        .reduce((a, b) -> b)
        .orElseThrow();
  }

  String url(String path) {
    return "http://localhost:" + port + CORE + path;
  }

  HttpResponse<String> get(String path, String cookie) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create(url(path))).header("Cookie", cookie).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  void seed(int count) {
    sql.execute("TRUNCATE cms.change_history");
    sql.update(
        """
      INSERT INTO cms.change_history(id,actor_id,action,resource_type,resource_id,occurred_at,before_data,after_data,payload_version)
      SELECT md5(i::text)::uuid::text,'actor-'||(i%100),'UPDATE',
             CASE WHEN i%2=0 THEN 'menu' ELSE 'role' END,'resource-'||(i%10),
             timestamp '2026-01-01 00:00:00'+(i/2)*interval '1 second',
             '{"title":"before"}'::jsonb,'{"title":"after"}'::jsonb,1
      FROM generate_series(0,?-1) i
      """,
        count);
    sql.execute("ANALYZE cms.change_history");
  }

  @Test
  void recordsActualQueryPlansWithTenThousandRows() throws Exception {
    seed(10_000);
    var cases = new LinkedHashMap<String, HistoryQueryService.Filter>();
    cases.put("recent", filter(null, null, null, null, null, null, 50));
    cases.put("target", filter("menu", "resource-0", null, null, null, null, 50));
    cases.put("actor", filter(null, null, "actor-0", null, null, null, 50));
    cases.put(
        "period",
        filter(
            null,
            null,
            null,
            Instant.parse("2026-01-01T00:10:00Z"),
            Instant.parse("2026-01-01T00:11:00Z"),
            null,
            50));
    var report =
        new StringBuilder(
            "PostgreSQL "
                + sql.queryForObject("SHOW server_version", String.class)
                + "; rows=10000; ANALYZE; default planner; size=50 (fetch 51)\n");
    for (var entry : cases.entrySet()) {
      SqlCapture.statements.clear();
      var result = history.list(root, entry.getValue());
      assertThat(result.items()).hasSize(50);
      String actual =
          SqlCapture.statements.stream()
              .filter(s -> s.startsWith("SELECT h.* FROM cms.change_history"))
              .findFirst()
              .orElseThrow();
      Object[] bindings =
          switch (entry.getKey()) {
            case "recent" -> new Object[] {51};
            case "target" -> new Object[] {"menu", "resource-0", 51};
            case "actor" -> new Object[] {"actor-0", 51};
            case "period" ->
                new Object[] {
                  java.sql.Timestamp.valueOf("2026-01-01 00:10:00"),
                  java.sql.Timestamp.valueOf("2026-01-01 00:11:00"),
                  51
                };
            default -> throw new AssertionError();
          };
      var plan =
          sql.queryForList(
              "EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) " + actual, String.class, bindings);
      report
          .append("\n")
          .append(entry.getKey())
          .append("\n")
          .append(actual)
          .append("\nbindings=")
          .append(Arrays.toString(bindings))
          .append("\n")
          .append(String.join("\n", plan))
          .append("\n");
      assertThat(plan).anyMatch(line -> line.contains("Scan"));
    }
    var path = Path.of("build/reports/history-query-plans.txt");
    Files.createDirectories(path.getParent());
    Files.writeString(path, report);
    System.out.println("HISTORY_EXPLAIN rows=10000 report=" + path.toAbsolutePath());
  }

  public static class SqlCapture implements StatementInspector {
    static final List<String> statements = new CopyOnWriteArrayList<>();

    public String inspect(String sql) {
      statements.add(sql);
      return sql;
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import(CmsCoreConfiguration.class)
  static class Host {
    @Bean
    PermissionCatalog.Contribution sample() {
      return () ->
          List.of(
              new PermissionCatalog.Definition(
                  "sample.documents", "Documents", Set.of(Action.READ, Action.MANAGE), false));
    }
  }
}
