package com.loco.users;

import com.fasterxml.jackson.databind.JsonNode;
import com.loco.compat.CompatibilityTestBase;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

abstract class ProfileImageRequests extends CompatibilityTestBase {
  static final List<String> INPUTS =
      List.of(
          "jpeg",
          "png",
          "webp",
          "heic",
          "avif",
          "large-png",
          "narrow-png",
          "wide-png",
          "missing",
          "empty",
          "wrong-field",
          "duplicate",
          "forbidden-mime",
          "gif-as-png",
          "corrupt",
          "oversize",
          "boundary-missing",
          "truncated",
          "at-limit",
          "below-limit",
          "mime-mismatch",
          "empty-filename",
          "extra-form",
          "json-body",
          "transparent-png",
          "exif-jpeg",
          "animated-webp");

  record Part(String field, String filename, String mime, byte[] bytes) {}

  byte[] fixture(String extension) throws Exception {
    try (var stream = getClass().getResourceAsStream("/codec/quadrants." + extension)) {
      return stream.readAllBytes();
    }
  }

  ResponseEntity<JsonNode> uploadCase(String base, String input, String token) throws Exception {
    if (input.equals("json-body"))
      return request(
          base,
          "/api/users/me/profile-image",
          HttpMethod.POST,
          token,
          java.util.Map.of("file", "image.png"));
    byte[] data =
        switch (input) {
          case "jpeg", "png", "webp", "heic", "avif" -> fixture(input);
          case "empty" -> new byte[0];
          case "large-png" -> png(1024, 768);
          case "narrow-png" -> png(100, 800);
          case "wide-png" -> png(800, 100);
          case "at-limit", "below-limit" ->
              java.util.Arrays.copyOf(
                  fixture("png"), 10 * 1024 * 1024 - (input.equals("below-limit") ? 1 : 0));
          case "transparent-png" -> transparentPng();
          case "exif-jpeg" ->
              java.util.Base64.getDecoder()
                  .decode(
                      environment()
                          .nodeCheck(
                              "const i=JSON.parse(process.argv[1]);require('sharp')(Buffer.from(i.data,'base64')).withMetadata({orientation:6}).jpeg().toBuffer().then(b=>console.log(JSON.stringify(b.toString('base64'))))",
                              java.util.Map.of(
                                  "data",
                                  java.util.Base64.getEncoder().encodeToString(fixture("png"))))
                          .asText());
          case "animated-webp" -> animatedWebp();
          case "oversize" -> new byte[10 * 1024 * 1024 + 1];
          case "corrupt" -> "not an image".getBytes(StandardCharsets.UTF_8);
          case "gif-as-png" ->
              java.util.Base64.getDecoder()
                  .decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7");
          default -> fixture("png");
        };
    var part =
        new Part(
            input.equals("wrong-field") ? "other" : "file",
            input.equals("empty-filename") ? "" : "image." + input,
            input.equals("forbidden-mime")
                ? "image/gif"
                : input.equals("mime-mismatch") || input.equals("exif-jpeg")
                    ? "image/jpeg"
                    : input.equals("animated-webp")
                        ? "image/webp"
                        : "image/"
                            + (List.of("jpeg", "webp", "heic", "avif").contains(input)
                                ? input
                                : "png"),
            data);
    var parts =
        input.equals("missing")
            ? List.<Part>of()
            : input.equals("duplicate")
                ? List.of(part, part)
                : input.equals("extra-form")
                    ? List.of(part, new Part("ignored", null, "text/plain", new byte[] {65}))
                    : List.of(part);
    return upload(base, parts, token, input);
  }

  private byte[] transparentPng() throws Exception {
    var image =
        new java.awt.image.BufferedImage(64, 48, java.awt.image.BufferedImage.TYPE_INT_ARGB);
    for (int y = 0; y < 48; y++)
      for (int x = 0; x < 64; x++) image.setRGB(x, y, x < 32 ? 0 : 0x8000ff00);
    var out = new ByteArrayOutputStream();
    javax.imageio.ImageIO.write(image, "png", out);
    return out.toByteArray();
  }

  protected byte[] animatedWebp() throws Exception {
    var out = new ByteArrayOutputStream();
    var writer = javax.imageio.ImageIO.getImageWritersByFormatName("gif").next();
    try (var stream = new javax.imageio.stream.MemoryCacheImageOutputStream(out)) {
      writer.setOutput(stream);
      writer.prepareWriteSequence(null);
      for (int rgb : new int[] {0xff0000, 0x0000ff}) {
        var frame =
            new java.awt.image.BufferedImage(64, 48, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var g = frame.createGraphics();
        g.setColor(new java.awt.Color(rgb));
        g.fillRect(0, 0, 64, 48);
        g.dispose();
        writer.writeToSequence(new javax.imageio.IIOImage(frame, null, null), null);
      }
      writer.endWriteSequence();
      stream.flush();
    } finally {
      writer.dispose();
    }
    return java.util.Base64.getDecoder()
        .decode(
            environment()
                .nodeCheck(
                    "const i=JSON.parse(process.argv[1]);require('sharp')(Buffer.from(i.data,'base64'),{animated:true}).webp().toBuffer().then(b=>console.log(JSON.stringify(b.toString('base64'))))",
                    java.util.Map.of(
                        "data", java.util.Base64.getEncoder().encodeToString(out.toByteArray())))
                .asText());
  }

  byte[] png(int width, int height) throws Exception {
    var image =
        new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
    int[] colors = {0xff0000, 0x00ff00, 0x0000ff, 0xffff00};
    for (int y = 0; y < height; y++)
      for (int x = 0; x < width; x++)
        image.setRGB(x, y, colors[(y >= height / 2 ? 2 : 0) + (x >= width / 2 ? 1 : 0)]);
    var out = new ByteArrayOutputStream();
    javax.imageio.ImageIO.write(image, "png", out);
    return out.toByteArray();
  }

  ResponseEntity<JsonNode> upload(String base, List<Part> parts, String token, String mode)
      throws Exception {
    String boundary = "loco-test-boundary";
    var bytes = new ByteArrayOutputStream();
    for (var part : parts) {
      bytes.write(
          ("--"
                  + boundary
                  + "\r\nContent-Disposition: form-data; name=\""
                  + part.field()
                  + "\""
                  + (part.filename() == null ? "" : "; filename=\"" + part.filename() + "\"")
                  + "\r\nContent-Type: "
                  + part.mime()
                  + "\r\n\r\n")
              .getBytes(StandardCharsets.UTF_8));
      bytes.write(part.bytes());
      bytes.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }
    if (!mode.equals("truncated"))
      bytes.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    var headers = new HttpHeaders();
    headers.set(
        "Content-Type",
        "multipart/form-data" + (mode.equals("boundary-missing") ? "" : "; boundary=" + boundary));
    if (token != null) headers.setBearerAuth(token);
    return client.exchange(
        base + "/api/users/me/profile-image",
        HttpMethod.POST,
        new HttpEntity<>(bytes.toByteArray(), headers),
        JsonNode.class);
  }
}
