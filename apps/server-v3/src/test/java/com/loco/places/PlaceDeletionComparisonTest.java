package com.loco.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.loco.compat.*;
import com.loco.maps.SqlQueryCounter;
import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
@Import(SqlQueryCounter.Configuration.class)
class PlaceDeletionComparisonTest extends CompatibilityTestBase {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "last",
        "shared",
        "former-member",
        "owner",
        "forbidden",
        "missing",
        "repeat",
        "place-failure",
        "commit-failure",
        "cell-1",
        "cell-2",
        "cell-3"
      })
  void deletionCommitCellsReviewsReportsAndFilesMatch(String mode) throws Exception {
    environment().reset("places/seed.sql");
    var rules = PlaceWriteSupport.rules();
    String id =
        PlaceObservationTest.place(
            mode.equals("shared") ? 2 : mode.equals("former-member") ? 7 : 6);
    int expected =
        mode.equals("forbidden")
            ? 403
            : mode.equals("missing")
                ? 404
                : mode.contains("failure") || mode.startsWith("cell-") ? 500 : 200;
    DatabaseSnapshot before = null;
    for (var side : SharedHarness.Side.values())
      try (var db = environment().connect(side)) {
        String base = side == SharedHarness.Side.V2 ? v2Url() : v3Url();
        var sql = db.createStatement();
        sql.execute(
            "UPDATE places SET \"imageUrls\"=ARRAY['/uploads/places/delete-target.webp','/uploads/places/missing.webp','https://example.test/external.webp','/uploads/places/delete-directory','/uploads/../start-v2.cjs'] WHERE id='"
                + id
                + "'");
        if (!mode.equals("shared"))
          sql.execute(
              "INSERT INTO place_reviews(id,\"placeId\",\"userId\",rating,content,\"updatedAt\") VALUES('eeeeeeee-eeee-4eee-8eee-000000000009','"
                  + id
                  + "','22222222-2222-4222-8222-222222222222',4,'delete review','2026-01-01')");
        sql.execute(
            "INSERT INTO reports(id,\"reporterId\",\"targetType\",\"targetId\",reason) VALUES('eeeeeeee-eeee-4eee-8eee-000000000010','22222222-2222-4222-8222-222222222222','PLACE','"
                + id
                + "','keep report')");
        assertThat(
                request(
                        base,
                        "/api/places/" + id,
                        HttpMethod.PUT,
                        PlaceWriteSupport.token(environment()),
                        Map.of())
                    .getStatusCode()
                    .value())
            .isEqualTo(200);
        prepareFiles(side);
        var seeded = DatabaseSnapshot.capture(db, rules);
        if (before == null) before = seeded;
        String table = mode.startsWith("cell-") ? "place_marker_cells" : "places";
        boolean injected = expected == 500;
        int precision = mode.startsWith("cell-") ? Integer.parseInt(mode.substring(5)) : 0;
        if (injected) {
          sql.execute(
              "CREATE FUNCTION deletion_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF "
                  + (precision == 0 ? "true" : "COALESCE(NEW.precision,OLD.precision)=" + precision)
                  + " THEN RAISE EXCEPTION 'injected'; END IF; IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW; END; $$");
          sql.execute(
              mode.equals("commit-failure")
                  ? "CREATE CONSTRAINT TRIGGER deletion_failure AFTER DELETE ON places DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION deletion_failure()"
                  : "CREATE TRIGGER deletion_failure BEFORE INSERT OR UPDATE OR DELETE ON "
                      + table
                      + " FOR EACH ROW EXECUTE FUNCTION deletion_failure()");
        }
        try {
          String token =
              mode.equals("owner")
                  ? environment()
                      .token("valid", "22222222-2222-4222-8222-222222222222", "owner@example.test")
                  : mode.equals("forbidden")
                      ? environment()
                          .token(
                              "valid",
                              "33333333-3333-4333-8333-333333333333",
                              "member@example.test")
                      : PlaceWriteSupport.token(environment());
          SqlQueryCounter.reset();
          var response =
              request(
                  base,
                  "/api/places/" + (mode.equals("missing") ? "missing" : id),
                  HttpMethod.DELETE,
                  token,
                  null);
          assertThat(response.getStatusCode().value()).as(mode).isEqualTo(expected);
          String json =
              expected == 200
                  ? "{\"message\":\"장소가 삭제되었습니다.\"}"
                  : expected == 500
                      ? "{\"statusCode\":500,\"message\":\"Internal server error\"}"
                      : expected == 403
                          ? "{\"statusCode\":403,\"message\":\"삭제 권한이 없습니다.\",\"error\":\"Forbidden\"}"
                          : "{\"statusCode\":404,\"message\":\"장소를 찾을 수 없습니다.\",\"error\":\"Not Found\"}";
          assertThat(response.getBody()).isEqualTo(mapper.readTree(json));
          if (side == SharedHarness.Side.V3)
            assertThat(SqlQueryCounter.statements())
                .hasSize(
                    expected == 200
                        ? 10
                        : expected == 403
                            ? 3
                            : expected == 404 ? 2 : precision == 0 ? 4 : 4 + precision * 2);
          boolean removed = expected == 200 || precision > 0;
          if (!removed) seeded.assertUnchanged(DatabaseSnapshot.capture(db, rules));
          try (var rows =
              sql.executeQuery(
                  "SELECT (SELECT count(*) FROM places WHERE id='"
                      + id
                      + "'),(SELECT count(*) FROM place_reviews WHERE \"placeId\"='"
                      + id
                      + "'),(SELECT count(*) FROM reports)")) {
            rows.next();
            assertThat(rows.getInt(1)).isEqualTo(removed ? 0 : 1);
            assertThat(rows.getInt(2)).isEqualTo(removed ? 0 : mode.equals("shared") ? 2 : 1);
            assertThat(rows.getInt(3)).isEqualTo(1);
          }
          var files = fileState(side);
          assertThat(files.path("target").asBoolean()).isEqualTo(expected != 200);
          assertThat(files.path("keep").asBoolean()).isTrue();
          assertThat(files.path("directory").asBoolean()).isTrue();
          assertThat(files.path("outside").asBoolean()).isTrue();
          if (mode.equals("repeat"))
            assertThat(
                    request(base, "/api/places/" + id, HttpMethod.DELETE, token, null)
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
        } finally {
          if (injected) {
            sql.execute("DROP TRIGGER deletion_failure ON " + table);
            sql.execute("DROP FUNCTION deletion_failure()");
          }
        }
      }
    try (var left = environment().connect(SharedHarness.Side.V2);
        var right = environment().connect(SharedHarness.Side.V3)) {
      DatabaseSnapshot.compare(
          DatabaseSnapshot.capture(left, rules),
          DatabaseSnapshot.capture(right, rules),
          before.seedIds(),
          rules);
    }
  }

  private void prepareFiles(SharedHarness.Side side) throws Exception {
    if (side == SharedHarness.Side.V2)
      environment()
          .nodeCheck(
              "const fs=require('fs');fs.mkdirSync('/app/uploads/places/delete-directory',{recursive:true});for(const p of ['/app/uploads/places/delete-target.webp','/app/uploads/places/delete-keep.webp'])fs.writeFileSync(p,'fixture');console.log('{}')",
              Map.of());
    else {
      Files.createDirectories(environment().uploads.resolve("places/delete-directory"));
      for (String name : new String[] {"places/delete-target.webp", "places/delete-keep.webp"})
        Files.writeString(environment().uploads.resolve(name), "fixture");
      Files.writeString(environment().uploads.resolveSibling("start-v2.cjs"), "fixture");
    }
  }

  private com.fasterxml.jackson.databind.JsonNode fileState(SharedHarness.Side side)
      throws Exception {
    if (side == SharedHarness.Side.V2)
      return environment()
          .nodeCheck(
              "const fs=require('fs');console.log(JSON.stringify({target:fs.existsSync('/app/uploads/places/delete-target.webp'),keep:fs.existsSync('/app/uploads/places/delete-keep.webp'),directory:fs.existsSync('/app/uploads/places/delete-directory'),outside:fs.existsSync('/app/start-v2.cjs')}))",
              Map.of());
    return mapper.valueToTree(
        Map.of(
            "target",
            Files.exists(environment().uploads.resolve("places/delete-target.webp")),
            "keep",
            Files.exists(environment().uploads.resolve("places/delete-keep.webp")),
            "directory",
            Files.exists(environment().uploads.resolve("places/delete-directory")),
            "outside",
            Files.exists(environment().uploads.resolveSibling("start-v2.cjs"))));
  }
}
