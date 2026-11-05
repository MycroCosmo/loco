package com.loco.users;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loco.compat.SharedHarness;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("v2-comparison")
class ProfileImageObservationTest extends com.loco.compat.ImageUploadRequests {
  @Override
  protected String uploadPath() {
    return "/api/users/me/profile-image";
  }

  @Test
  void observeUploadResponses() throws Exception {
    if (Boolean.getBoolean("users.observe")) {
      byte[] animated = animatedWebp();
      var path = Path.of("build/reports/users/animated.webp");
      Files.createDirectories(path.getParent());
      Files.write(path, animated);
      var observed =
          environment()
              .nodeCheck(
                  "const b=Buffer.from(JSON.parse(process.argv[1]).data,'base64'),s=require('sharp');Promise.all([s(b).metadata(),s(b).raw().toBuffer({resolveWithObject:true})]).then(([m,r])=>console.log(JSON.stringify({format:'animated-webp',sha256:require('crypto').createHash('sha256').update(b).digest('hex'),metadata:{format:m.format,width:m.width,height:m.height,pages:m.pages},read:true,pixel:Array.from(r.data.subarray(0,3))})))",
                  Map.of("data", java.util.Base64.getEncoder().encodeToString(animated)));
      assertThat(observed.path("metadata").path("pages").asInt()).isEqualTo(2);
      for (int channel = 0; channel < 3; channel++)
        assertThat(Math.abs(observed.path("pixel").get(channel).asInt() - (channel == 0 ? 255 : 0)))
            .isLessThanOrEqualTo(24);
      mapper
          .writerWithDefaultPrettyPrinter()
          .writeValue(path.resolveSibling("animated-v2.json").toFile(), observed);
    }
    var rows = mapper.createArrayNode();
    for (String input : INPUTS) {
      environment().reset("compat/seed.sql");
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
      if (body.has("profileImageUrl")) {
        String url = body.path("profileImageUrl").asText();
        assertThat(url).matches("/uploads/profiles/[0-9a-f-]{36}\\.webp");
        var metadata =
            environment()
                .nodeCheck(
                    "const i=JSON.parse(process.argv[1]); require('sharp')('/app'+i.url).metadata().then(m=>console.log(JSON.stringify({width:m.width,height:m.height,format:m.format})))",
                    Map.of("url", url));
        row.set("image", metadata);
        ((ObjectNode) body).put("profileImageUrl", "<image-url>");
        ((ObjectNode) body).put("updatedAt", "<timestamp>");
      }
      row.set("body", body);
    }
    var path = Path.of("build/reports/users/image-v2.json");
    Files.createDirectories(path.getParent());
    mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), rows);
    if (!Boolean.getBoolean("users.observe"))
      try (var fixture = getClass().getResourceAsStream("/users/image-v2.json")) {
        assertThat(rows).isEqualTo(mapper.readTree(fixture));
      }
  }
}
