package com.cms.core;

import static org.assertj.core.api.Assertions.*;

import com.cms.core.account.*;
import com.cms.core.menu.*;
import com.cms.core.permission.*;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.*;
import org.springframework.web.bind.annotation.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(
    classes = GovernanceIntegrationTest.Host.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.flyway.locations=classpath:db/cms",
      "spring.flyway.default-schema=public",
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.jpa.open-in-view=false",
      "cms.cookie.secure=false"
    })
class GovernanceIntegrationTest {
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
  @Autowired PermissionService access;
  @Autowired JdbcTemplate sql;
  @Autowired DataSource source;
  @Autowired ObjectMapper json;
  @Autowired AdminSecurityIntegrationTest.MutableClock clock;
  @LocalServerPort int port;
  AdminIdentity root;
  String password;
  final Map<String, String> passwords = new HashMap<>();
  final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  static final String CORE = "/admin/api/core";

  @BeforeEach
  void setup() {
    clock.advance(Duration.ofDays(1));
    passwords.clear();
    password = UUID.randomUUID().toString();
    sql.execute("CREATE SCHEMA IF NOT EXISTS sample");
    sql.execute(
        "CREATE TABLE IF NOT EXISTS sample.document (id text PRIMARY KEY,menu_id text REFERENCES cms.menu_node(id),body text)");
    sql.execute(
        "TRUNCATE sample.document,cms.menu_node,cms.role_permission,cms.admin_account_role,cms.admin_account,cms.change_history");
    sql.update("DELETE FROM cms.admin_role WHERE code <> 'SYSTEM_ADMIN'");
    sql.update("UPDATE cms.administration_state SET bootstrap_completed=false");
    sql.update("UPDATE cms.menu_tree_revision SET revision=0");
    var initial = admins.bootstrap("root", "Root", password);
    admins.changePassword(initial, password, password + "-new");
    root = admins.authenticate("root", password + "-new");
    passwords.put("root", password + "-new");
  }

  long revision() {
    return sql.queryForObject("SELECT revision FROM cms.menu_tree_revision WHERE id=1", Long.class);
  }

  long count(String table) {
    return sql.queryForObject("SELECT count(*) FROM " + table, Long.class);
  }

  String group(String title, String parent) {
    var before = menus.list(root).nodes().stream().map(MenuService.Node::id).toList();
    return menus
        .create(
            root,
            new MenuService.Draft(
                title, MenuNode.Kind.GROUP, parent, null, null, null, true, revision()))
        .nodes()
        .stream()
        .map(MenuService.Node::id)
        .filter(id -> !before.contains(id))
        .findFirst()
        .orElseThrow();
  }

  String page(String title, String parent) {
    var before = menus.list(root).nodes().stream().map(MenuService.Node::id).toList();
    return menus
        .create(
            root,
            new MenuService.Draft(
                title,
                MenuNode.Kind.PAGE,
                parent,
                null,
                "documents.list",
                "sample.documents",
                true,
                revision()))
        .nodes()
        .stream()
        .map(MenuService.Node::id)
        .filter(id -> !before.contains(id))
        .findFirst()
        .orElseThrow();
  }

  AdminIdentity member(String name) {
    var initial = admins.createAccount(root, name, name, password);
    admins.changePassword(initial, password, password + "-new");
    passwords.put(name, password + "-new");
    return admins.authenticate(name, password + "-new");
  }

  RoleService.RoleView role(String code, String template, String... keys) {
    return roles.create(root, code, code, template, Set.of(keys));
  }

  AdminIdentity assigned(AdminIdentity member, RoleService.RoleView... granted) {
    return roles.assign(
        root,
        member.id(),
        Arrays.stream(granted)
            .map(RoleService.RoleView::id)
            .collect(java.util.stream.Collectors.toSet()),
        member.authVersion());
  }

  MenuService.Node node(String id) {
    return menus.list(root).nodes().stream()
        .filter(n -> n.id().equals(id))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void templatesCopyOnceAndUnionExplainsManageIncludingRead() {
    var reader = role("READER", "READ_ONLY", "sample.documents");
    var writer = role("WRITER", "MANAGER", "sample.documents");
    var member = assigned(member("operator"), reader, writer);
    var explanation = access.explain(member, member.id(), "sample.documents", Action.READ);
    assertThat(explanation.allowed()).isTrue();
    assertThat(explanation.reason()).isEqualTo("ROLE_GRANT");
    assertThat(explanation.grants())
        .extracting(PermissionService.Grant::roleCode)
        .containsExactly("READER", "WRITER");
    assertThat(explanation.grants())
        .filteredOn(g -> g.roleCode().equals("WRITER"))
        .allMatch(PermissionService.Grant::impliesRead);
    roles.permissions(root, reader.id(), Map.of(), reader.version());
    assertThat(roles.get(root, writer.id()).permissions())
        .containsEntry("sample.documents", Action.MANAGE);
    assertThat(roles.templates()).contains(new RoleService.Template("READ_ONLY", Action.READ));
    assertThat(access.explain(member, member.id(), "sample.documents", Action.MANAGE).allowed())
        .isTrue();
    assertThat(access.explain(member, member.id(), "sample.archive", Action.READ).allowed())
        .isFalse();
  }

  @Test
  void unknownUnsupportedAndReservedGrantsAreRejectedWithoutWrites() {
    var role = role("EMPTY", null);
    long history = count("cms.change_history");
    for (var grant :
        List.of(
            Map.of("unknown", Action.READ),
            Map.of("sample.archive", Action.MANAGE),
            Map.of("cms.roles", Action.MANAGE)))
      assertThatThrownBy(() -> roles.permissions(root, role.id(), grant, role.version()))
          .isInstanceOf(AdminProblem.class);
    assertThatThrownBy(
            () -> roles.create(root, "BAD", "Bad", "not-a-template", Set.of("sample.documents")))
        .isInstanceOf(AdminProblem.class);
    assertThat(roles.get(root, role.id()).permissions()).isEmpty();
    assertThat(count("cms.change_history")).isEqualTo(history);
  }

  @Test
  void roleCrudProtectsAssignedAndSystemRolesAndStaleVersions() {
    var role = role("EDIT", null);
    var renamed = roles.rename(root, role.id(), "Renamed", role.version());
    assertThat(renamed.version()).isGreaterThan(role.version());
    assertThatThrownBy(() -> roles.permissions(root, role.id(), Map.of(), role.version()))
        .isInstanceOf(AdminProblem.class);
    var member = assigned(member("operator"), renamed);
    assertThatThrownBy(() -> roles.delete(root, role.id(), renamed.version()))
        .isInstanceOf(AdminProblem.class);
    roles.assign(root, member.id(), Set.of(), member.authVersion());
    roles.delete(root, role.id(), renamed.version());
    assertThatThrownBy(() -> roles.get(root, role.id())).isInstanceOf(AdminProblem.class);
    var system =
        roles.list(root).stream()
            .filter(RoleService.RoleView::systemProtected)
            .findFirst()
            .orElseThrow();
    assertThatThrownBy(() -> roles.permissions(root, system.id(), Map.of(), system.version()))
        .isInstanceOf(AdminProblem.class);
    assertThatThrownBy(() -> roles.delete(root, system.id(), system.version()))
        .isInstanceOf(AdminProblem.class);
  }

  @Test
  void ordinaryAdminsCanExplainOnlyThemselvesAndCannotManageSecurity() throws Exception {
    var member = assigned(member("reader"), role("READ", "READ_ONLY", "sample.documents"));
    var session = login(member.loginId());
    assertThat(
            request(
                    "GET",
                    CORE + "/permissions/explain?permissionKey=sample.documents&action=READ",
                    null,
                    session)
                .body
                .path("allowed")
                .asBoolean())
        .isTrue();
    assertThat(
            request(
                    "GET",
                    CORE
                        + "/permissions/explain?permissionKey=sample.documents&action=READ&accountId="
                        + root.id(),
                    null,
                    session)
                .status)
        .isEqualTo(403);
    for (var path : List.of("/roles", "/role-templates", "/permissions", "/accounts"))
      assertThat(request("GET", CORE + path, null, session).status).as(path).isEqualTo(403);
    assertThat(
            request(
                    "POST",
                    CORE + "/roles",
                    Map.of("code", "ESCALATE", "name", "Escalate"),
                    session)
                .status)
        .isEqualTo(403);
    assertThat(
            request(
                    "PUT",
                    CORE + "/accounts/" + member.id() + "/roles",
                    Map.of("roleIds", List.of(), "authVersion", member.authVersion()),
                    session)
                .status)
        .isEqualTo(403);
  }

  @Test
  void roleAndMenuHttpContractsSupportCreateUpdatePreviewAndDelete() throws Exception {
    var session = login("root");
    var created =
        request(
            "POST",
            CORE + "/roles",
            Map.of(
                "code",
                "HTTP_ROLE",
                "name",
                "HTTP role",
                "template",
                "READ_ONLY",
                "permissionKeys",
                List.of("sample.documents")),
            session);
    assertThat(created.status).isEqualTo(201);
    String roleId = created.body.path("id").asText();
    long version = created.body.path("version").asLong();
    var permissions =
        request(
            "PUT",
            CORE + "/roles/" + roleId + "/permissions",
            Map.of("permissions", Map.of("sample.documents", "MANAGE"), "version", version),
            session);
    assertThat(permissions.status).isEqualTo(200);
    var renamed =
        request(
            "PUT",
            CORE + "/roles/" + roleId,
            Map.of("name", "Renamed", "version", permissions.body.path("version").asLong()),
            session);
    assertThat(renamed.status).isEqualTo(200);
    assertThat(request("GET", CORE + "/roles/" + roleId, null, session).body.path("name").asText())
        .isEqualTo("Renamed");
    var group =
        request(
            "POST",
            CORE + "/menus",
            Map.of("title", "HTTP group", "type", "GROUP", "visible", true, "revision", revision()),
            session);
    assertThat(group.status).isEqualTo(201);
    String groupId = group.body.path("nodes").get(0).path("id").asText();
    var page =
        request(
            "POST",
            CORE + "/menus",
            Map.of(
                "title",
                "HTTP page",
                "type",
                "PAGE",
                "parentId",
                groupId,
                "routeKey",
                "documents.list",
                "permissionKey",
                "sample.documents",
                "visible",
                true,
                "revision",
                revision()),
            session);
    assertThat(page.status).isEqualTo(201);
    String pageId =
        menus.list(root).nodes().stream()
            .filter(n -> n.type() == MenuNode.Kind.PAGE)
            .findFirst()
            .orElseThrow()
            .id();
    var preview =
        request(
            "POST",
            CORE + "/menus/" + pageId + "/move-preview",
            Map.of("revision", revision()),
            session);
    assertThat(preview.status).isEqualTo(200);
    assertThat(preview.body.path("permissionsChanged").asBoolean()).isFalse();
    assertThat(
            request(
                    "PUT",
                    CORE + "/menus/" + pageId,
                    Map.of(
                        "title",
                        "Updated",
                        "routeKey",
                        "documents.list",
                        "permissionKey",
                        "sample.documents",
                        "visible",
                        false,
                        "revision",
                        revision()),
                    session)
                .status)
        .isEqualTo(200);
    assertThat(
            request("DELETE", CORE + "/menus/" + pageId + "?revision=" + revision(), null, session)
                .status)
        .isEqualTo(200);
    assertThat(
            request("DELETE", CORE + "/menus/" + groupId + "?revision=" + revision(), null, session)
                .status)
        .isEqualTo(200);
    assertThat(
            request(
                    "DELETE",
                    CORE + "/roles/" + roleId + "?version=" + renamed.body.path("version").asLong(),
                    null,
                    session)
                .status)
        .isEqualTo(204);
    assertThat(menus.list(root).nodes()).isEmpty();
    assertThat(request("GET", CORE + "/menus", null, new Session("", "")).status).isEqualTo(401);
  }

  @Test
  void permissionRevocationAffectsNextRequestWithoutRelogin() throws Exception {
    var writer = role("WRITE", "MANAGER", "sample.documents");
    var member = assigned(member("writer"), writer);
    var session = login(member.loginId());
    assertThat(request("POST", "/admin/api/sample/documents", Map.of(), session).status)
        .isEqualTo(200);
    var updated =
        roles.permissions(
            root, writer.id(), Map.of("sample.documents", Action.READ), writer.version());
    assertThat(updated.version()).isGreaterThan(writer.version());
    assertThat(request("POST", "/admin/api/sample/documents", Map.of(), session).status)
        .isEqualTo(403);
    assertThat(request("GET", "/admin/api/sample/documents", null, session).status).isEqualTo(200);
    admins.setActive(root, member.id(), false);
    assertThat(access.explain(root, member.id(), "sample.documents", Action.READ).reason())
        .isEqualTo("ACCOUNT_INACTIVE");
    assertThat(request("GET", "/admin/api/sample/documents", null, session).status).isEqualTo(401);
  }

  @Test
  void subtreeMovePreservesIdsBusinessPermissionHistoryReferencesAndExplanation() throws Exception {
    String a = group("A", null),
        b = group("B", null),
        child = group("Child", a),
        leaf = page("Document", child);
    sql.update("INSERT INTO sample.document VALUES (?,?,?)", "document-1", leaf, "unchanged");
    var reader = role("READER", "READ_ONLY", "sample.documents");
    var member = assigned(member("reader"), reader);
    var before = menus.list(root);
    var explain = access.explain(member, member.id(), "sample.documents", Action.READ);
    var grants =
        sql.queryForList("SELECT * FROM cms.role_permission ORDER BY role_id,permission_key");
    var history =
        sql.queryForList(
            "SELECT id,resource_id,before_data::text,after_data::text FROM cms.change_history ORDER BY id");
    var preview = menus.preview(root, a, new MenuService.Position(b, null, before.revision()));
    assertThat(preview.permissionsChanged()).isFalse();
    assertThat(preview.message()).isEqualTo("권한 변화 없음");
    assertThat(menus.list(root)).isEqualTo(before);
    var after = menus.move(root, a, new MenuService.Position(b, null, before.revision()));
    assertThat(after.nodes())
        .extracting(MenuService.Node::id)
        .containsExactlyInAnyOrderElementsOf(
            before.nodes().stream().map(MenuService.Node::id).toList());
    assertThat(node(a).parentId()).isEqualTo(b);
    assertThat(node(child))
        .isEqualTo(
            before.nodes().stream().filter(n -> n.id().equals(child)).findFirst().orElseThrow());
    assertThat(node(leaf))
        .isEqualTo(
            before.nodes().stream().filter(n -> n.id().equals(leaf)).findFirst().orElseThrow());
    assertThat(
            sql.queryForObject(
                "SELECT menu_id FROM sample.document WHERE id='document-1'", String.class))
        .isEqualTo(leaf);
    assertThat(
            sql.queryForObject(
                "SELECT body FROM sample.document WHERE id='document-1'", String.class))
        .isEqualTo("unchanged");
    assertThat(
            sql.queryForList("SELECT * FROM cms.role_permission ORDER BY role_id,permission_key"))
        .isEqualTo(grants);
    var now =
        sql.queryForList(
            "SELECT id,resource_id,before_data::text,after_data::text FROM cms.change_history ORDER BY id");
    assertThat(now).containsAll(history);
    assertThat(now).hasSize(history.size() + 1);
    assertThat(access.explain(member, member.id(), "sample.documents", Action.READ))
        .isEqualTo(explain);
    assertThat(menus.navigation(member).nodes())
        .extracting(MenuService.Node::id)
        .containsExactlyInAnyOrder(a, b, child, leaf);
  }

  @Test
  void sortingMovesToRootAndRejectsCyclesStaleRevisionAndWrongSiblings() {
    String a = group("A", null),
        b = group("B", null),
        child = group("Child", a),
        leaf = page("Leaf", child);
    menus.move(root, b, new MenuService.Position(null, a, revision()));
    assertThat(node(b).sortOrder()).isZero();
    assertThat(node(a).sortOrder()).isEqualTo(1);
    var before = menus.list(root);
    for (var move :
        List.of(
            new MenuService.Position(a, null, revision()),
            new MenuService.Position(child, null, revision()),
            new MenuService.Position(b, leaf, revision()),
            new MenuService.Position(null, null, revision() - 1)))
      assertThatThrownBy(() -> menus.move(root, a, move)).isInstanceOf(AdminProblem.class);
    assertThat(menus.list(root)).isEqualTo(before);
    menus.move(root, child, new MenuService.Position(null, null, revision()));
    assertThat(node(child).parentId()).isNull();
    assertThat(node(leaf).parentId()).isEqualTo(child);
    assertThatThrownBy(() -> menus.delete(root, child, revision()))
        .isInstanceOf(AdminProblem.class);
    menus.delete(root, leaf, revision());
    menus.delete(root, child, revision());
    assertThat(menus.list(root).nodes()).hasSize(2);
  }

  @Test
  void hiddenNavigationDoesNotRevokeBusinessAccessAndGroupsGrantNothing() throws Exception {
    String group = group("Group", null), leaf = page("Leaf", group), empty = group("Empty", null);
    var reader = assigned(member("reader"), role("READ", "READ_ONLY", "sample.documents"));
    var noRole = member("none");
    assertThat(menus.navigation(noRole).nodes()).isEmpty();
    assertThat(menus.navigation(reader).nodes())
        .extracting(MenuService.Node::id)
        .containsExactlyInAnyOrder(group, leaf);
    menus.update(root, group, new MenuService.Details("Hidden", null, null, false, revision()));
    assertThat(menus.navigation(reader).nodes()).isEmpty();
    assertThat(request("GET", "/admin/api/sample/documents", null, login(reader.loginId())).status)
        .isEqualTo(200);
    assertThat(node(empty).id()).isEqualTo(empty);
  }

  @Test
  void menuEditorsCanMoveButCannotGrantSecurityPrivileges() throws Exception {
    String a = group("A", null), b = group("B", null);
    var editor = assigned(member("editor"), role("MENU_EDITOR", "MANAGER", "cms.menus"));
    var session = login(editor.loginId());
    assertThat(
            request(
                    "PUT",
                    CORE + "/menus/" + a + "/position",
                    Map.of("parentId", b, "revision", revision()),
                    session)
                .status)
        .isEqualTo(200);
    assertThat(request("GET", CORE + "/roles", null, session).status).isEqualTo(403);
  }

  @Test
  void invalidMenuInputsAndMissingCsrfLeaveTreeUnchanged() throws Exception {
    String page = page("Page", null);
    var before = menus.list(root);
    assertThatThrownBy(() -> group("Child", page)).isInstanceOf(AdminProblem.class);
    assertThatThrownBy(
            () ->
                menus.create(
                    root,
                    new MenuService.Draft(
                        "Bad",
                        MenuNode.Kind.PAGE,
                        null,
                        null,
                        "external.url",
                        "sample.documents",
                        true,
                        revision())))
        .isInstanceOf(AdminProblem.class);
    assertThatThrownBy(
            () -> menus.move(root, page, new MenuService.Position("absent", null, revision())))
        .isInstanceOf(AdminProblem.class);
    var session = login("root");
    var invalid = new Session(session.cookie, "wrong");
    assertThat(
            request("DELETE", CORE + "/menus/" + page + "?revision=" + revision(), null, invalid)
                .status)
        .isEqualTo(403);
    assertThat(
            request(
                    "GET",
                    CORE + "/permissions/explain?permissionKey=sample.documents&action=DELETE",
                    null,
                    session)
                .status)
        .isEqualTo(400);
    assertThat(menus.list(root)).isEqualTo(before);
  }

  @Test
  void historyFailureRollsBackTreeOrderAndRevision() {
    String a = group("A", null), b = group("B", null);
    var before = menus.list(root);
    long histories = count("cms.change_history");
    sql.execute(
        "ALTER TABLE cms.change_history ADD CONSTRAINT fail_move CHECK (action <> 'MOVE') NOT VALID");
    try {
      assertThatThrownBy(() -> menus.move(root, a, new MenuService.Position(b, null, revision())))
          .isInstanceOf(RuntimeException.class);
    } finally {
      sql.execute("ALTER TABLE cms.change_history DROP CONSTRAINT fail_move");
    }
    assertThat(menus.list(root)).isEqualTo(before);
    assertThat(count("cms.change_history")).isEqualTo(histories);
  }

  @Test
  void rolePermissionFailureRollsBackRoleAndHistory() {
    var role = role("READ", "READ_ONLY", "sample.documents");
    long histories = count("cms.change_history");
    sql.execute(
        "ALTER TABLE cms.change_history ADD CONSTRAINT fail_role CHECK (action <> 'PERMISSIONS_CHANGE') NOT VALID");
    try {
      assertThatThrownBy(
              () ->
                  roles.permissions(
                      root, role.id(), Map.of("sample.documents", Action.MANAGE), role.version()))
          .isInstanceOf(RuntimeException.class);
    } finally {
      sql.execute("ALTER TABLE cms.change_history DROP CONSTRAINT fail_role");
    }
    assertThat(roles.get(root, role.id())).isEqualTo(role);
    assertThat(count("cms.change_history")).isEqualTo(histories);
  }

  @Test
  void concurrentCrossMovesWithoutLockProduceCycleButRealHttpWithLockAndRevisionDoNot()
      throws Exception {
    int unsafeCycles = 0,
        unsafeSuccess = 0,
        unsafeNodes = 0,
        safeCycles = 0,
        safeSuccess = 0,
        conflicts = 0;
    var session = login("root");
    for (int round = 0; round < 5; round++) {
      sql.execute("TRUNCATE sample.document,cms.menu_node");
      sql.update("UPDATE cms.menu_tree_revision SET revision=0");
      String a = group("A", null), b = group("B", null);
      var checked = new CyclicBarrier(2);
      try (var pool = Executors.newFixedThreadPool(2)) {
        var first =
            pool.submit(
                () -> {
                  unsafeMove(a, b, checked);
                  return null;
                });
        var second =
            pool.submit(
                () -> {
                  unsafeMove(b, a, checked);
                  return null;
                });
        first.get(15, TimeUnit.SECONDS);
        second.get(15, TimeUnit.SECONDS);
        unsafeSuccess += 2;
      }
      int observedUnsafeNodes = cyclicNodes();
      assertThat(observedUnsafeNodes).isEqualTo(2);
      unsafeNodes += observedUnsafeNodes;
      if (observedUnsafeNodes > 0) unsafeCycles++;
      sql.update("UPDATE cms.menu_node SET parent_id=NULL");
      long rev = revision();
      var responses =
          DatabaseRequestRace.run(
              source,
              "cms.menu_tree_revision",
              () ->
                  request(
                      "PUT",
                      CORE + "/menus/" + a + "/position",
                      Map.of("parentId", b, "revision", rev),
                      session),
              () ->
                  request(
                      "PUT",
                      CORE + "/menus/" + b + "/position",
                      Map.of("parentId", a, "revision", rev),
                      session));
      assertThat(responses).extracting(Response::status).containsExactlyInAnyOrder(200, 409);
      safeSuccess += (int) responses.stream().filter(x -> x.status == 200).count();
      conflicts += (int) responses.stream().filter(x -> x.status == 409).count();
      if (cyclicNodes() > 0) safeCycles++;
      assertThat(revision()).isEqualTo(rev + 1);
    }
    assertThat(unsafeCycles).isEqualTo(5);
    assertThat(safeCycles).isZero();
    System.out.printf(
        "MENU_CROSS_RACE rounds=5 unsafe_success=%d unsafe_cycle_rounds=%d unsafe_cyclic_nodes_total=%d safe_success=%d safe_conflict=%d safe_cycle_rounds=%d%n",
        unsafeSuccess, unsafeCycles, unsafeNodes, safeSuccess, conflicts, safeCycles);
  }

  private void unsafeMove(String id, String parent, CyclicBarrier checked) throws Exception {
    try (var connection = source.getConnection()) {
      connection.setAutoCommit(false);
      try (var query =
          connection.prepareStatement(
              "WITH RECURSIVE ancestors AS (SELECT id,parent_id FROM cms.menu_node WHERE id=? UNION ALL SELECT n.id,n.parent_id FROM cms.menu_node n JOIN ancestors a ON n.id=a.parent_id) SELECT count(*) FROM ancestors WHERE id=?")) {
        query.setString(1, parent);
        query.setString(2, id);
        try (var rows = query.executeQuery()) {
          rows.next();
          assertThat(rows.getInt(1)).isZero();
        }
      }
      checked.await(10, TimeUnit.SECONDS);
      try (var update =
          connection.prepareStatement("UPDATE cms.menu_node SET parent_id=? WHERE id=?")) {
        update.setString(1, parent);
        update.setString(2, id);
        assertThat(update.executeUpdate()).isEqualTo(1);
      }
      connection.commit();
    }
  }

  private int cyclicNodes() {
    return sql.queryForObject(
        """
       WITH RECURSIVE walk(start,id,parent_id,path,cycle) AS (
         SELECT id,id,parent_id,ARRAY[id],false FROM cms.menu_node
         UNION ALL
         SELECT w.start,n.id,n.parent_id,w.path||n.id,n.id=ANY(w.path)
         FROM walk w JOIN cms.menu_node n ON n.id=w.parent_id WHERE NOT w.cycle)
       SELECT count(DISTINCT start)::integer FROM walk WHERE cycle
       """,
        Integer.class);
  }

  @Test
  void concurrentSiblingSortAndMoveVersusDeleteRejectStaleEditors() throws Exception {
    String a = group("A", null), b = group("B", null);
    long rev = revision();
    var session = login("root");
    var sorted =
        DatabaseRequestRace.run(
            source,
            "cms.menu_tree_revision",
            () ->
                request(
                    "PUT",
                    CORE + "/menus/" + a + "/position",
                    Map.of("beforeSiblingId", b, "revision", rev),
                    session),
            () ->
                request(
                    "PUT",
                    CORE + "/menus/" + b + "/position",
                    Map.of("beforeSiblingId", a, "revision", rev),
                    session));
    assertThat(sorted).extracting(Response::status).containsExactlyInAnyOrder(200, 409);
    assertThat(menus.list(root).nodes())
        .extracting(MenuService.Node::sortOrder)
        .containsExactlyInAnyOrder(0, 1);
    String leaf = page("Leaf", a);
    long next = revision();
    var moved =
        DatabaseRequestRace.run(
            source,
            "cms.menu_tree_revision",
            () ->
                request(
                    "PUT",
                    CORE + "/menus/" + leaf + "/position",
                    Map.of("parentId", b, "revision", next),
                    session),
            () -> request("DELETE", CORE + "/menus/" + leaf + "?revision=" + next, null, session));
    assertThat(moved).extracting(Response::status).containsExactlyInAnyOrder(200, 409);
    assertThat(cyclicNodes()).isZero();
  }

  @Test
  void concurrentDemotionKeepsOneActiveSystemAdministrator() throws Exception {
    var system =
        roles.list(root).stream()
            .filter(RoleService.RoleView::systemProtected)
            .findFirst()
            .orElseThrow();
    var second = assigned(member("second"), system);
    var firstSession = login("root");
    var secondSession = login("second");
    var responses =
        DatabaseRequestRace.run(
            source,
            "cms.administration_state",
            () ->
                request(
                    "PUT",
                    CORE + "/accounts/" + root.id() + "/roles",
                    Map.of("roleIds", List.of(), "authVersion", root.authVersion()),
                    firstSession),
            () ->
                request(
                    "PUT",
                    CORE + "/accounts/" + second.id() + "/roles",
                    Map.of("roleIds", List.of(), "authVersion", second.authVersion()),
                    secondSession));
    assertThat(responses).extracting(Response::status).containsExactlyInAnyOrder(200, 409);
    assertThat(
            sql.queryForObject(
                "SELECT count(*) FROM cms.admin_account a JOIN cms.admin_account_role ar ON ar.account_id=a.id JOIN cms.admin_role r ON r.id=ar.role_id WHERE a.active AND r.code='SYSTEM_ADMIN'",
                Integer.class))
        .isEqualTo(1);
  }

  record Session(String cookie, String csrf) {}

  record Response(int status, JsonNode body) {}

  Session login(String name) throws Exception {
    var csrf =
        http.send(
            HttpRequest.newBuilder(URI.create(url(CORE + "/auth/csrf"))).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    String token = cookie(csrf, "XSRF-TOKEN"), session = cookie(csrf, "CMSSESSION");
    var response =
        http.send(
            HttpRequest.newBuilder(URI.create(url(CORE + "/auth/login")))
                .header("Cookie", session + "; " + token)
                .header("X-XSRF-TOKEN", token.substring(token.indexOf('=') + 1))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        json.writeValueAsString(
                            Map.of("loginId", name, "password", passwords.get(name)))))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    token = cookie(response, "XSRF-TOKEN");
    return new Session(
        cookie(response, "CMSSESSION") + "; " + token, token.substring(token.indexOf('=') + 1));
  }

  private String cookie(HttpResponse<?> response, String name) {
    return response.headers().allValues("set-cookie").stream()
        .filter(c -> c.startsWith(name + "=") && !c.startsWith(name + "=;"))
        .map(c -> c.split(";", 2)[0])
        .reduce((a, b) -> b)
        .orElseThrow();
  }

  Response request(String method, String path, Object body, Session session) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create(url(path)))
            .timeout(Duration.ofSeconds(25))
            .header("Cookie", session.cookie)
            .header("X-XSRF-TOKEN", session.csrf)
            .header("Content-Type", "application/json");
    var response =
        http.send(
            builder
                .method(
                    method,
                    body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    return new Response(
        response.statusCode(),
        response.body().isBlank() ? json.nullNode() : json.readTree(response.body()));
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }

  @RestController
  static class SampleController {
    private final PermissionService access;

    SampleController(PermissionService access) {
      this.access = access;
    }

    @GetMapping("/admin/api/sample/documents")
    Map<String, Object> read(@AuthenticationPrincipal AdminIdentity actor) {
      access.require(actor, "sample.documents", Action.READ);
      return Map.of("ok", true);
    }

    @PostMapping("/admin/api/sample/documents")
    Map<String, Object> write(@AuthenticationPrincipal AdminIdentity actor) {
      access.require(actor, "sample.documents", Action.MANAGE);
      return Map.of("ok", true);
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import({CmsCoreConfiguration.class, SampleController.class})
  static class Host {
    @Bean
    @Primary
    AdminSecurityIntegrationTest.MutableClock testClock() {
      return new AdminSecurityIntegrationTest.MutableClock();
    }

    @Bean
    PermissionCatalog.Contribution sample() {
      return new PermissionCatalog.Contribution() {
        public Collection<PermissionCatalog.Definition> permissions() {
          return List.of(
              new PermissionCatalog.Definition(
                  "sample.documents",
                  "Sample documents",
                  Set.of(Action.READ, Action.MANAGE),
                  false),
              new PermissionCatalog.Definition(
                  "sample.archive", "Archive", Set.of(Action.READ), false));
        }

        public Collection<PermissionCatalog.Screen> screens() {
          return List.of(new PermissionCatalog.Screen("documents.list", "sample.documents"));
        }
      };
    }
  }
}
