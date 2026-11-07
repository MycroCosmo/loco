package com.loco.maps;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loco.compat.SharedHarness;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("v2-comparison")
class MapCoverObservationTest extends com.loco.compat.ImageUploadRequests {
  @Override
  protected String uploadPath() {
    return "/api/maps/aaaaaaaa-aaaa-4aaa-8aaa-000000000001/cover";
  }

  @Test
  void observeUploadResponses() throws Exception {
    var rows = mapper.createArrayNode();
    for (String input : INPUTS) {
      environment().reset("maps/seed.sql");
      var response =
          uploadCase(
              v2Url(),
              input,
              environment()
                  .token(
                      "valid", SharedHarness.Environment.USER_ID, SharedHarness.Environment.EMAIL));
      var row =
          rows.addObject().put("input", input).put("status", response.getStatusCode().value());
      var body = response.getBody().deepCopy();
      if (body.has("coverImageUrl")) {
        String url = body.path("coverImageUrl").asText();
        assertThat(url).matches("/uploads/maps/[0-9a-f-]{36}\\.webp");
        var metadata =
            environment()
                .nodeCheck(
                    "const i=JSON.parse(process.argv[1]); require('sharp')('/app'+i.url).metadata().then(m=>console.log(JSON.stringify({width:m.width,height:m.height,format:m.format})))",
                    Map.of("url", url));
        row.set("image", metadata);
        ((ObjectNode) body).put("coverImageUrl", "<image-url>");
        ((ObjectNode) body).put("updatedAt", "<timestamp>");
      }
      row.set("body", body);
    }
    var path = Path.of("build/reports/maps/cover-v2.json");
    Files.createDirectories(path.getParent());
    mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), rows);
    if (!Boolean.getBoolean("maps.observe"))
      try (var fixture = getClass().getResourceAsStream("/maps/cover-v2.json")) {
        assertThat(rows).isEqualTo(mapper.readTree(fixture));
      }
  }
}
