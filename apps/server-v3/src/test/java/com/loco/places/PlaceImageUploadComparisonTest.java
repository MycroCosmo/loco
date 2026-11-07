package com.loco.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.loco.compat.*;
import com.loco.images.codec.ImageIoConverter;
import com.loco.maps.SqlQueryCounter;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.context.annotation.Import;

@Tag("v2-comparison")
@Import(SqlQueryCounter.Configuration.class)
class PlaceImageUploadComparisonTest extends PlaceImageTestSupport {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void veryThinImageKeepsOnePixel(boolean landscape) throws Exception {
    seedBoth(0);
    for (var side : sides()) {
      var response =
          upload(
              base(side),
              List.of(
                  new Part(
                      "file",
                      "thin.png",
                      "image/png",
                      png(landscape ? 3000 : 1, landscape ? 1 : 3000))),
              token(1),
              "normal");
      assertThat(response.getStatusCode().value()).isEqualTo(201);
      var image =
          new ImageIoConverter()
              .read(Base64.getDecoder().decode(files(side).get(urls(side).getFirst())));
      assertThat(image.getWidth()).isEqualTo(landscape ? 1200 : 1);
      assertThat(image.getHeight()).isEqualTo(landscape ? 1 : 1200);
    }
  }

  static java.util.stream.Stream<JsonNode> inputs() throws Exception {
    return fixtures("input");
  }

  @ParameterizedTest
  @MethodSource("inputs")
  void uploadResponseDatabaseAndImage(JsonNode expected) throws Exception {
    String input = expected.path("case").asText();
    seedBoth(input.equals("2400x1600") ? 0 : 2);
    byte[] first = null;
    for (var side : sides())
      try (var db = environment().connect(side)) {
        var unchanged = DatabaseSnapshot.capture(db, otherFields());
        var original = DatabaseSnapshot.capture(db, PlaceWriteSupport.rules());
        var before = files(side);
        SqlQueryCounter.reset();
        var response =
            input.equals("2400x1600")
                ? upload(
                    base(side),
                    List.of(new Part("file", "large.png", "image/png", png(2400, 1600))),
                    token(1),
                    "normal")
                : uploadCase(base(side), input, token(1));
        int status = expected.path("status").asInt();
        assertThat(response.getStatusCode().value()).as(input).isEqualTo(status);
        assertThat(normalized(response.getBody())).as(input).isEqualTo(expected.get("body"));
        if (side == SharedHarness.Side.V3)
          assertThat(SqlQueryCounter.statements())
              .as(input + " SQL")
              .hasSize(
                  status == 201
                      ? 4
                      : Set.of("empty", "corrupt", "gif-as-png", "heic").contains(input) ? 2 : 1);
        unchanged.assertUnchanged(DatabaseSnapshot.capture(db, otherFields()));
        var after = files(side);
        before.forEach((url, data) -> assertThat(after.get(url)).isEqualTo(data));
        if (status != 201) {
          original.assertUnchanged(DatabaseSnapshot.capture(db, PlaceWriteSupport.rules()));
          if (input.equals("heic")) {
            var added = new HashSet<>(after.keySet());
            added.removeAll(before.keySet());
            assertThat(added).hasSize(1);
            assertThat(after.get(added.iterator().next())).isEmpty();
          } else assertThat(after).isEqualTo(before);
        } else {
          assertDatabaseResponse(side, response.getBody());
          var resultUrls = urls(side);
          assertThat(after.keySet()).containsExactlyInAnyOrderElementsOf(resultUrls);
          String url = resultUrls.getLast();
          assertThat(url).matches("/uploads/places/[0-9a-f-]{36}\\.webp");
          byte[] bytes = Base64.getDecoder().decode(after.get(url));
          var converter = new ImageIoConverter();
          var image = converter.read(bytes);
          assertThat(converter.metadata(bytes).format()).isEqualTo("webp");
          assertThat(image.getWidth()).isEqualTo(expected.path("image").path("width").asInt());
          assertThat(image.getHeight()).isEqualTo(expected.path("image").path("height").asInt());
          if (first == null) first = bytes;
          else {
            var left = converter.read(first);
            for (double fraction : new double[] {0.25, 0.75})
              for (double vertical : new double[] {0.25, 0.75}) {
                int x = (int) (image.getWidth() * fraction),
                    y = (int) (image.getHeight() * vertical);
                for (int shift : new int[] {0, 8, 16, 24})
                  assertThat(
                          Math.abs(
                              ((left.getRGB(x, y) >> shift) & 255)
                                  - ((image.getRGB(x, y) >> shift) & 255)))
                      .isLessThanOrEqualTo(24);
              }
          }
        }
      }
  }

  static java.util.stream.Stream<JsonNode> permissions() throws Exception {
    return fixtures("permissions").filter(r -> !r.path("case").asText().startsWith("delete-"));
  }

  @ParameterizedTest
  @MethodSource("permissions")
  void permissionsAndOrder(JsonNode expected) throws Exception {
    String name = expected.path("case").asText();
    seedBoth(name.startsWith("three") ? 3 : 2);
    if (name.startsWith("missing-place")) placeId = "missing";
    if (name.startsWith("private")) privateBoth();
    String input =
        name.endsWith("no-file") || name.equals("three-missing")
            ? "missing"
            : name.equals("three-corrupt")
                ? "corrupt"
                : name.endsWith("bad-mime") || name.equals("unauthenticated")
                    ? "forbidden-mime"
                    : "png";
    int user = name.startsWith("other-author") || name.equals("private-other") ? 2 : 1;
    String auth =
        name.equals("unauthenticated")
            ? null
            : name.equals("deleted-user")
                ? token(4)
                : name.equals("missing-user")
                    ? environment()
                        .token(
                            "valid", "99999999-9999-4999-8999-999999999999", "missing@example.test")
                    : token(user);
    for (var side : sides())
      try (var db = environment().connect(side)) {
        var before = DatabaseSnapshot.capture(db, PlaceWriteSupport.rules());
        var beforeFiles = files(side);
        SqlQueryCounter.reset();
        var response = uploadCase(base(side), input, auth);
        assertThat(response.getStatusCode().value()).isEqualTo(expected.path("status").asInt());
        assertThat(normalized(response.getBody())).isEqualTo(expected.get("body"));
        if (side == SharedHarness.Side.V3)
          assertThat(SqlQueryCounter.statements())
              .hasSize(
                  name.equals("unauthenticated")
                      ? 0
                      : input.equals("missing")
                              || input.equals("forbidden-mime")
                              || name.endsWith("user")
                          ? 1
                          : response.getStatusCode().value() == 201 ? 4 : 2);
        if (response.getStatusCode().isError()) {
          before.assertUnchanged(DatabaseSnapshot.capture(db, PlaceWriteSupport.rules()));
          assertThat(files(side)).isEqualTo(beforeFiles);
        } else {
          assertDatabaseResponse(side, response.getBody());
          assertThat(files(side).keySet()).containsExactlyInAnyOrderElementsOf(urls(side));
        }
      }
  }
}
