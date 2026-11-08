package com.cms.core;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cms.core.account.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(
    classes = AdminSecurityIntegrationTest.Host.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.flyway.locations=classpath:db/cms",
      "spring.flyway.default-schema=public",
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.jpa.open-in-view=false",
      "cms.cookie.secure=false"
    })
class AdminSecurityIntegrationTest {
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

  @Autowired MockMvc mvc;
  @Autowired AdminService admins;
  @Autowired JdbcTemplate jdbc;
  @Autowired MutableClock clock;
  @Autowired ObjectMapper json;
  @Autowired PlatformTransactionManager transactions;
  @LocalServerPort int port;
  String password, changed;
  AdminIdentity initial;
  static final String AUTH = "/admin/api/core/auth";

  @BeforeEach
  void setup() {
    clock.advance(Duration.ofDays(1));
    jdbc.execute("TRUNCATE cms.change_history,cms.admin_account_role,cms.admin_account");
    jdbc.update("UPDATE cms.administration_state SET bootstrap_completed=false");
    password = UUID.randomUUID().toString();
    changed = UUID.randomUUID().toString();
    initial = admins.bootstrap(" Root ", "Root", password);
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor csrf() {
    return request -> {
      String token = UUID.randomUUID().toString();
      request.setCookies(new Cookie("XSRF-TOKEN", token));
      request.addHeader("X-XSRF-TOKEN", token);
      return request;
    };
  }

  MockHttpSession login(String account, String secret) throws Exception {
    return (MockHttpSession)
        mvc.perform(
                post(AUTH + "/login")
                    .with(csrf())
                    .contentType("application/json")
                    .content(
                        json.writeValueAsString(Map.of("loginId", account, "password", secret))))
            .andExpect(status().isOk())
            .andReturn()
            .getRequest()
            .getSession(false);
  }

  AdminIdentity readyAdmin() {
    admins.changePassword(initial, password, changed);
    return admins.authenticate("root", changed);
  }

  @Test
  void permissionAndRevisionErrorsCarryDifferentCmsCodes() throws Exception {
    var root = readyAdmin();
    var member = admins.createAccount(root, "reader", "Reader", password);
    admins.changePassword(member, password, changed);
    var denied = login("reader", changed);
    mvc.perform(get("/admin/api/core/files").session(denied))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    var permitted = login("root", changed);
    mvc.perform(
            post("/admin/api/core/menus")
                .session(permitted)
                .with(csrf())
                .contentType("application/json")
                .content(
                    "{\"title\":\"stale\",\"type\":\"GROUP\",\"visible\":true,\"revision\":-1}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("STALE_REVISION"));
  }

  @Test
  void bootstrapIsOneShotAndHistoryDoesNotContainSecrets() {
    assertThatThrownBy(() -> admins.bootstrap("second", "Second", password))
        .isInstanceOf(AdminProblem.class);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM cms.admin_account", Integer.class))
        .isEqualTo(1);
    String history =
        jdbc.queryForObject("SELECT after_data::text FROM cms.change_history", String.class);
    assertThat(history).contains("root").doesNotContain(password, "passwordHash", "argon2id");
  }

  @Test
  void unknownWrongAndInactiveAccountsHaveSameResponse() throws Exception {
    var root = readyAdmin();
    var other = admins.createAccount(root, "disabled", "Disabled", password);
    admins.setActive(root, other.id(), false);
    String expected = null;
    for (var credentials :
        List.of(
            Map.of("loginId", "missing", "password", password),
            Map.of("loginId", "root", "password", password),
            Map.of("loginId", "disabled", "password", password))) {
      var response =
          mvc.perform(
                  post(AUTH + "/login")
                      .with(csrf())
                      .contentType("application/json")
                      .content(json.writeValueAsString(credentials)))
              .andExpect(status().isUnauthorized())
              .andReturn()
              .getResponse();
      if (expected == null) expected = response.getContentAsString();
      else assertThat(response.getContentAsString()).isEqualTo(expected);
    }
  }

  @Test
  void csrfCookieIsReadableAndLoginRotatesSessionAndToken() throws Exception {
    var before = mvc.perform(get(AUTH + "/csrf")).andExpect(status().isNoContent()).andReturn();
    var session = (MockHttpSession) before.getRequest().getSession(false);
    String id = session.getId();
    Cookie token = before.getResponse().getCookie("XSRF-TOKEN");
    assertThat(token.isHttpOnly()).isFalse();
    assertThat(token.getPath()).isEqualTo("/admin");

    var result =
        mvc.perform(
                post(AUTH + "/login")
                    .session(session)
                    .cookie(token)
                    .header("X-XSRF-TOKEN", token.getValue())
                    .contentType("application/json")
                    .content(
                        json.writeValueAsString(Map.of("loginId", "root", "password", password))))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(session.getId()).isNotEqualTo(id);
    var newToken =
        Arrays.stream(result.getResponse().getCookies())
            .filter(c -> c.getName().equals("XSRF-TOKEN") && c.getMaxAge() != 0)
            .reduce((a, b) -> b)
            .orElseThrow();
    assertThat(newToken.getValue()).isNotEqualTo(token.getValue());
    mvc.perform(
            post(AUTH + "/logout")
                .session(session)
                .cookie(newToken)
                .header("X-XSRF-TOKEN", token.getValue()))
        .andExpect(status().isForbidden());
    var logout =
        mvc.perform(
                post(AUTH + "/logout")
                    .session(session)
                    .cookie(newToken)
                    .header("X-XSRF-TOKEN", newToken.getValue()))
            .andExpect(status().isNoContent())
            .andReturn();
    assertThat(session.isInvalid()).isTrue();
    assertThat(
            Arrays.stream(logout.getResponse().getCookies())
                .filter(c -> c.getName().equals("XSRF-TOKEN") && c.getMaxAge() != 0)
                .reduce((a, b) -> b)
                .orElseThrow()
                .getValue())
        .isNotEqualTo(newToken.getValue());
  }

  @Test
  void loginAndChangingRequestsRequireCsrf() throws Exception {
    mvc.perform(post(AUTH + "/login").contentType("application/json").content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    var session = login("root", password);
    mvc.perform(post(AUTH + "/logout").session(session)).andExpect(status().isForbidden());
    mvc.perform(
            put(AUTH + "/password").session(session).contentType("application/json").content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(AUTH + "/logout")
                .session(session)
                .with(
                    r -> {
                      r.setCookies(new Cookie("XSRF-TOKEN", "valid"));
                      r.addHeader("X-XSRF-TOKEN", "invalid");
                      return r;
                    }))
        .andExpect(status().isForbidden());
  }

  @Test
  void idleExpiresExactlyAtThirtyMinutes() throws Exception {
    var session = login("root", password);
    clock.advance(Duration.ofMinutes(30));
    mvc.perform(get(AUTH + "/me").session(session))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
    assertThat(session.isInvalid()).isTrue();
  }

  @Test
  void activityResetsIdleButCannotExtendAbsoluteEightHours() throws Exception {
    var session = login("root", password);
    for (int i = 0; i < 16; i++) {
      clock.advance(Duration.ofMinutes(29));
      mvc.perform(get(AUTH + "/me").session(session)).andExpect(status().isOk());
    }
    clock.advance(Duration.ofMinutes(16));
    mvc.perform(get(AUTH + "/me").session(session)).andExpect(status().isUnauthorized());
  }

  @Test
  void lastActiveSystemAdminCannotBeDisabled() {
    var root = readyAdmin();
    assertThatThrownBy(() -> admins.setActive(root, root.id(), false))
        .isInstanceOf(AdminProblem.class);
    assertThat(admins.current(root)).isEqualTo(root);
  }

  @Test
  void inactiveAccountIsBlockedOnNextRequest() throws Exception {
    var root = readyAdmin();
    var member = admins.createAccount(root, "member", "Member", password);
    var session = login("member", password);
    admins.setActive(root, member.id(), false);
    mvc.perform(get(AUTH + "/me").session(session)).andExpect(status().isUnauthorized());
  }

  @Test
  void passwordChangeInvalidatesEverySessionAndClearsInitialRequirement() throws Exception {
    var first = login("root", password);
    var second = login("root", password);
    mvc.perform(get("/admin/api/core/accounts").session(first))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
    mvc.perform(
            put(AUTH + "/password")
                .session(first)
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of("currentPassword", password, "newPassword", changed))))
        .andExpect(status().isNoContent());
    mvc.perform(get(AUTH + "/me").session(second)).andExpect(status().isUnauthorized());
    assertThat(admins.authenticate("root", changed).mustChangePassword()).isFalse();
  }

  @Test
  void accountCreationRequiresSystemAdminAndWritesHistory() throws Exception {
    readyAdmin();
    var rootSession = login("root", changed);
    mvc.perform(
            post("/admin/api/core/accounts")
                .session(rootSession)
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of("loginId", "new", "displayName", "New", "password", password))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("passwordHash").doesNotExist());
    var member = admins.authenticate("new", password);
    admins.changePassword(member, password, changed);
    mvc.perform(get("/admin/api/core/accounts").session(login("new", changed)))
        .andExpect(status().isForbidden());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM cms.change_history WHERE action='CREATE'", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void historyFailureRollsBackAccountCreation() {
    var root = readyAdmin();
    jdbc.execute(
        "ALTER TABLE cms.change_history ADD CONSTRAINT fail_history CHECK (action <> 'CREATE') NOT VALID");
    try {
      assertThatThrownBy(() -> admins.createAccount(root, "rollback", "Rollback", password))
          .isInstanceOf(RuntimeException.class);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM cms.admin_account WHERE login_id='rollback'",
                  Integer.class))
          .isZero();
    } finally {
      jdbc.execute("ALTER TABLE cms.change_history DROP CONSTRAINT fail_history");
    }
  }

  @Test
  void businessRollbackAlsoRollsBackHistory() {
    var root = readyAdmin();
    int before = jdbc.queryForObject("SELECT count(*) FROM cms.change_history", Integer.class);
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            tx -> {
              admins.createAccount(root, "rollback", "Rollback", password);
              tx.setRollbackOnly();
            });
    assertThat(jdbc.queryForObject("SELECT count(*) FROM cms.change_history", Integer.class))
        .isEqualTo(before);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM cms.admin_account", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void concurrentBootstrapCreatesExactlyOneAccountAndHistory() throws Exception {
    jdbc.execute("TRUNCATE cms.change_history,cms.admin_account_role,cms.admin_account");
    jdbc.update("UPDATE cms.administration_state SET bootstrap_completed=false");
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        int number = i;
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    admins.bootstrap("race" + number, "Race", password);
                    return true;
                  } catch (AdminProblem expected) {
                    return false;
                  }
                }));
      }
      start.countDown();
      int success = 0;
      for (var result : results) if (result.get(15, TimeUnit.SECONDS)) success++;
      assertThat(success).isEqualTo(1);
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM cms.admin_account", Integer.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM cms.change_history", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void loginQuotaEnforcesIpAcrossAccounts() throws Exception {
    for (int i = 0; i < 5; i++)
      mvc.perform(
              post(AUTH + "/login")
                  .with(csrf())
                  .contentType("application/json")
                  .content(
                      json.writeValueAsString(
                          Map.of("loginId", "missing" + i, "password", password))))
          .andExpect(status().isUnauthorized());
    mvc.perform(
            post(AUTH + "/login")
                .with(csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("loginId", "root", "password", password))))
        .andExpect(status().isTooManyRequests());
    clock.advance(Duration.ofSeconds(60));
    login("root", password);
  }

  @Test
  void loginQuotaEnforcesNormalizedAccountAcrossIps() throws Exception {
    for (int i = 0; i < 6; i++) {
      int ip = i;
      mvc.perform(
              post(AUTH + "/login")
                  .with(csrf())
                  .with(
                      r -> {
                        r.setRemoteAddr("192.0.2." + ip);
                        return r;
                      })
                  .contentType("application/json")
                  .content(
                      json.writeValueAsString(
                          Map.of("loginId", i % 2 == 0 ? " ROOT " : "root", "password", "wrong"))))
          .andExpect(status().is(i < 5 ? 401 : 429));
    }
  }

  @Test
  void sessionCookieAttributesAreSetByRealServletContainer() throws Exception {
    var client = java.net.http.HttpClient.newHttpClient();
    var response =
        client.send(
            java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("http://localhost:" + port + AUTH + "/csrf"))
                .GET()
                .build(),
            java.net.http.HttpResponse.BodyHandlers.ofString());
    String session =
        response.headers().allValues("set-cookie").stream()
            .filter(c -> c.startsWith("CMSSESSION="))
            .findFirst()
            .orElseThrow();
    assertThat(
            response.headers().allValues("set-cookie").stream()
                .filter(c -> c.startsWith("XSRF-TOKEN="))
                .findFirst()
                .orElseThrow())
        .contains("SameSite=Strict")
        .doesNotContain("HttpOnly");
    assertThat(session)
        .contains("HttpOnly", "SameSite=Strict", "Path=/admin")
        .doesNotContain("Secure");
  }

  @Test
  void bearerTokenDoesNotAuthenticateAdmin() throws Exception {
    mvc.perform(get(AUTH + "/me").header("Authorization", "Bearer not-an-admin-credential"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void cliRejectsPasswordArgumentBeforeDatabaseAndDoesNotEchoIt() throws Exception {
    var result =
        cli(
            List.of("--login-id=another", "--display-name=Another", "--password=" + password),
            null,
            false);
    assertThat(result.exit).isEqualTo(1);
    assertThat(result.output).doesNotContain(password);
  }

  @Test
  void cliReadsStdinAndRefusesRerunWithoutLeakingSecret() throws Exception {
    jdbc.execute("TRUNCATE cms.change_history,cms.admin_account_role,cms.admin_account");
    jdbc.update("UPDATE cms.administration_state SET bootstrap_completed=false");
    var result = cli(List.of("--login-id=cli", "--display-name=CLI"), password, false);
    assertThat(result.exit).withFailMessage(result.output).isZero();
    assertThat(result.output).doesNotContain(password, "argon2");
    assertThat(admins.authenticate("cli", password).mustChangePassword()).isTrue();
    var again = cli(List.of("--login-id=other", "--display-name=Other"), changed, true);
    assertThat(again.exit).isEqualTo(1);
    assertThat(again.output).doesNotContain(changed, password);
  }

  record CliResult(int exit, String output) {}

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void cliRejectsMissingOrPartialSchemaWithoutChangingDatabase(boolean partial) throws Exception {
    String name = "bootstrap_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("CREATE DATABASE " + name);
    String url = db.getJdbcUrl().replace("/" + db.getDatabaseName(), "/" + name);
    try (var connection =
            java.sql.DriverManager.getConnection(url, db.getUsername(), db.getPassword());
        var statement = connection.createStatement()) {
      if (partial) {
        statement.execute("CREATE SCHEMA cms");
        statement.execute("CREATE TABLE cms.admin_account (id text PRIMARY KEY)");
      }
      String before = databaseObjects(connection);
      var result =
          cli(List.of("--login-id=initial", "--display-name=Initial"), password, false, url);
      assertThat(databaseObjects(connection))
          .as("CLI must not create schemas, tables or Flyway history")
          .isEqualTo(before);
      assertThat(result.exit).isEqualTo(1);
      assertThat(result.output).contains("Start the deployed application").doesNotContain(password);
    }
  }

  private String databaseObjects(java.sql.Connection connection) throws Exception {
    try (var query = connection.createStatement();
        var result =
            query.executeQuery(
                """
          SELECT json_build_object(
            'schemas', (SELECT json_agg(nspname ORDER BY nspname) FROM pg_namespace
              WHERE nspname NOT LIKE 'pg_%' AND nspname <> 'information_schema'),
            'relations', (SELECT json_agg(row_to_json(r) ORDER BY r.schema,r.name) FROM
              (SELECT n.nspname AS schema,c.relname AS name,c.relkind AS kind
               FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
               WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema') r),
            'columns', (SELECT json_agg(row_to_json(c) ORDER BY table_schema,table_name,ordinal_position)
              FROM (SELECT table_schema,table_name,column_name,ordinal_position,data_type,is_nullable,column_default
                FROM information_schema.columns WHERE table_schema NOT LIKE 'pg_%'
                  AND table_schema <> 'information_schema') c))::text
          """)) {
      result.next();
      return result.getString(1);
    }
  }

  @Test
  void cliCanCreateUsingEnvironmentSecretWithoutPrintingIt() throws Exception {
    jdbc.execute("TRUNCATE cms.change_history,cms.admin_account_role,cms.admin_account");
    jdbc.update("UPDATE cms.administration_state SET bootstrap_completed=false");
    var result =
        cli(List.of("--login-id=environment", "--display-name=Environment"), password, true);
    assertThat(result.exit).withFailMessage(result.output).isZero();
    assertThat(result.output).doesNotContain(password, "argon2");
    assertThat(admins.authenticate("environment", password).mustChangePassword()).isTrue();
  }

  @Test
  void concurrentDisablingCannotRemoveBothLastSystemAdministrators() throws Exception {
    var first = readyAdmin();
    var second = admins.createAccount(first, "second", "Second", password);
    jdbc.update(
        "INSERT INTO cms.admin_account_role SELECT ?,id FROM cms.admin_role WHERE code='SYSTEM_ADMIN'",
        second.id());
    admins.changePassword(second, password, changed);
    var other = admins.authenticate("second", changed);
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var results =
          List.of(
              pool.submit(
                  () -> {
                    start.await();
                    try {
                      admins.setActive(first, first.id(), false);
                      return true;
                    } catch (AdminProblem expected) {
                      return false;
                    }
                  }),
              pool.submit(
                  () -> {
                    start.await();
                    try {
                      admins.setActive(other, other.id(), false);
                      return true;
                    } catch (AdminProblem expected) {
                      return false;
                    }
                  }));
      start.countDown();
      int success = 0;
      for (var result : results) if (result.get(15, TimeUnit.SECONDS)) success++;
      assertThat(success).isEqualTo(1);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM cms.admin_account WHERE active", Integer.class))
        .isEqualTo(1);
  }

  CliResult cli(List<String> args, String secret, boolean env) throws Exception {
    return cli(args, secret, env, db.getJdbcUrl());
  }

  CliResult cli(List<String> args, String secret, boolean env, String databaseUrl)
      throws Exception {
    var command =
        new ArrayList<>(
            List.of(
                System.getProperty("java.home") + "/bin/java",
                "-cp",
                System.getProperty("cms.runtime.classpath"),
                AdminBootstrap.class.getName()));
    command.addAll(args);
    var builder = new ProcessBuilder(command).redirectErrorStream(true);
    builder.environment().put("DB_URL", databaseUrl);
    builder.environment().put("DB_USERNAME", db.getUsername());
    builder.environment().put("DB_PASSWORD", db.getPassword());
    builder.environment().remove("CMS_BOOTSTRAP_PASSWORD");
    if (env && secret != null) builder.environment().put("CMS_BOOTSTRAP_PASSWORD", secret);
    var file = java.nio.file.Files.createTempFile("cms-cli-", ".log");
    builder.redirectOutput(file.toFile());
    var process = builder.start();
    try {
      if (!env && secret != null)
        process
            .getOutputStream()
            .write((secret + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      process.getOutputStream().close();
      assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
      return new CliResult(process.exitValue(), java.nio.file.Files.readString(file));
    } finally {
      process.destroyForcibly();
      java.nio.file.Files.deleteIfExists(file);
    }
  }

  public static class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-01-01T00:00:00Z");

    public void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import(CmsCoreConfiguration.class)
  static class Host {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }
}
