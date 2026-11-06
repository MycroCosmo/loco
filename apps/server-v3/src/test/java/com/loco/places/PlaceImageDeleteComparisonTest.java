package com.loco.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.loco.compat.*;
import com.loco.maps.SqlQueryCounter;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
@Import(SqlQueryCounter.Configuration.class)
class PlaceImageDeleteComparisonTest extends PlaceImageTestSupport {
  static java.util.stream.Stream<JsonNode> inputs() throws Exception {
    return fixtures("delete");
  }

  @ParameterizedTest
  @MethodSource("inputs")
  void responseDatabaseFilesAndSql(JsonNode expected) throws Exception {
    seedBoth(2);
    String name = expected.path("case").asText();
    for (var side : sides())
      try (var db = environment().connect(side)) {
        if (name.equals("repeat"))
          request(base(side), uploadPath(), HttpMethod.DELETE, token(1), Map.of("imageUrl", A));
        var before = DatabaseSnapshot.capture(db, PlaceWriteSupport.rules());
        var other = DatabaseSnapshot.capture(db, otherFields());
        var original = files(side);
        SqlQueryCounter.reset();
        var response =
            request(
                base(side),
                uploadPath(),
                HttpMethod.DELETE,
                token(1),
                name.equals("repeat") ? Map.of("imageUrl", A) : mapper.readTree(name));
        int status = expected.path("status").asInt();
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(normalized(response.getBody())).isEqualTo(expected.get("body"));
        boolean removed = name.contains("unknown");
        if (side == SharedHarness.Side.V3)
          assertThat(SqlQueryCounter.statements()).hasSize(status == 400 ? 1 : removed ? 4 : 3);
        other.assertUnchanged(DatabaseSnapshot.capture(db, otherFields()));
        if (!removed) {
          before.assertUnchanged(DatabaseSnapshot.capture(db, PlaceWriteSupport.rules()));
          assertThat(files(side)).isEqualTo(original);
        } else {
          var after = files(side);
          assertThat(after).containsOnlyKeys(B);
          assertThat(after.get(B)).isEqualTo(original.get(B));
        }
        if (status == 200) assertDatabaseResponse(side, response.getBody());
      }
  }

  static java.util.stream.Stream<JsonNode> permissions() throws Exception {
    return fixtures("permissions").filter(r -> r.path("case").asText().startsWith("delete-"));
  }

  @ParameterizedTest
  @MethodSource("permissions")
  void permissionAndOrder(JsonNode expected) throws Exception {
    seedBoth(2);
    String name = expected.path("case").asText().substring(7);
    if (name.startsWith("missing-place")) placeId = "missing";
    if (name.startsWith("private")) privateBoth();
    String auth =
        name.equals("unauthenticated")
            ? null
            : name.equals("deleted-user")
                ? token(4)
                : name.equals("missing-user")
                    ? environment()
                        .token(
                            "valid", "99999999-9999-4999-8999-999999999999", "missing@example.test")
                    : token(
                        name.equals("other-author") || name.equals("private-owner-not-author")
                            ? 2
                            : 1);
    for (var side : sides())
      try (var db = environment().connect(side)) {
        var before = DatabaseSnapshot.capture(db, PlaceWriteSupport.rules());
        var beforeFiles = files(side);
        SqlQueryCounter.reset();
        var response =
            request(
                base(side),
                uploadPath(),
                HttpMethod.DELETE,
                auth,
                name.endsWith("no-url") ? Map.of() : Map.of("imageUrl", A));
        assertThat(response.getStatusCode().value()).isEqualTo(expected.path("status").asInt());
        assertThat(normalized(response.getBody())).isEqualTo(expected.get("body"));
        if (side == SharedHarness.Side.V3)
          assertThat(SqlQueryCounter.statements())
              .hasSize(
                  name.equals("unauthenticated")
                      ? 0
                      : name.endsWith("user") || name.endsWith("no-url")
                          ? 1
                          : response.getStatusCode().value() == 200 ? 4 : 2);
        if (response.getStatusCode().isError()) {
          before.assertUnchanged(DatabaseSnapshot.capture(db, PlaceWriteSupport.rules()));
          assertThat(files(side)).isEqualTo(beforeFiles);
        } else {
          assertDatabaseResponse(side, response.getBody());
          assertThat(files(side)).containsOnlyKeys(B);
        }
      }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"update", "commit", "missing-file", "directory", "external", "duplicate-url"})
  void failureAndCleanup(String mode) throws Exception {
    seedBoth(2);
    JsonNode first = null;
    for (var side : sides())
      try (var db = environment().connect(side);
          var sql = db.createStatement()) {
        String url = mode.equals("external") ? "https://example.test/external.webp" : A;
        if (mode.equals("external") || mode.equals("duplicate-url"))
          try (var q =
              db.prepareStatement("UPDATE places SET \"imageUrls\"=ARRAY[?,?,?] WHERE id=?")) {
            q.setString(1, url);
            q.setString(2, mode.equals("duplicate-url") ? A : B);
            q.setString(3, B);
            q.setString(4, placeId);
            q.executeUpdate();
          }
        if (mode.equals("missing-file") || mode.equals("directory")) {
          if (side == SharedHarness.Side.V2)
            environment()
                .nodeCheck(
                    "const fs=require('fs'),i=JSON.parse(process.argv[1]);fs.unlinkSync('/app'+i.url);if(i.directory)fs.mkdirSync('/app'+i.url);console.log('{}')",
                    Map.of("url", A, "directory", mode.equals("directory")));
          else {
            var path = environment().uploads.resolve("places/old-a.webp");
            Files.delete(path);
            if (mode.equals("directory")) Files.createDirectory(path);
          }
        }
        boolean injected = Set.of("update", "commit").contains(mode);
        var before = DatabaseSnapshot.capture(db, PlaceWriteSupport.rules());
        var beforeFiles = files(side);
        if (injected) {
          sql.execute(
              "CREATE FUNCTION image_delete_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected'; END; $$");
          sql.execute(
              mode.equals("commit")
                  ? "CREATE CONSTRAINT TRIGGER image_delete_failure AFTER UPDATE ON places DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION image_delete_failure()"
                  : "CREATE TRIGGER image_delete_failure BEFORE UPDATE ON places FOR EACH ROW EXECUTE FUNCTION image_delete_failure()");
        }
        try {
          var response =
              request(
                  base(side), uploadPath(), HttpMethod.DELETE, token(1), Map.of("imageUrl", url));
          assertThat(response.getStatusCode().value()).isEqualTo(injected ? 500 : 200);
          var body = normalized(response.getBody());
          if (first == null) first = body;
          else assertThat(body).isEqualTo(first);
          if (injected) {
            before.assertUnchanged(DatabaseSnapshot.capture(db, PlaceWriteSupport.rules()));
            assertThat(files(side)).isEqualTo(beforeFiles);
          } else {
            assertDatabaseResponse(side, response.getBody());
            assertThat(urls(side)).doesNotContain(url);
            if (mode.equals("external")) assertThat(files(side)).isEqualTo(beforeFiles);
            else assertThat(files(side)).containsOnlyKeys(B);
            if (mode.equals("directory"))
              assertThat(
                      side == SharedHarness.Side.V2
                          ? environment()
                              .nodeCheck(
                                  "console.log(JSON.stringify(require('fs').statSync('/app/uploads/places/old-a.webp').isDirectory()))",
                                  Map.of())
                              .asBoolean()
                          : Files.isDirectory(environment().uploads.resolve("places/old-a.webp")))
                  .isTrue();
          }
        } finally {
          if (injected) {
            sql.execute("DROP TRIGGER image_delete_failure ON places");
            sql.execute("DROP FUNCTION image_delete_failure()");
          }
        }
      }
  }
}
