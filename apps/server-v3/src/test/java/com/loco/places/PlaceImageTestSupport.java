package com.loco.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.loco.compat.*;
import java.nio.file.*;
import java.util.*;

abstract class PlaceImageTestSupport extends PlaceImageObservationSupport {
  protected List<SharedHarness.Side> sides() {
    return environment().v2Enabled()
        ? List.of(SharedHarness.Side.values())
        : List.of(SharedHarness.Side.V3);
  }

  protected String base(SharedHarness.Side side) {
    return side == SharedHarness.Side.V2 ? v2Url() : v3Url();
  }

  protected void seedBoth(int count) throws Exception {
    if (environment().v2Enabled()) seed(count);
    else {
      placeId = PlaceObservationTest.place(1);
      environment().reset("places/seed.sql");
    }
    Path root = environment().uploads.resolve("places");
    if (!root.startsWith(Path.of("build/harness-uploads").toAbsolutePath().normalize()))
      throw new IllegalStateException("Unsafe test root");
    if (Files.exists(root))
      try (var paths = Files.walk(root)) {
        for (var p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
      }
    Files.createDirectories(root);
    String[] urls = new String[count];
    for (int i = 0; i < count; i++) {
      urls[i] = "/uploads/places/old-" + (char) ('a' + i) + ".webp";
      Files.write(root.resolve("old-" + (char) ('a' + i) + ".webp"), fixture("webp"));
    }
    try (var db = environment().connect(SharedHarness.Side.V3);
        var q = db.prepareStatement("UPDATE places SET \"imageUrls\"=? WHERE id=?")) {
      q.setArray(1, db.createArrayOf("text", urls));
      q.setString(2, placeId);
      q.executeUpdate();
    }
  }

  protected void privateBoth() throws Exception {
    if (environment().v2Enabled()) selectPrivatePlace();
    try (var db = environment().connect(SharedHarness.Side.V3);
        var q =
            db.prepareStatement(
                "UPDATE places SET \"imageUrls\"=(SELECT \"imageUrls\" FROM places WHERE id=?) WHERE id=?")) {
      q.setString(1, PlaceObservationTest.place(1));
      q.setString(2, PlaceObservationTest.place(7));
      q.executeUpdate();
      try (var clear =
          db.prepareStatement("UPDATE places SET \"imageUrls\"=ARRAY[]::text[] WHERE id=?")) {
        clear.setString(1, PlaceObservationTest.place(1));
        clear.executeUpdate();
      }
    }
    placeId = PlaceObservationTest.place(7);
  }

  protected Map<String, String> files(SharedHarness.Side side) throws Exception {
    var result = new TreeMap<String, String>();
    if (side == SharedHarness.Side.V2) {
      environment()
          .nodeCheck(
              "const fs=require('fs'),p='/app/uploads/places',out={};for(const n of fs.readdirSync(p)){if(fs.statSync(p+'/'+n).isFile())out['/uploads/places/'+n]=fs.readFileSync(p+'/'+n).toString('base64')}console.log(JSON.stringify(out))",
              Map.of())
          .fields()
          .forEachRemaining(e -> result.put(e.getKey(), e.getValue().asText()));
    } else
      try (var paths = Files.list(environment().uploads.resolve("places"))) {
        for (var p : paths.filter(Files::isRegularFile).toList())
          result.put(
              "/uploads/places/" + p.getFileName(),
              Base64.getEncoder().encodeToString(Files.readAllBytes(p)));
      }
    return result;
  }

  protected List<String> urls(SharedHarness.Side side) throws Exception {
    try (var db = environment().connect(side);
        var q = db.prepareStatement("SELECT \"imageUrls\" FROM places WHERE id=?")) {
      q.setString(1, placeId);
      try (var row = q.executeQuery()) {
        return row.next() ? List.of((String[]) row.getArray(1).getArray()) : List.of();
      }
    }
  }

  protected DatabaseSnapshot.Rules otherFields() {
    var base = PlaceWriteSupport.rules();
    var queries = new HashMap<>(base.rowQueries());
    queries.put(
        "places",
        "SELECT (jsonb_populate_record(NULL::places,to_jsonb(p) || CASE WHEN id='"
            + placeId
            + "' THEN jsonb_build_object('imageUrls',ARRAY[]::text[],'updatedAt',timestamp '2000-01-01') ELSE '{}'::jsonb END)).* FROM places p");
    return new DatabaseSnapshot.Rules(base.orderBy(), queries);
  }

  protected void assertDatabaseResponse(SharedHarness.Side side, JsonNode body) throws Exception {
    assertThat(body.get("imageUrls")).isEqualTo(mapper.valueToTree(urls(side)));
    try (var db = environment().connect(side);
        var q = db.prepareStatement("SELECT \"updatedAt\" FROM places WHERE id=?")) {
      q.setString(1, placeId);
      try (var row = q.executeQuery()) {
        assertThat(row.next()).isTrue();
        assertThat(body.path("updatedAt").asText())
            .isEqualTo(
                com.loco.users.UserResponses.timestamp(row.getTimestamp(1).toLocalDateTime()));
      }
    }
  }

  protected static java.util.stream.Stream<JsonNode> fixtures(String name) throws Exception {
    try (var in =
        PlaceImageTestSupport.class.getResourceAsStream("/place-images/" + name + "-v2.json")) {
      return java.util.stream.StreamSupport.stream(
          new com.fasterxml.jackson.databind.ObjectMapper().readTree(in).spliterator(), false);
    }
  }
}
