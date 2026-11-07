package com.loco.cmsinfra;

import com.cms.core.account.*;
import com.cms.core.menu.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loco.LocoApplication;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.*;
import org.testcontainers.containers.wait.strategy.*;
import org.testcontainers.utility.DockerImageName;

/** Separate browser-test executable. Never included in bootJar or normal JUnit runs. */
public class AdminUiBrowserHost {
  public static class TestClock extends Clock {
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return Clock.fixed(instant(), zone);
    }

    public Instant instant() {
      return now.get();
    }

    void advance(Duration amount) {
      now.updateAndGet(time -> time.plus(amount));
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Time {
    @Bean
    @Primary
    TestClock browserClock() {
      return new TestClock();
    }
  }

  public static void main(String[] args) throws Exception {
    String key = UUID.randomUUID().toString(), secret = UUID.randomUUID().toString();
    String password = UUID.randomUUID().toString(), firstPassword = UUID.randomUUID().toString();
    String controlSecret = UUID.randomUUID().toString();
    Path runtime = Path.of(System.getProperty("cms.e2e.runtime"));
    try (var db =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("postgis/postgis:17-3.6")
                        .asCompatibleSubstituteFor("postgres"))
                .withPassword(UUID.randomUUID().toString())
                .withTmpFs(Map.of("/docker-entrypoint-initdb.d", "rw"));
        var seaweed =
            new GenericContainer<>(CmsFilesIntegrationTest.IMAGE)
                .withEnv("AWS_ACCESS_KEY_ID", key)
                .withEnv("AWS_SECRET_ACCESS_KEY", secret)
                .withCommand(
                    "server",
                    "-dir=/data",
                    "-ip=127.0.0.1",
                    "-ip.bind=0.0.0.0",
                    "-s3",
                    "-s3.port.iceberg=0",
                    "-s3.port.lance=0",
                    "-master.telemetry=false")
                .withExposedPorts(8333, 9333)
                .withCreateContainerCmdModifier(
                    c ->
                        c.getHostConfig()
                            .withNanoCPUs(1_000_000_000L)
                            .withMemory(1024L * 1024 * 1024))
                .waitingFor(
                    new WaitAllStrategy()
                        .withStrategy(Wait.forHttp("/").forPort(8333).forStatusCode(403))
                        .withStrategy(
                            Wait.forSuccessfulCommand(
                                "echo cluster.ps | timeout 4 weed shell -connectTimeout=2s -master=127.0.0.1:9333 -filer=127.0.0.1:8888 2>/dev/null | grep -q 'filers 1'"))
                        .withStartupTimeout(Duration.ofMinutes(2)))) {
      db.start();
      seaweed.start();
      try (var app =
          new SpringApplicationBuilder(LocoApplication.class, Time.class)
              .run(
                  "--server.address=127.0.0.1",
                  "--server.port=0",
                  "--cms.cookie.secure=false",
                  "--spring.datasource.url=" + db.getJdbcUrl(),
                  "--spring.datasource.username=" + db.getUsername(),
                  "--spring.datasource.password=" + db.getPassword(),
                  "--loco.jwt.secret=" + UUID.randomUUID() + UUID.randomUUID(),
                  "--loco.cleanup.enabled=false",
                  "--loco.notifications.service-account=",
                  "--loco.notifications.base-url=http://127.0.0.1:9",
                  "--loco.kakao.base-url=http://127.0.0.1:9",
                  "--loco.kakao.api-key=",
                  "--loco.uploads.directory=",
                  "--cms.storage.enabled=true",
                  "--cms.storage.endpoint=http://"
                      + seaweed.getHost()
                      + ":"
                      + seaweed.getMappedPort(8333),
                  "--cms.storage.access-key=" + key,
                  "--cms.storage.secret-key=" + secret,
                  "--cms.storage.region=us-east-1",
                  "--cms.storage.bucket=cms-browser",
                  "--cms.storage.create-bucket=true",
                  "--cms.storage.cleanup-interval-ms=3600000")) {
        var json = app.getBean(ObjectMapper.class);
        var clock = app.getBean(TestClock.class);
        var sql = app.getBean(JdbcTemplate.class);
        var admins = app.getBean(AdminService.class);
        var menus = app.getBean(MenuService.class);
        var stop = new CountDownLatch(1);
        var control = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        control.createContext(
            "/",
            exchange -> {
              try {
                if (!"POST".equals(exchange.getRequestMethod())
                    || !controlSecret.equals(
                        exchange.getRequestHeaders().getFirst("X-Test-Control"))) {
                  exchange.sendResponseHeaders(403, -1);
                  return;
                }
                Object result;
                switch (exchange.getRequestURI().getPath()) {
                  case "/reset" -> {
                    clock.advance(Duration.ofDays(1));
                    sql.execute(
                        "TRUNCATE cms.stored_file,cms.change_history,cms.menu_node,cms.role_permission,cms.admin_account_role,cms.admin_account");
                    sql.update("DELETE FROM cms.admin_role WHERE code<>'SYSTEM_ADMIN'");
                    sql.update("UPDATE cms.administration_state SET bootstrap_completed=false");
                    sql.update("UPDATE cms.menu_tree_revision SET revision=0");
                    var initial = admins.bootstrap("root", "Browser Root", password);
                    admins.changePassword(initial, password, password);
                    var root = admins.authenticate("root", password);
                    admins.createAccount(root, "first", "First Admin", firstPassword);
                    var a =
                        menus.create(
                            root,
                            new MenuService.Draft(
                                "그룹 A", MenuNode.Kind.GROUP, null, null, null, null, true, 0L));
                    String aid = a.nodes().getFirst().id();
                    var b =
                        menus.create(
                            root,
                            new MenuService.Draft(
                                "그룹 B",
                                MenuNode.Kind.GROUP,
                                null,
                                null,
                                null,
                                null,
                                true,
                                a.revision()));
                    String bid =
                        b.nodes().stream()
                            .filter(n -> n.title().equals("그룹 B"))
                            .findFirst()
                            .orElseThrow()
                            .id();
                    menus.create(
                        root,
                        new MenuService.Draft(
                            "하위 파일",
                            MenuNode.Kind.PAGE,
                            aid,
                            null,
                            "cms.files",
                            "cms.files",
                            true,
                            b.revision()));
                    result = Map.of("a", aid, "b", bid);
                  }
                  case "/expire" -> {
                    clock.advance(Duration.ofMinutes(30));
                    result = Map.of("ok", true);
                  }
                  case "/stop" -> {
                    result = Map.of("ok", true);
                    stop.countDown();
                  }
                  default -> {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                  }
                }
                byte[] data = json.writeValueAsBytes(result);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, data.length);
                exchange.getResponseBody().write(data);
              } catch (Exception error) {
                exchange.sendResponseHeaders(500, -1);
              } finally {
                exchange.close();
              }
            });
        control.start();
        try {
          Files.createDirectories(runtime.getParent());
          String base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port");
          Files.writeString(
              runtime,
              json.writeValueAsString(
                  Map.of(
                      "baseUrl",
                      base,
                      "controlUrl",
                      "http://127.0.0.1:" + control.getAddress().getPort(),
                      "controlSecret",
                      controlSecret,
                      "password",
                      password,
                      "firstPassword",
                      firstPassword)));
          stop.await();
        } finally {
          control.stop(1);
          Files.deleteIfExists(runtime);
        }
      }
    }
  }
}
