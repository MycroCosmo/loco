package com.loco.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.loco.compat.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
class PlaceImageObservationTest extends PlaceImageObservationSupport {
  @Test
  void uploadInputBoundaries() throws Exception {
    environment().reserveV2Requests("POST", uploadPath(), INPUTS.size() + 1);
    var rows = mapper.createArrayNode();
    for (String input : INPUTS) {
      seed(2);
      var response = uploadCase(v2Url(), input, token(1));
      var row = observation(input, response);
      if (response.getStatusCode().is2xxSuccessful()) {
        String url = response.getBody().path("imageUrls").get(2).asText();
        row.set(
            "image",
            environment()
                .nodeCheck(
                    "const i=JSON.parse(process.argv[1]);require('sharp')('/app'+i.url).metadata().then(m=>console.log(JSON.stringify({format:m.format,width:m.width,height:m.height})))",
                    Map.of("url", url)));
      }
      rows.add(row);
    }
    seed(0);
    var response =
        upload(
            v2Url(),
            List.of(new Part("file", "large.png", "image/png", png(2400, 1600))),
            token(1),
            "normal");
    var row = observation("2400x1600", response);
    row.set(
        "image",
        environment()
            .nodeCheck(
                "const i=JSON.parse(process.argv[1]);require('sharp')('/app'+i.url).metadata().then(m=>console.log(JSON.stringify({format:m.format,width:m.width,height:m.height})))",
                Map.of("url", response.getBody().path("imageUrls").get(0).asText())));
    rows.add(row);
    record("input-v2.json", rows);
  }

  @Test
  void permissionsAndValidationOrder() throws Exception {
    environment().reserveV2Requests("POST", uploadPath(), 15);
    environment().reserveV2Requests("DELETE", uploadPath(), 12);
    var rows = mapper.createArrayNode();
    for (String scenario :
        List.of(
            "missing-place",
            "other-author",
            "private-former-author",
            "private-other",
            "three-images",
            "three-corrupt",
            "three-missing",
            "missing-place-no-file",
            "other-author-bad-mime",
            "unauthenticated",
            "deleted-user",
            "missing-user")) {
      seed(scenario.startsWith("three") ? 3 : 2);
      int user = 1;
      String input = "png";
      if (scenario.startsWith("missing-place")) placeId = "missing";
      if (scenario.startsWith("other-author")) user = 2;
      if (scenario.startsWith("private")) selectPrivatePlace();
      if (scenario.equals("private-other")) user = 2;
      if (scenario.endsWith("no-file") || scenario.equals("three-missing")) input = "missing";
      if (scenario.equals("three-corrupt")) input = "corrupt";
      if (scenario.endsWith("bad-mime") || scenario.equals("unauthenticated"))
        input = "forbidden-mime";
      String auth =
          scenario.equals("unauthenticated")
              ? null
              : scenario.equals("deleted-user")
                  ? token(4)
                  : scenario.equals("missing-user")
                      ? environment()
                          .token(
                              "valid", "99999999-9999-4999-8999-999999999999", "none@example.test")
                      : token(user);
      rows.add(observation(scenario, uploadCase(v2Url(), input, auth)));
    }
    for (String scenario :
        List.of(
            "missing-place",
            "missing-place-no-url",
            "other-author",
            "private-former-author",
            "private-owner-not-author",
            "unauthenticated",
            "deleted-user",
            "missing-user")) {
      seed(2);
      int user = 1;
      if (scenario.startsWith("missing-place")) placeId = "missing";
      if (scenario.equals("other-author")) user = 2;
      if (scenario.startsWith("private")) selectPrivatePlace();
      if (scenario.equals("private-owner-not-author")) user = 2;
      String auth =
          scenario.equals("unauthenticated")
              ? null
              : scenario.equals("deleted-user")
                  ? token(4)
                  : scenario.equals("missing-user")
                      ? environment()
                          .token(
                              "valid", "99999999-9999-4999-8999-999999999999", "none@example.test")
                      : token(user);
      rows.add(
          observation(
              "delete-" + scenario,
              request(
                  v2Url(),
                  uploadPath(),
                  HttpMethod.DELETE,
                  auth,
                  scenario.endsWith("no-url") ? Map.of() : Map.of("imageUrl", A))));
    }
    record("permissions-v2.json", rows);
  }

  @Test
  void deleteInputs() throws Exception {
    environment().reserveV2Requests("DELETE", uploadPath(), 18);
    var rows = mapper.createArrayNode();
    for (String input :
        List.of(
            "{}",
            "{\"imageUrl\":null}",
            "{\"imageUrl\":\"\"}",
            "{\"imageUrl\":false}",
            "{\"imageUrl\":0}",
            "{\"imageUrl\":1}",
            "{\"imageUrl\":true}",
            "{\"imageUrl\":[]}",
            "{\"imageUrl\":{}}",
            "{\"imageUrl\":\" \"}",
            "{\"imageUrl\":\"/uploads/places/missing.webp\"}",
            "{\"imageUrl\":\"" + A + "\",\"unknown\":1}",
            "[]")) {
      seed(2);
      rows.add(observation(input, delete(mapper.readTree(input), 1)));
    }
    seed(2);
    delete(Map.of("imageUrl", A), 1);
    rows.add(observation("repeat", delete(Map.of("imageUrl", A), 1)));
    record("delete-v2.json", rows);
  }

  @Test
  void failuresAndCleanup() throws Exception {
    environment().reserveV2Requests("POST", uploadPath(), 5);
    environment().reserveV2Requests("DELETE", uploadPath(), 7);
    var rows = mapper.createArrayNode();
    for (String method : List.of("POST", "DELETE"))
      for (String phase : List.of("update", "commit")) {
        seed(2);
        try (var db = environment().connect(SharedHarness.Side.V2);
            var sql = db.createStatement()) {
          var before = DatabaseSnapshot.capture(db, PlaceWriteSupport.rules());
          sql.execute(
              "CREATE FUNCTION image_observe_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected'; END; $$");
          sql.execute(
              phase.equals("commit")
                  ? "CREATE CONSTRAINT TRIGGER image_observe_fail AFTER UPDATE ON places DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION image_observe_fail()"
                  : "CREATE TRIGGER image_observe_fail BEFORE UPDATE ON places FOR EACH ROW EXECUTE FUNCTION image_observe_fail()");
          try {
            var response =
                method.equals("POST")
                    ? uploadCase(v2Url(), "png", token(1))
                    : delete(Map.of("imageUrl", A), 1);
            assertThat(response.getStatusCode().value()).isEqualTo(500);
            before.assertUnchanged(DatabaseSnapshot.capture(db, PlaceWriteSupport.rules()));
            rows.add(observation(method + "-db-" + phase, response));
          } finally {
            sql.execute("DROP TRIGGER image_observe_fail ON places");
            sql.execute("DROP FUNCTION image_observe_fail()");
          }
        }
      }
    seed(2);
    environment()
        .nodeCheck(
            "const fs=require('fs');fs.renameSync('/app/uploads/places','/app/uploads/places-observe-saved');fs.writeFileSync('/app/uploads/places','blocked');console.log('{}')",
            Map.of());
    try {
      var response = uploadCase(v2Url(), "png", token(1));
      assertThat(response.getStatusCode().value()).isEqualTo(500);
      environment()
          .nodeCheck(
              "const fs=require('fs');fs.unlinkSync('/app/uploads/places');fs.renameSync('/app/uploads/places-observe-saved','/app/uploads/places');console.log('{}')",
              Map.of());
      rows.add(observation("POST-storage-failure", response));
    } finally {
      environment()
          .nodeCheck(
              "const fs=require('fs');if(fs.existsSync('/app/uploads/places-observe-saved')){fs.unlinkSync('/app/uploads/places');fs.renameSync('/app/uploads/places-observe-saved','/app/uploads/places')}console.log('{}')",
              Map.of());
    }
    for (String kind : List.of("missing-file", "directory", "external", "duplicate-url")) {
      seed(2);
      String url = A;
      if (kind.equals("missing-file") || kind.equals("directory"))
        environment()
            .nodeCheck(
                "const fs=require('fs'),i=JSON.parse(process.argv[1]);fs.unlinkSync('/app'+i.url);if(i.directory)fs.mkdirSync('/app'+i.url);console.log('{}')",
                Map.of("url", A, "directory", kind.equals("directory")));
      if (kind.equals("external")) url = "https://example.test/external.webp";
      if (kind.equals("external") || kind.equals("duplicate-url"))
        try (var db = environment().connect(SharedHarness.Side.V2);
            var q =
                db.prepareStatement("UPDATE places SET \"imageUrls\"=ARRAY[?,?,?] WHERE id=?")) {
          q.setString(1, url);
          q.setString(2, kind.equals("duplicate-url") ? A : B);
          q.setString(3, B);
          q.setString(4, placeId);
          q.executeUpdate();
        }
      var beforeState = state();
      var row = observation("DELETE-" + kind, delete(Map.of("imageUrl", url), 1));
      row.set("beforeState", beforeState);
      rows.add(row);
    }
    record("failure-v2.json", rows);
  }
}
