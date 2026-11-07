package com.cms.core;

import static org.assertj.core.api.Assertions.*;

import com.cms.core.account.*;
import com.cms.core.file.*;
import com.cms.core.permission.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(
    classes = FileServiceIntegrationTest.Host.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.flyway.locations=classpath:db/cms",
      "spring.flyway.default-schema=public",
      "spring.jpa.hibernate.ddl-auto=validate"
    })
class FileServiceIntegrationTest {
  // Every file write facade below has executable success/failure coverage in this class.
  static final Set<String> WRITE_CONTRACTS =
      Set.of("FileService.upload/4", "FileService.delete/2", "FileService.reconcile/0");

  @Container
  static final PostgreSQLContainer<?> db =
      new PostgreSQLContainer<>(
              DockerImageName.parse("postgis/postgis:17-3.6").asCompatibleSubstituteFor("postgres"))
          .withPassword(UUID.randomUUID().toString())
          .withTmpFs(Map.of("/docker-entrypoint-initdb.d", "rw"));

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", db::getJdbcUrl);
    r.add("spring.datasource.username", db::getUsername);
    r.add("spring.datasource.password", db::getPassword);
  }

  @Autowired FileService files;
  @Autowired AdminService admins;
  @Autowired RoleService roles;
  @Autowired JdbcTemplate sql;
  @Autowired Memory store;
  @Autowired TestClock clock;
  AdminIdentity root;
  byte[] data = "test file body secret".getBytes(java.nio.charset.StandardCharsets.UTF_8);

  @BeforeEach
  void setup() {
    store.objects.clear();
    store.failDelete = false;
    store.failPut = false;
    store.afterPut = () -> {};
    sql.execute(
        "TRUNCATE cms.stored_file,cms.admin_account_role,cms.admin_account,cms.role_permission,cms.change_history");
    sql.update("DELETE FROM cms.admin_role WHERE code<>'SYSTEM_ADMIN'");
    sql.update("UPDATE cms.administration_state SET bootstrap_completed=false");
    String password = UUID.randomUUID().toString();
    var initial = admins.bootstrap("root", "Root", password);
    admins.changePassword(initial, password, password + "new");
    root = admins.authenticate("root", password + "new");
  }

  StoredFile.View upload() {
    return files.upload(root, "sample.txt", "text/plain", data);
  }

  long count(String action) {
    return sql.queryForObject(
        "SELECT count(*) FROM cms.change_history WHERE resource_type='file' AND action=?",
        Long.class,
        action);
  }

  String status(String id) {
    return sql.queryForObject("SELECT status FROM cms.stored_file WHERE id=?", String.class, id);
  }

  @Test
  void successUploadAndDeleteHaveOneSafeHistoryAndBodyIsUnchanged() throws Exception {
    var file = upload();
    assertThat(status(file.id())).isEqualTo("ACTIVE");
    assertThat(count("UPLOAD")).isEqualTo(1);
    try (var content = files.download(root, file.id()).content()) {
      assertThat(content.readAllBytes()).isEqualTo(data);
    }
    String payload =
        sql.queryForObject(
            "SELECT after_data::text FROM cms.change_history WHERE resource_type='file'",
            String.class);
    assertThat(payload)
        .doesNotContain(new String(data), "object_key", "signature", "token", "password");
    assertThat(payload).contains(file.sha256(), "sample.txt");
    files.delete(root, file.id());
    files.delete(root, file.id());
    assertThat(count("DELETE")).isEqualTo(1);
    assertThat(store.objects).isEmpty();
    assertThat(files.list(root, 0, 50)).isEmpty();
  }

  @Test
  void uploadHistoryFailureKeepsNoActiveRowOrObject() {
    sql.execute(
        "ALTER TABLE cms.change_history ADD CONSTRAINT fail_file_history CHECK(resource_type <> 'file') NOT VALID");
    try {
      assertThatThrownBy(this::upload).isInstanceOf(RuntimeException.class);
    } finally {
      sql.execute("ALTER TABLE cms.change_history DROP CONSTRAINT fail_file_history");
    }
    assertThat(sql.queryForList("SELECT status FROM cms.stored_file", String.class))
        .containsExactly("FAILED");
    assertThat(store.objects).isEmpty();
    assertThat(count("UPLOAD")).isZero();
  }

  @Test
  void deleteHistoryFailurePreservesActiveFileAndObject() {
    var f = upload();
    sql.execute(
        "ALTER TABLE cms.change_history ADD CONSTRAINT fail_file_history CHECK(action <> 'DELETE') NOT VALID");
    try {
      assertThatThrownBy(() -> files.delete(root, f.id())).isInstanceOf(RuntimeException.class);
    } finally {
      sql.execute("ALTER TABLE cms.change_history DROP CONSTRAINT fail_file_history");
    }
    assertThat(status(f.id())).isEqualTo("ACTIVE");
    assertThat(store.objects).containsKey("cms/files/" + f.id());
    assertThat(count("DELETE")).isZero();
  }

  @Test
  void putFailureTransitionsToFailedWithoutSuccessHistory() {
    store.failPut = true;
    assertThatThrownBy(this::upload).isInstanceOf(IllegalStateException.class);
    assertThat(sql.queryForList("SELECT status FROM cms.stored_file", String.class))
        .containsExactly("FAILED");
    assertThat(count("UPLOAD")).isZero();
  }

  @Test
  void abandonedRowsRetainAttemptsAndAreRetried() {
    store.afterPut =
        () -> {
          sql.update("UPDATE cms.stored_file SET status='ABANDONED'");
        };
    store.failDelete = true;
    assertThatThrownBy(this::upload)
        .isInstanceOfSatisfying(AdminProblem.class, e -> assertThat(e.status()).isEqualTo(409));
    files.reconcile();
    assertThat(sql.queryForObject("SELECT delete_attempts FROM cms.stored_file", Integer.class))
        .isEqualTo(1);
    assertThat(sql.queryForObject("SELECT status FROM cms.stored_file", String.class))
        .isEqualTo("ABANDONED");
    store.failDelete = false;
    files.reconcile();
    assertThat(sql.queryForObject("SELECT count(*) FROM cms.stored_file", Long.class)).isZero();
    assertThat(store.objects).isEmpty();
    assertThat(count("UPLOAD")).isZero();
  }

  @Test
  void pendingDeletesAreHiddenAndRetriedWithAttemptCount() {
    var file = upload();
    store.failDelete = true;
    files.delete(root, file.id());
    assertThat(status(file.id())).isEqualTo("DELETE_PENDING");
    assertThat(sql.queryForObject("SELECT delete_attempts FROM cms.stored_file", Integer.class))
        .isEqualTo(1);
    assertThatThrownBy(() -> files.download(root, file.id())).isInstanceOf(AdminProblem.class);
    assertThat(files.list(root, 0, 50)).isEmpty();
    files.reconcile();
    assertThat(sql.queryForObject("SELECT delete_attempts FROM cms.stored_file", Integer.class))
        .isEqualTo(2);
    store.failDelete = false;
    files.reconcile();
    assertThat(store.objects).isEmpty();
    assertThat(count("DELETE")).isEqualTo(1);
  }

  @Test
  void oldOrphansAreRemovedYoungAndActiveObjectsSurvive() {
    var active = upload();
    String old = "cms/files/" + UUID.randomUUID(), young = "cms/files/" + UUID.randomUUID();
    store.put(old, data, "text/plain");
    clock.advance(Duration.ofMinutes(11));
    store.put(young, data, "text/plain");
    var report = files.reconcile();
    assertThat(report.objects()).isEqualTo(3);
    assertThat(report.deleted()).isEqualTo(1);
    assertThat(store.objects).containsOnlyKeys("cms/files/" + active.id(), young);
    assertThat(count("UPLOAD")).isEqualTo(1);
  }

  @Test
  void uploadingRowsAreHiddenAndGraceBoundaryIsConditional() {
    store.afterPut =
        () -> {
          var id = sql.queryForObject("SELECT id FROM cms.stored_file", String.class);
          assertThat(status(id)).isEqualTo("UPLOADING");
          assertThat(files.list(root, 0, 50)).isEmpty();
          assertThatThrownBy(() -> files.detail(root, id)).isInstanceOf(AdminProblem.class);
          files.reconcile();
          assertThat(status(id)).isEqualTo("UPLOADING");
          clock.advance(Duration.ofMinutes(10));
          files.reconcile();
        };
    assertThatThrownBy(this::upload)
        .isInstanceOfSatisfying(AdminProblem.class, e -> assertThat(e.status()).isEqualTo(409));
    assertThat(store.objects).isEmpty();
    assertThat(count("UPLOAD")).isZero();
  }

  @Test
  void fileSharingUsesReadManageNotCreator() throws Exception {
    String p = UUID.randomUUID().toString();
    var member = admins.createAccount(root, "other", "Other", p);
    admins.changePassword(member, p, p + "new");
    member = admins.authenticate("other", p + "new");
    var read = roles.create(root, "FILE_READER", "Reader", "READ_ONLY", Set.of("cms.files"));
    member = roles.assign(root, member.id(), Set.of(read.id()), member.authVersion());
    var current = member;
    var file = upload();
    assertThat(files.detail(current, file.id())).isEqualTo(file);
    try (var stream = files.download(current, file.id()).content()) {
      assertThat(stream.readAllBytes()).isEqualTo(data);
    }
    assertThatThrownBy(() -> files.delete(current, file.id()))
        .isInstanceOfSatisfying(AdminProblem.class, e -> assertThat(e.status()).isEqualTo(403));
    roles.permissions(root, read.id(), Map.of("cms.files", Action.MANAGE), read.version());
    files.delete(current, file.id());
    assertThat(count("DELETE")).isEqualTo(1);
  }

  @Test
  void filenameTypeAndByteLimitsRejectInvalidInputs() {
    for (String name : List.of("../file", "a\\b", "\rname", ""))
      assertThatThrownBy(() -> files.upload(root, name, "text/plain", data))
          .isInstanceOf(AdminProblem.class);
    for (String type : List.of("application/octet-stream", "image/heic", "text/html"))
      assertThatThrownBy(() -> files.upload(root, "file", type, data))
          .isInstanceOf(AdminProblem.class);
    assertThatThrownBy(() -> files.upload(root, "file", "text/plain", new byte[] {0, 1, 2}))
        .isInstanceOf(AdminProblem.class);
    assertThatThrownBy(() -> files.upload(root, "file", "application/pdf", data))
        .isInstanceOf(AdminProblem.class);
    byte[] exact = new byte[FileService.MAX_BYTES];
    Arrays.fill(exact, (byte) 'x');
    assertThat(files.upload(root, "exact.txt", "text/plain", exact).byteSize())
        .isEqualTo(exact.length);
    assertThatThrownBy(
            () -> files.upload(root, "large.txt", "text/plain", new byte[exact.length + 1]))
        .isInstanceOfSatisfying(AdminProblem.class, e -> assertThat(e.status()).isEqualTo(413));
  }

  @Test
  void reuploadMayDuplicateWithoutIdempotencyKey() {
    var first = upload();
    var second = upload();
    assertThat(second.id()).isNotEqualTo(first.id());
    assertThat(count("UPLOAD")).isEqualTo(2);
    assertThat(files.list(root, 0, 50)).hasSize(2);
    assertThat(store.objects).hasSize(2);
  }

  static class TestClock extends Clock {
    Instant now = Instant.now();

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

  static class Memory implements ObjectStorage {
    record Entry(byte[] bytes, Instant time) {}

    Map<String, Entry> objects = new ConcurrentHashMap<>();
    final Clock clock;
    boolean failPut, failDelete;
    Runnable afterPut = () -> {};

    Memory(Clock clock) {
      this.clock = clock;
    }

    public void put(String key, byte[] bytes, String mime) {
      if (failPut) throw new IllegalStateException("PUT");
      objects.put(key, new Entry(bytes, clock.instant()));
      afterPut.run();
    }

    public InputStream open(String key) {
      return new ByteArrayInputStream(objects.get(key).bytes);
    }

    public void delete(String key) {
      if (failDelete) throw new IllegalStateException("DELETE");
      objects.remove(key);
    }

    public Page list(String prefix, String token) {
      return new Page(
          objects.entrySet().stream().map(e -> new Item(e.getKey(), e.getValue().time)).toList(),
          null);
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import(CmsCoreConfiguration.class)
  static class Host {
    @Bean
    @Primary
    TestClock testClock() {
      return new TestClock();
    }

    @Bean
    Memory memory(Clock clock) {
      return new Memory(clock);
    }
  }
}
