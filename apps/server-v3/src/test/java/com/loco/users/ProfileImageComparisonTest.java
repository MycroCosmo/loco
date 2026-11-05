package com.loco.users;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.loco.compat.DatabaseSnapshot;
import com.loco.compat.JsonComparison;
import com.loco.compat.SharedHarness;
import com.loco.images.codec.ImageIoConverter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
class ProfileImageComparisonTest extends com.loco.compat.ImageUploadRequests {
  @Override
  protected String uploadPath() {
    return "/api/users/me/profile-image";
  }

  private static final String OLD = "/uploads/profiles/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa.webp";
  private static final DatabaseSnapshot.Rules OTHER_FIELDS =
      new DatabaseSnapshot.Rules(
          Map.of(),
          Map.of(
              "users",
              "SELECT id,email,nickname,password,provider,\"providerId\",NULL::text AS \"profileImageUrl\",\"fcmToken\",\"deletedAt\",\"createdAt\",timestamp '2000-01-01' AS \"updatedAt\" FROM users"));

  static Stream<JsonNode> cases() throws Exception {
    try (var stream =
        ProfileImageComparisonTest.class.getResourceAsStream("/users/image-v2.json")) {
      var rows = new com.fasterxml.jackson.databind.ObjectMapper().readTree(stream);
      return java.util.stream.StreamSupport.stream(rows.spliterator(), false);
    }
  }

  private void seed() throws Exception {
    environment().reset("compat/seed.sql");
    Path root = environment().uploads;
    if (!root.startsWith(Path.of("build/harness-uploads").toAbsolutePath().normalize()))
      throw new IllegalStateException("Unsafe test root");
    if (Files.exists(root))
      try (var paths = Files.walk(root)) {
        for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
          Files.delete(path);
      }
    byte[] webp = fixture("webp");
    Files.createDirectories(root.resolve("profiles"));
    Files.write(root.resolve(OLD.substring("/uploads/".length())), webp);
    Files.write(root.resolve("profiles/keep.webp"), webp);
    environment()
        .nodeCheck(
            "const fs=require('fs'),i=JSON.parse(process.argv[1]); fs.rmSync('/app/uploads/profiles',{recursive:true,force:true}); fs.mkdirSync('/app/uploads/profiles',{recursive:true}); for(const name of ['aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa.webp','keep.webp']) fs.writeFileSync('/app/uploads/profiles/'+name,Buffer.from(i.data,'base64')); console.log('{}')",
            Map.of("data", Base64.getEncoder().encodeToString(webp)));
    for (var side : SharedHarness.Side.values())
      try (var db = environment().connect(side)) {
        db.createStatement().execute("UPDATE users SET \"profileImageUrl\"='" + OLD + "'");
      }
  }

  private Map<String, String> files(SharedHarness.Side side) throws Exception {
    var result = new TreeMap<String, String>();
    if (side == SharedHarness.Side.V2) {
      var entries =
          environment()
              .nodeCheck(
                  "const fs=require('fs'); const r={}; for(const name of fs.readdirSync('/app/uploads/profiles')) { const p='/app/uploads/profiles/'+name; if(fs.statSync(p).isFile()) r['/uploads/profiles/'+name]=fs.readFileSync(p).toString('base64'); } console.log(JSON.stringify(r))",
                  Map.of());
      entries
          .fields()
          .forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue().asText()));
    } else
      try (var paths = Files.walk(environment().uploads)) {
        for (var path : paths.filter(Files::isRegularFile).toList())
          result.put(
              "/uploads/" + environment().uploads.relativize(path).toString().replace('\\', '/'),
              Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
      }
    return result;
  }

  private String token() throws Exception {
    return environment()
        .token("valid", SharedHarness.Environment.USER_ID, SharedHarness.Environment.EMAIL);
  }

  private String base(SharedHarness.Side side) {
    return side == SharedHarness.Side.V2 ? v2Url() : v3Url();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void responseDatabaseAndFilesMatchV2(JsonNode expected) throws Exception {
    seed();
    String input = expected.path("input").asText();
    JsonNode first = null;
    byte[] firstImage = null;
    for (var side : SharedHarness.Side.values())
      try (var db = environment().connect(side)) {
        var before = DatabaseSnapshot.capture(db, DatabaseSnapshot.Rules.defaults());
        var otherFields = DatabaseSnapshot.capture(db, OTHER_FIELDS);
        var beforeFiles = files(side);
        var response = uploadCase(base(side), input, token());
        assertThat(response.getStatusCode().value()).isEqualTo(expected.path("status").asInt());
        if (first == null) first = response.getBody();
        else
          JsonComparison.compare(
              first,
              response.getBody(),
              first.has("profileImageUrl")
                  ? Map.of("/profileImageUrl", "독립 저장소의 새 UUID 파일", "/updatedAt", "서버 처리 시각")
                  : Map.of());
        if (response.getStatusCode().isError()) {
          before.assertUnchanged(DatabaseSnapshot.capture(db, DatabaseSnapshot.Rules.defaults()));
          if (input.equals("heic")) {
            var afterFiles = files(side);
            beforeFiles.forEach((name, bytes) -> assertThat(afterFiles.get(name)).isEqualTo(bytes));
            var added = new java.util.HashSet<>(afterFiles.keySet());
            added.removeAll(beforeFiles.keySet());
            assertThat(added).hasSize(1);
            String leftover = added.iterator().next();
            assertThat(leftover).matches("/uploads/profiles/[0-9a-f-]{36}\\.webp");
            assertThat(afterFiles.get(leftover)).isEmpty();
          } else assertThat(files(side)).isEqualTo(beforeFiles);
        } else {
          otherFields.assertUnchanged(DatabaseSnapshot.capture(db, OTHER_FIELDS));
          String url = response.getBody().path("profileImageUrl").asText();
          assertThat(url).matches("/uploads/profiles/[0-9a-f-]{36}\\.webp");
          var after = files(side);
          assertThat(after.keySet()).containsExactlyInAnyOrder(url, "/uploads/profiles/keep.webp");
          assertThat(after.get("/uploads/profiles/keep.webp"))
              .isEqualTo(beforeFiles.get("/uploads/profiles/keep.webp"));
          byte[] bytes = Base64.getDecoder().decode(after.get(url));
          var converter = new ImageIoConverter();
          var image = converter.read(bytes);
          assertThat(image.getWidth()).isEqualTo(expected.path("image").path("width").asInt());
          assertThat(image.getHeight()).isEqualTo(expected.path("image").path("height").asInt());
          if (firstImage == null) firstImage = bytes;
          else comparePixels(firstImage, bytes);
          try (var row =
              db.createStatement()
                  .executeQuery(
                      "SELECT \"profileImageUrl\",nickname,email,\"updatedAt\" FROM users")) {
            row.next();
            assertThat(row.getString(1)).isEqualTo(url);
            assertThat(row.getString(2)).isEqualTo("compat");
            assertThat(row.getString(3)).isEqualTo(SharedHarness.Environment.EMAIL);
            assertThat(response.getBody().path("updatedAt").asText())
                .isEqualTo(UserResponses.timestamp(row.getTimestamp(4).toLocalDateTime()));
          }
          var served = client.getForEntity(base(side) + url, byte[].class);
          assertThat(served.getStatusCode().value()).isEqualTo(200);
          assertThat(served.getBody()).isEqualTo(bytes);
          assertThat(served.getHeaders().getFirst("Content-Type")).startsWith("image/webp");
          assertThat(served.getHeaders().getFirst("Cross-Origin-Resource-Policy"))
              .isEqualTo("cross-origin");
        }
      }
  }

  private void comparePixels(byte[] left, byte[] right) throws Exception {
    var converter = new ImageIoConverter();
    var a = converter.read(left);
    var b = converter.read(right);
    assertThat(b.getWidth()).isEqualTo(a.getWidth());
    assertThat(b.getHeight()).isEqualTo(a.getHeight());
    // 리사이즈 필터·WebP 인코더의 경계 픽셀 차이를 피하고 각 사분면 내부를 비교한다.
    for (int y : new int[] {a.getHeight() / 4, a.getHeight() * 3 / 4})
      for (int x : new int[] {a.getWidth() / 4, a.getWidth() * 3 / 4})
        for (int shift : new int[] {0, 8, 16, 24})
          assertThat(
                  Math.abs(((a.getRGB(x, y) >> shift) & 255) - ((b.getRGB(x, y) >> shift) & 255)))
              .isLessThanOrEqualTo(24);
  }

  @Test
  void storageFailureDoesNotChangeDatabaseOrExistingFiles() throws Exception {
    seed();
    for (var side : SharedHarness.Side.values())
      try (var db = environment().connect(side)) {
        var before = DatabaseSnapshot.capture(db, DatabaseSnapshot.Rules.defaults());
        var beforeFiles = files(side);
        Path root = environment().uploads;
        if (side == SharedHarness.Side.V2)
          environment()
              .nodeCheck(
                  "const fs=require('fs');fs.renameSync('/app/uploads/profiles','/app/uploads/profiles.saved');fs.writeFileSync('/app/uploads/profiles','blocked');console.log('{}')",
                  Map.of());
        else {
          Files.move(root.resolve("profiles"), root.resolve("profiles.saved"));
          Files.writeString(root.resolve("profiles"), "blocked");
        }
        try {
          var response = uploadCase(base(side), "png", token());
          assertThat(response.getStatusCode().value()).isEqualTo(500);
          before.assertUnchanged(DatabaseSnapshot.capture(db, DatabaseSnapshot.Rules.defaults()));
        } finally {
          if (side == SharedHarness.Side.V2)
            environment()
                .nodeCheck(
                    "const fs=require('fs');fs.unlinkSync('/app/uploads/profiles');fs.renameSync('/app/uploads/profiles.saved','/app/uploads/profiles');console.log('{}')",
                    Map.of());
          else {
            Files.delete(root.resolve("profiles"));
            Files.move(root.resolve("profiles.saved"), root.resolve("profiles"));
          }
        }
        assertThat(files(side)).isEqualTo(beforeFiles);
      }
  }

  @ParameterizedTest
  @ValueSource(strings = {"external-url", "missing-old-file", "old-path-is-directory"})
  void oldImageCleanupDoesNotFailSuccessfulUpload(String state) throws Exception {
    seed();
    for (var side : SharedHarness.Side.values())
      try (var db = environment().connect(side)) {
        if (state.equals("external-url"))
          db.createStatement()
              .execute("UPDATE users SET \"profileImageUrl\"='https://example.com/photo.webp'");
        else if (side == SharedHarness.Side.V2)
          environment()
              .nodeCheck(
                  "const fs=require('fs'),i=JSON.parse(process.argv[1]);const p='/app'+i.old;fs.unlinkSync(p);if(i.directory) fs.mkdirSync(p);console.log('{}')",
                  Map.of("old", OLD, "directory", state.equals("old-path-is-directory")));
        else {
          var path = environment().uploads.resolve(OLD.substring("/uploads/".length()));
          Files.delete(path);
          if (state.equals("old-path-is-directory")) Files.createDirectory(path);
        }
        var response = uploadCase(base(side), "png", token());
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(files(side)).containsKey(response.getBody().path("profileImageUrl").asText());
        if (state.equals("external-url")) assertThat(files(side)).containsKey(OLD);
        if (state.equals("old-path-is-directory")) {
          boolean remains =
              side == SharedHarness.Side.V2
                  ? environment()
                      .nodeCheck(
                          "console.log(JSON.stringify(require('fs').statSync('/app'+JSON.parse(process.argv[1]).old).isDirectory()))",
                          Map.of("old", OLD))
                      .asBoolean()
                  : Files.isDirectory(
                      environment().uploads.resolve(OLD.substring("/uploads/".length())));
          assertThat(remains).isTrue();
        }
      }
  }

  @Test
  void withdrawalKeepsImagesAndStaticDownloadNeedsNoMemberQuery() throws Exception {
    seed();
    for (var side : SharedHarness.Side.values())
      try (var db = environment().connect(side)) {
        var beforeFiles = files(side);
        assertThat(
                request(base(side), "/api/users/me", HttpMethod.DELETE, token(), null)
                    .getStatusCode()
                    .value())
            .isEqualTo(200);
        assertThat(files(side)).isEqualTo(beforeFiles);
        db.createStatement().execute("ALTER TABLE users RENAME TO unavailable_users");
        try {
          assertThat(client.getForEntity(base(side) + OLD, byte[].class).getStatusCode().value())
              .isEqualTo(200);
        } finally {
          db.createStatement().execute("ALTER TABLE unavailable_users RENAME TO users");
        }
      }
  }

  @ParameterizedTest
  @ValueSource(strings = {"before-update", "commit"})
  void databaseFailureRemovesNewFileAndPreservesOld(String phase) throws Exception {
    seed();
    for (var side : SharedHarness.Side.values())
      try (var db = environment().connect(side)) {
        db.createStatement()
            .execute(
                "CREATE FUNCTION image_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'image failure'; END; $$");
        db.createStatement()
            .execute(
                phase.equals("commit")
                    ? "CREATE CONSTRAINT TRIGGER image_fail AFTER UPDATE ON users DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION image_fail()"
                    : "CREATE TRIGGER image_fail BEFORE UPDATE ON users FOR EACH ROW EXECUTE FUNCTION image_fail()");
        try {
          var before = DatabaseSnapshot.capture(db, DatabaseSnapshot.Rules.defaults());
          var beforeFiles = files(side);
          var response = uploadCase(base(side), "png", token());
          assertThat(response.getStatusCode().value()).isEqualTo(500);
          assertThat(response.getBody())
              .isEqualTo(
                  mapper.readTree("{\"statusCode\":500,\"message\":\"Internal server error\"}"));
          before.assertUnchanged(DatabaseSnapshot.capture(db, DatabaseSnapshot.Rules.defaults()));
          assertThat(files(side)).isEqualTo(beforeFiles);
        } finally {
          db.createStatement().execute("DROP TRIGGER image_fail ON users");
          db.createStatement().execute("DROP FUNCTION image_fail()");
        }
      }
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "deleted", "absent"})
  void authenticationPrecedesMultipartParsing(String state) throws Exception {
    seed();
    for (var side : SharedHarness.Side.values())
      try (var db = environment().connect(side)) {
        if (state.equals("deleted"))
          db.createStatement().execute("UPDATE users SET \"deletedAt\"=CURRENT_TIMESTAMP");
        if (state.equals("absent")) db.createStatement().execute("DELETE FROM users");
        var beforeFiles = files(side);
        var before = DatabaseSnapshot.capture(db, DatabaseSnapshot.Rules.defaults());
        var response =
            uploadCase(base(side), "boundary-missing", state.equals("missing") ? null : token());
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody())
            .isEqualTo(mapper.readTree("{\"statusCode\":401,\"message\":\"Unauthorized\"}"));
        before.assertUnchanged(DatabaseSnapshot.capture(db, DatabaseSnapshot.Rules.defaults()));
        assertThat(files(side)).isEqualTo(beforeFiles);
      }
  }
}
