package com.loco.cmsinfra;

import static org.assertj.core.api.Assertions.*;

import com.cms.core.*;
import com.cms.core.account.*;
import com.cms.core.file.*;
import com.cms.core.permission.*;
import com.fasterxml.jackson.databind.*;
import com.loco.images.codec.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.testcontainers.containers.*;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitAllStrategy;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Testcontainers
@SpringBootTest(
    classes = CmsFilesIntegrationTest.Host.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "cms.cookie.secure=false",
      "cms.storage.enabled=true",
      "cms.storage.region=us-east-1",
      "cms.storage.bucket=cms-files-test",
      "cms.storage.create-bucket=true",
      "cms.storage.cleanup-interval-ms=3600000"
    })
class CmsFilesIntegrationTest {
  static final String IMAGE =
      "chrislusf/seaweedfs:4.52@sha256:57877e32bb90073b37dc109b438ba66e84fd5ed9ced5f5c04f56444ef243ce59";
  static final String KEY = "test" + UUID.randomUUID().toString().replace("-", "");
  static final String SECRET = UUID.randomUUID().toString() + UUID.randomUUID();

  @Container
  static final PostgreSQLContainer<?> db =
      new PostgreSQLContainer<>(
              DockerImageName.parse("postgis/postgis:17-3.6").asCompatibleSubstituteFor("postgres"))
          .withPassword(UUID.randomUUID().toString())
          .withTmpFs(Map.of("/docker-entrypoint-initdb.d", "rw"));

  @Container
  static final GenericContainer<?> seaweed =
      new GenericContainer<>(IMAGE)
          .withEnv("AWS_ACCESS_KEY_ID", KEY)
          .withEnv("AWS_SECRET_ACCESS_KEY", SECRET)
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
              c -> c.getHostConfig().withNanoCPUs(1_000_000_000L).withMemory(1024L * 1024 * 1024))
          .waitingFor(
              new WaitAllStrategy()
                  .withStrategy(Wait.forHttp("/").forPort(8333).forStatusCode(403))
                  .withStrategy(
                      Wait.forSuccessfulCommand(
                          "echo cluster.ps | timeout 4 weed shell -connectTimeout=2s -master=127.0.0.1:9333 -filer=127.0.0.1:8888 2>/dev/null | grep -q 'filers 1'"))
                  .withStartupTimeout(Duration.ofMinutes(2)));

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", db::getJdbcUrl);
    r.add("spring.datasource.username", db::getUsername);
    r.add("spring.datasource.password", db::getPassword);
    r.add(
        "cms.storage.endpoint",
        () -> "http://" + seaweed.getHost() + ":" + seaweed.getMappedPort(8333));
    r.add("cms.storage.access-key", () -> KEY);
    r.add("cms.storage.secret-key", () -> SECRET);
  }

  @Autowired FileService files;
  @Autowired AdminService admins;
  @Autowired RoleService roles;
  @Autowired S3ObjectStorage raw;
  @Autowired FaultStorage storage;
  @Autowired JdbcTemplate sql;
  @Autowired DataSource source;
  @Autowired ObjectMapper json;
  @Autowired TestClock clock;
  @Autowired ImageTransformer images;
  @Autowired ImageConverter codec;
  @LocalServerPort int port;
  AdminIdentity root;
  String password;
  final byte[] data = "local file payload".getBytes(StandardCharsets.UTF_8);
  final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void setup() {
    storage.reset();
    clock.now = Instant.now();
    String token = null;
    do {
      var page = raw.list("", token);
      for (var item : page.items()) raw.delete(item.key());
      token = page.nextToken();
    } while (token != null);
    sql.execute(
        "TRUNCATE cms.stored_file,cms.admin_account_role,cms.admin_account,cms.role_permission,cms.change_history");
    sql.update("DELETE FROM cms.admin_role WHERE code <> 'SYSTEM_ADMIN'");
    sql.update("UPDATE cms.administration_state SET bootstrap_completed=false");
    password = UUID.randomUUID().toString();
    var initial = admins.bootstrap("root", "Root", password);
    admins.changePassword(initial, password, password + "new");
    root = admins.authenticate("root", password + "new");
  }

  @AfterAll
  static void storageDiagnostics() throws IOException {
    var output = Path.of("build/reports/cms-storage");
    Files.createDirectories(output);
    Files.writeString(
        output.resolve("seaweedfs.log"),
        seaweed.getLogs().replace(KEY, "[redacted]").replace(SECRET, "[redacted]"));
  }

  StoredFile.View upload() {
    return files.upload(root, "notes.txt", "text/plain", data);
  }

  long histories(String action) {
    return sql.queryForObject(
        "SELECT count(*) FROM cms.change_history WHERE resource_type='file' AND action=?",
        Long.class,
        action);
  }

  boolean exists(String id) {
    return raw.list("cms/files/" + id, null).items().stream()
        .anyMatch(i -> i.key().equals("cms/files/" + id));
  }

  @Test
  void s3ContractPathStylePrivacyMissingAndIdempotentDelete() throws Exception {
    String key = "cms/files/" + UUID.randomUUID();
    raw.put(key, data, "text/plain");
    try (var stream = raw.open(key)) {
      assertThat(stream.readAllBytes()).isEqualTo(data);
    }
    var item = raw.list("cms/files/", null).items().getFirst();
    assertThat(item.key()).isEqualTo(key);
    assertThat(item.createdAt()).isNotNull();
    int anonymous =
        http.send(
                HttpRequest.newBuilder(
                        URI.create(
                            "http://"
                                + seaweed.getHost()
                                + ":"
                                + seaweed.getMappedPort(8333)
                                + "/cms-files-test/"
                                + key))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.discarding())
            .statusCode();
    assertThat(anonymous).isEqualTo(403);
    raw.delete(key);
    raw.delete(key);
    assertThatThrownBy(() -> raw.open(key))
        .isInstanceOfSatisfying(
            S3Exception.class,
            e -> {
              assertThat(e.statusCode()).isEqualTo(404);
              assertThat(e.awsErrorDetails().errorCode()).isEqualTo("NoSuchKey");
              System.out.println(
                  "CMS_S3_CONTRACT anonymous="
                      + anonymous
                      + " missing="
                      + e.statusCode()
                      + "/"
                      + e.awsErrorDetails().errorCode()
                      + " duplicateDelete=success pathStyle=true");
            });
  }

  @ParameterizedTest
  @ValueSource(strings = {"jpeg", "png", "webp", "avif", "animated-webp"})
  void acceptedImagesUseRealVipsAndSeaweed(String format) throws Exception {
    byte[] fixture = fixture(format);
    var file =
        files.upload(
            root,
            "image." + format,
            format.equals("jpeg")
                ? "image/jpeg"
                : format.equals("animated-webp") ? "image/webp" : "image/" + format,
            fixture);
    try (var stream = files.download(root, file.id()).content()) {
      byte[] output = stream.readAllBytes();
      assertThat(new String(output, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
      assertThat(new String(output, 8, 4, StandardCharsets.US_ASCII)).isEqualTo("WEBP");
      var meta = codec.metadata(output);
      assertThat(meta.width()).isLessThanOrEqualTo(2048);
      assertThat(meta.height()).isLessThanOrEqualTo(2048);
    }
    assertThat(histories("UPLOAD")).isEqualTo(1);
  }

  static byte[] fixture(String format) throws IOException {
    try (var in =
        CmsFilesIntegrationTest.class.getResourceAsStream(
            "/codec/"
                + (format.equals("animated-webp") ? "animated.webp" : "quadrants." + format))) {
      if (in == null) throw new IOException("fixture missing " + format);
      return in.readAllBytes();
    }
  }

  @Test
  void heicAndDisguisedImagesAreRejectedAndCleaned() throws Exception {
    for (byte[] input : List.of(fixture("heic"), data)) {
      assertThatThrownBy(() -> files.upload(root, "bad.png", "image/png", input))
          .isInstanceOfSatisfying(AdminProblem.class, e -> assertThat(e.status()).isEqualTo(415));
    }
    assertThat(raw.list("cms/files/", null).items()).isEmpty();
    assertThat(histories("UPLOAD")).isZero();
  }

  @Test
  void putResponseLossAndDatabaseFailureAreCompensated() {
    storage.losePut = true;
    assertThatThrownBy(this::upload).isInstanceOf(RuntimeException.class);
    assertThat(raw.list("cms/files/", null).items()).isEmpty();
    storage.losePut = false;
    sql.execute(
        "ALTER TABLE cms.change_history ADD CONSTRAINT fail_file CHECK(resource_type<>'file') NOT VALID");
    try {
      assertThatThrownBy(this::upload).isInstanceOf(RuntimeException.class);
    } finally {
      sql.execute("ALTER TABLE cms.change_history DROP CONSTRAINT fail_file");
    }
    assertThat(raw.list("cms/files/", null).items()).isEmpty();
    assertThat(histories("UPLOAD")).isZero();
    assertThat(sql.queryForList("SELECT status FROM cms.stored_file", String.class))
        .containsOnly("FAILED");
  }

  @Test
  void deletionFailureRemainsPendingWithAttemptsThenReconciles() {
    var f = upload();
    storage.failDelete = true;
    files.delete(root, f.id());
    assertThat(sql.queryForObject("SELECT status FROM cms.stored_file", String.class))
        .isEqualTo("DELETE_PENDING");
    assertThat(exists(f.id())).isTrue();
    assertThat(files.list(root, 0, 50)).isEmpty();
    var failed = files.reconcile();
    assertThat(failed.failures()).isEqualTo(1);
    assertThat(sql.queryForObject("SELECT delete_attempts FROM cms.stored_file", Integer.class))
        .isEqualTo(2);
    storage.failDelete = false;
    files.reconcile();
    assertThat(exists(f.id())).isFalse();
    assertThat(histories("DELETE")).isEqualTo(1);
  }

  @Test
  void compensationFailureAndOldOrphansAreEventuallyRemoved() {
    storage.losePut = true;
    storage.failDelete = true;
    assertThatThrownBy(this::upload).isInstanceOf(RuntimeException.class);
    String id = sql.queryForObject("SELECT id FROM cms.stored_file", String.class);
    assertThat(exists(id)).isTrue();
    String orphan = "cms/files/" + UUID.randomUUID();
    raw.put(orphan, data, "text/plain");
    storage.losePut = false;
    storage.failDelete = false;
    files.reconcile();
    assertThat(exists(id)).isTrue();
    clock.advance(Duration.ofMinutes(11));
    var report = files.reconcile();
    assertThat(report.deleted()).isEqualTo(2);
    assertThat(raw.list("cms/files/", null).items()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void conditionalActiveAndAbandonedUpdatesRaceThroughDatabaseGate(boolean activeFirst)
      throws Exception {
    var put = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    storage.afterPut =
        () -> {
          put.countDown();
          await(resume);
        };
    try (var pool = Executors.newFixedThreadPool(2)) {
      var uploading = pool.submit(this::upload);
      assertThat(put.await(20, TimeUnit.SECONDS)).isTrue();
      String id = sql.queryForObject("SELECT id FROM cms.stored_file", String.class);
      sql.update(
          "UPDATE cms.stored_file SET created_at=created_at-interval '11 minutes' WHERE id=?", id);
      try (var gate = source.getConnection()) {
        gate.setAutoCommit(false);
        try (var lock =
            gate.prepareStatement("SELECT id FROM cms.stored_file WHERE id=? FOR UPDATE")) {
          lock.setString(1, id);
          lock.execute();
        }
        Future<FileService.Reconciliation> cleaning;
        if (activeFirst) {
          resume.countDown();
          waitForUpdate("ACTIVE");
          cleaning = pool.submit(files::reconcile);
          waitForUpdate("ABANDONED");
        } else {
          cleaning = pool.submit(files::reconcile);
          waitForUpdate("ABANDONED");
          resume.countDown();
          waitForUpdate("ACTIVE");
        }
        gate.commit();
        cleaning.get(30, TimeUnit.SECONDS);
        if (activeFirst) {
          assertThat(uploading.get(30, TimeUnit.SECONDS).id()).isEqualTo(id);
          assertThat(exists(id)).isTrue();
          assertThat(
                  sql.queryForObject(
                      "SELECT status FROM cms.stored_file WHERE id=?", String.class, id))
              .isEqualTo("ACTIVE");
          assertThat(histories("UPLOAD")).isEqualTo(1);
        } else {
          assertThatThrownBy(() -> uploading.get(30, TimeUnit.SECONDS))
              .hasCauseInstanceOf(AdminProblem.class);
          assertThat(exists(id)).isFalse();
          assertThat(histories("UPLOAD")).isZero();
        }
        System.out.println(
            "CMS_FILE_RACE first="
                + (activeFirst ? "ACTIVE" : "ABANDONED")
                + " activeObjectMissing=0 uploadHistory="
                + histories("UPLOAD"));
      } finally {
        resume.countDown();
      }
    }
  }

  void waitForUpdate(String state) throws Exception {
    long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < limit) {
      int waiting =
          sql.queryForObject(
              "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE ?",
              Integer.class,
              "%SET status='" + state + "'%");
      if (waiting > 0) return;
      Thread.sleep(20);
    }
    throw new AssertionError("DB waiter missing: " + state);
  }

  static void await(CountDownLatch latch) {
    try {
      if (!latch.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("gate timeout");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  @Test
  void latePutAfterAbandonedRowRemovalCannotPublish() throws Exception {
    var ready = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    storage.beforePut =
        () -> {
          ready.countDown();
          await(resume);
        };
    try (var pool = Executors.newSingleThreadExecutor()) {
      var uploading = pool.submit(this::upload);
      assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
      String id = sql.queryForObject("SELECT id FROM cms.stored_file", String.class);
      files.reconcile();
      assertThat(sql.queryForObject("SELECT status FROM cms.stored_file", String.class))
          .isEqualTo("UPLOADING");
      sql.update("UPDATE cms.stored_file SET created_at=created_at-interval '11 minutes'");
      files.reconcile();
      assertThat(sql.queryForObject("SELECT count(*) FROM cms.stored_file", Long.class)).isZero();
      resume.countDown();
      assertThatThrownBy(() -> uploading.get(30, TimeUnit.SECONDS))
          .hasCauseInstanceOf(AdminProblem.class);
      assertThat(exists(id)).isFalse();
    } finally {
      resume.countDown();
    }
  }

  @Test
  void httpUploadDownloadCsrfSharingAndPostCommitResponseFailure() throws Exception {
    String cookie = login();
    String csrf =
        Arrays.stream(cookie.split("; "))
            .filter(c -> c.startsWith("XSRF-TOKEN="))
            .findFirst()
            .orElseThrow()
            .substring(11);
    var nonMultipart =
        http.send(
            HttpRequest.newBuilder(URI.create(url("/files")))
                .header("Cookie", cookie)
                .header("X-XSRF-TOKEN", csrf)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(nonMultipart.statusCode()).isEqualTo(400);
    var denied = multipart(cookie, false, false, data);
    assertThat(denied.statusCode()).isEqualTo(403);
    assertThat(histories("UPLOAD")).isZero();
    var saved = multipart(cookie, true, false, data);
    assertThat(saved.statusCode()).isEqualTo(201);
    String id = json.readTree(saved.body()).path("id").asText();
    var download =
        http.send(
            HttpRequest.newBuilder(URI.create(url("/files/" + id + "/content")))
                .header("Cookie", cookie)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertThat(download.statusCode()).isEqualTo(200);
    assertThat(download.body()).isEqualTo(data);
    assertThat(download.headers().firstValue("content-disposition").orElse(""))
        .contains("attachment");
    var failed = multipart(cookie, true, true, data);
    assertThat(failed.statusCode()).isEqualTo(500);
    assertThat(histories("UPLOAD")).isEqualTo(2);
    for (var f : files.list(root, 0, 50)) assertThat(exists(f.id())).isTrue();
    assertThat(multipart(cookie, true, false, data).statusCode()).isEqualTo(201);
    assertThat(histories("UPLOAD")).isEqualTo(3);
    String history =
        sql.queryForObject(
            "SELECT jsonb_agg(after_data)::text FROM cms.change_history WHERE resource_type='file'",
            String.class);
    assertThat(history)
        .doesNotContain(SECRET, password, new String(data), cookie, "X-Amz-Signature");
    for (String entry : cookie.split("; "))
      assertThat(history).doesNotContain(entry.split("=", 2)[1]);
  }

  HttpResponse<String> multipart(String cookie, boolean csrf, boolean fail, byte[] body)
      throws Exception {
    String boundary = "cmsBoundary";
    var bytes = new ByteArrayOutputStream();
    bytes.write(
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"note.txt\"\r\nContent-Type: text/plain\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
    bytes.write(body);
    bytes.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    var builder =
        HttpRequest.newBuilder(URI.create(url("/files")))
            .header("Cookie", cookie)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary);
    if (csrf)
      builder.header(
          "X-XSRF-TOKEN",
          Arrays.stream(cookie.split("; "))
              .filter(c -> c.startsWith("XSRF-TOKEN="))
              .findFirst()
              .orElseThrow()
              .substring(11));
    if (fail) builder.header("X-Test-Response-Failure", "true");
    return http.send(
        builder.POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray())).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  String url(String path) {
    return "http://localhost:" + port + "/admin/api/core" + path;
  }

  String login() throws Exception {
    var csrf =
        http.send(
            HttpRequest.newBuilder(URI.create(url("/auth/csrf"))).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    String token = cookie(csrf, "XSRF-TOKEN"), session = cookie(csrf, "CMSSESSION");
    var response =
        http.send(
            HttpRequest.newBuilder(URI.create(url("/auth/login")))
                .header("Cookie", session + "; " + token)
                .header("X-XSRF-TOKEN", token.substring(11))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        json.writeValueAsString(
                            Map.of("loginId", "root", "password", password + "new"))))
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

  @Test
  void measuresFullListingAndDatabaseComparisonAcrossTwoPages() throws Exception {
    long start = System.nanoTime();
    var ids = IntStreamIds(1001);
    try (var pool = Executors.newFixedThreadPool(4)) {
      var futures =
          ids.stream()
              .map(id -> pool.submit(() -> raw.put("cms/files/" + id, data, "text/plain")))
              .toList();
      for (var future : futures) future.get(2, TimeUnit.MINUTES);
    }
    for (String id : ids)
      sql.update(
          "INSERT INTO cms.stored_file(id,object_key,original_name,media_type,byte_size,sha256,status,created_by,created_at,delete_attempts) VALUES (?,?,?,'text/plain',?,'test','ACTIVE',?,CURRENT_TIMESTAMP,0)",
          id,
          "cms/files/" + id,
          "fixture.txt",
          data.length,
          root.id());
    clock.advance(Duration.ofMinutes(11));
    var report = files.reconcile();
    assertThat(report.objects()).isEqualTo(1001);
    assertThat(report.pages()).isEqualTo(2);
    assertThat(report.deleted()).isZero();
    assertThat(report.failures()).isZero();
    var directory = Path.of("build/reports/cms-storage");
    Files.createDirectories(directory);
    Files.writeString(
        directory.resolve("reconciliation.txt"),
        "SeaweedFS CPU1/1GiB; active objects=1001; page size=1000; metadata existence SELECT=1001; unfinished SELECT=1; "
            + report
            + "; preparation+scanMillis="
            + ((System.nanoTime() - start) / 1_000_000));
    System.out.println("CMS_STORAGE_SCAN " + report);
  }

  List<String> IntStreamIds(int n) {
    return java.util.stream.IntStream.range(0, n)
        .mapToObj(i -> UUID.randomUUID().toString())
        .toList();
  }

  static class TestClock extends Clock {
    volatile Instant now = Instant.now();

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId z) {
      return this;
    }

    public Instant instant() {
      return now;
    }

    void advance(Duration d) {
      now = now.plus(d);
    }
  }

  static class FaultStorage implements ObjectStorage {
    final S3ObjectStorage actual;
    volatile boolean losePut, failDelete;
    volatile Runnable beforePut = () -> {}, afterPut = () -> {};

    FaultStorage(S3ObjectStorage actual) {
      this.actual = actual;
    }

    void reset() {
      losePut = false;
      failDelete = false;
      beforePut = () -> {};
      afterPut = () -> {};
    }

    public void put(String key, byte[] bytes, String mime) {
      beforePut.run();
      actual.put(key, bytes, mime);
      afterPut.run();
      if (losePut) throw new IllegalStateException("injected PUT reply loss");
    }

    public InputStream open(String key) {
      return actual.open(key);
    }

    public void delete(String key) {
      if (failDelete) throw new IllegalStateException("injected DELETE failure");
      actual.delete(key);
    }

    public Page list(String prefix, String token) {
      return actual.list(prefix, token);
    }
  }

  @RestControllerAdvice
  static class ResponseFailure implements ResponseBodyAdvice<Object> {
    public boolean supports(
        org.springframework.core.MethodParameter p, Class<? extends HttpMessageConverter<?>> type) {
      return p.getContainingClass() == com.cms.core.web.FileController.class;
    }

    public Object beforeBodyWrite(
        Object body,
        org.springframework.core.MethodParameter p,
        MediaType type,
        Class<? extends HttpMessageConverter<?>> converter,
        org.springframework.http.server.ServerHttpRequest req,
        org.springframework.http.server.ServerHttpResponse response) {
      if ("true".equals(req.getHeaders().getFirst("X-Test-Response-Failure")))
        throw new IllegalStateException("injected response failure after commit");
      return body;
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import({CmsCoreConfiguration.class, CmsStorageConfiguration.class, ResponseFailure.class})
  static class Host {
    @Bean
    @Primary
    TestClock testClock() {
      return new TestClock();
    }

    @Bean
    ImageConverter converter() {
      return new VipsImageConverter(System.getProperty("loco.vips.executable", "vips"));
    }

    @Bean
    @Primary
    FaultStorage faults(S3ObjectStorage actual) {
      return new FaultStorage(actual);
    }
  }
}
