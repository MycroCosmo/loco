package com.loco.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loco.compat.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.http.*;

abstract class PlaceImageObservationSupport extends ImageUploadRequests {
  protected String placeId = PlaceObservationTest.place(1);
  protected static final String A = "/uploads/places/old-a.webp", B = "/uploads/places/old-b.webp";

  protected String uploadPath() {
    return "/api/places/" + placeId + "/images";
  }

  protected String token(int user) throws Exception {
    return environment().token("valid", ReviewTestSupport.user(user), "ignored@example.test");
  }

  protected void seed(int count) throws Exception {
    placeId = PlaceObservationTest.place(1);
    environment().reset("places/seed.sql");
    environment()
        .nodeCheck(
            """
        const fs=require('fs'),i=JSON.parse(process.argv[1]),root='/app/uploads/places';
        fs.rmSync(root,{recursive:true,force:true});fs.mkdirSync(root,{recursive:true});
        for(let n=0;n<i.count;n++)fs.writeFileSync(root+'/old-'+String.fromCharCode(97+n)+'.webp',Buffer.from(i.data,'base64'));
        console.log('{}');
        """,
            Map.of("count", count, "data", Base64.getEncoder().encodeToString(fixture("webp"))));
    try (var db = environment().connect(SharedHarness.Side.V2);
        var q = db.prepareStatement("UPDATE places SET \"imageUrls\"=? WHERE id=?")) {
      String[] urls =
          java.util.stream.IntStream.range(0, count)
              .mapToObj(n -> "/uploads/places/old-" + (char) ('a' + n) + ".webp")
              .toArray(String[]::new);
      q.setArray(1, db.createArrayOf("text", urls));
      q.setString(2, placeId);
      q.executeUpdate();
    }
  }

  protected ResponseEntity<JsonNode> delete(Object input, int user) throws Exception {
    return request(v2Url(), uploadPath(), HttpMethod.DELETE, token(user), input);
  }

  protected void selectPrivatePlace() throws Exception {
    try (var db = environment().connect(SharedHarness.Side.V2);
        var q =
            db.prepareStatement(
                "UPDATE places SET \"imageUrls\"=(SELECT \"imageUrls\" FROM places WHERE id=?) WHERE id=?")) {
      q.setString(1, placeId);
      q.setString(2, PlaceObservationTest.place(7));
      q.executeUpdate();
      try (var clear =
          db.prepareStatement("UPDATE places SET \"imageUrls\"=ARRAY[]::text[] WHERE id=?")) {
        clear.setString(1, placeId);
        clear.executeUpdate();
      }
    }
    placeId = PlaceObservationTest.place(7);
  }

  protected ObjectNode state() throws Exception {
    var result = mapper.createObjectNode();
    var urls = result.putArray("urls");
    var allReferences = new HashSet<String>();
    try (var db = environment().connect(SharedHarness.Side.V2);
        var q = db.prepareStatement("SELECT \"imageUrls\",\"updatedAt\" FROM places WHERE id=?")) {
      q.setString(1, placeId);
      try (var row = q.executeQuery()) {
        if (row.next()) {
          for (String url : (String[]) row.getArray(1).getArray()) urls.add(url);
          result.put(
              "updated",
              !row.getTimestamp(2)
                  .toLocalDateTime()
                  .equals(java.time.LocalDateTime.parse("2026-01-01T00:00:00")));
        }
      }
      try (var query = db.createStatement();
          var refs = query.executeQuery("SELECT unnest(\"imageUrls\") FROM places")) {
        while (refs.next()) allReferences.add(refs.getString(1));
      }
    }
    var files =
        environment()
            .nodeCheck(
                """
        const fs=require('fs'),p='/app/uploads/places';let out={};
        if(fs.existsSync(p)&&fs.statSync(p).isDirectory())for(const n of fs.readdirSync(p).sort()){
          const s=fs.statSync(p+'/'+n);out['/uploads/places/'+n]=s.isDirectory()?{kind:'directory'}:{kind:'file',bytes:s.size,sha256:require('crypto').createHash('sha256').update(fs.readFileSync(p+'/'+n)).digest('hex')};
        }console.log(JSON.stringify(out));
        """,
                Map.of());
    result.set("files", files);
    var broken = result.putArray("brokenUrls");
    for (var u : urls)
      if (!files.has(u.asText()) || !files.get(u.asText()).path("kind").asText().equals("file"))
        broken.add(u.asText());
    var orphan = result.putArray("orphanFiles");
    files
        .fieldNames()
        .forEachRemaining(
            url -> {
              if (files.get(url).path("kind").asText().equals("file")
                  && !allReferences.contains(url)) orphan.add(url);
            });
    return result;
  }

  protected JsonNode normalized(JsonNode raw) throws Exception {
    String text = mapper.writeValueAsString(raw);
    var matcher =
        java.util.regex.Pattern.compile("/uploads/places/[0-9a-f]{8}-[0-9a-f-]{27}\\.webp")
            .matcher(text);
    var aliases = new LinkedHashMap<String, String>();
    while (matcher.find())
      aliases.computeIfAbsent(
          matcher.group(), key -> "/uploads/places/$new" + (aliases.size() + 1) + ".webp");
    for (var entry : aliases.entrySet()) text = text.replace(entry.getKey(), entry.getValue());
    var value = mapper.readTree(text);
    normalizeTime(value);
    return value;
  }

  private void normalizeTime(JsonNode n) {
    if (n instanceof ObjectNode object)
      for (String field : List.of("createdAt", "updatedAt"))
        if (object.has(field)) {
          java.time.Instant.parse(object.path(field).asText());
          object.put(field, "$time");
        }
    n.forEach(this::normalizeTime);
  }

  protected ObjectNode observation(String name, ResponseEntity<JsonNode> response)
      throws Exception {
    var row =
        mapper.createObjectNode().put("case", name).put("status", response.getStatusCode().value());
    row.set("body", response.getBody());
    row.set("state", state());
    return (ObjectNode) normalized(row);
  }

  protected void record(String filename, JsonNode value) throws Exception {
    Path output = Path.of("build/reports/place-images", filename);
    Files.createDirectories(output.getParent());
    mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), value);
    if (!Boolean.getBoolean("placeImages.observe"))
      try (var in = getClass().getResourceAsStream("/place-images/" + filename)) {
        assertThat(in).as(filename).isNotNull();
        assertThat(value).isEqualTo(mapper.readTree(in));
      }
  }
}
