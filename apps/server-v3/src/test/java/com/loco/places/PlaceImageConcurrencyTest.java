package com.loco.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.loco.compat.*;
import com.loco.maps.SqlQueryCounter;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.context.annotation.Import;

@Import(SqlQueryCounter.Configuration.class)
class PlaceImageConcurrencyTest extends PlaceImageTestSupport {
  protected boolean usesV2Http() {
    return false;
  }

  static java.util.stream.Stream<Arguments> initialAndRequests() {
    return java.util.stream.IntStream.rangeClosed(0, 3)
        .boxed()
        .flatMap(
            initial -> java.util.stream.Stream.of(2, 4, 8, 16).map(n -> Arguments.of(initial, n)));
  }

  @ParameterizedTest
  @MethodSource("initialAndRequests")
  void atomicCapacityAndOnlyOwnFileCompensation(int initial, int n) throws Exception {
    seedBoth(initial);
    var side = SharedHarness.Side.V3;
    var before = files(side);
    var actions =
        new ArrayList<
            java.util.concurrent.Callable<
                org.springframework.http.ResponseEntity<
                    com.fasterxml.jackson.databind.JsonNode>>>();
    for (int i = 0; i < n; i++) actions.add(uploadAction(side));
    try (var db = environment().connect(side)) {
      var other = DatabaseSnapshot.capture(db, otherFields());
      SqlQueryCounter.reset();
      // 최대 4개 UPDATE만 gate에서 기다린다. N=16에서도 기본 연결 풀을 인위적으로 늘리지 않는다.
      var responses =
          ImageRequestRaces.run(
              environment(),
              side,
              placeId,
              actions,
              initial == 3 ? 0 : Math.min(n, 4),
              false,
              c -> {});
      int success = Math.min(n, 3 - initial);
      assertThat(responses.stream().filter(r -> r.getStatusCode().value() == 201)).hasSize(success);
      assertThat(responses.stream().filter(r -> r.getStatusCode().value() == 400))
          .hasSize(n - success);
      for (var response : responses)
        if (response.getStatusCode().value() == 400)
          assertThat(response.getBody())
              .isEqualTo(
                  mapper.readTree(
                      "{\"statusCode\":400,\"message\":\"최대 3장까지 업로드 가능합니다.\",\"error\":\"Bad Request\"}"));
      if (initial == 2 && n == 2) assertThat(SqlQueryCounter.statements()).hasSize(8);
      if (initial == 3) assertThat(SqlQueryCounter.statements()).hasSize(n * 2);
      assertThat(urls(side)).hasSize(initial + success);
      var after = files(side);
      assertThat(after.keySet()).containsExactlyInAnyOrderElementsOf(urls(side));
      before.forEach((url, data) -> assertThat(after.get(url)).isEqualTo(data));
      for (var file : after.values())
        assertThat(
                new com.loco.images.codec.ImageIoConverter()
                    .read(Base64.getDecoder().decode(file))
                    .getWidth())
            .isEqualTo(64);
      other.assertUnchanged(DatabaseSnapshot.capture(db, otherFields()));
      assertThat(summary(side, responses))
          .containsEntry("brokenUrls", 0L)
          .containsEntry("orphanFiles", 0L)
          .containsEntry("lostUploadedUrls", 0);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"different-delete", "same-delete", "upload-delete", "delete-upload"})
  void deletionAndMixedRequestsKeepUrlsAndFilesConsistent(String scenario) throws Exception {
    seedBoth(2);
    var side = SharedHarness.Side.V3;
    var actions =
        scenario.endsWith("delete") && scenario.startsWith("upload")
            ? List.of(uploadAction(side), deleteAction(side, A))
            : scenario.equals("delete-upload")
                ? List.of(deleteAction(side, A), uploadAction(side))
                : List.of(
                    deleteAction(side, A),
                    deleteAction(side, scenario.equals("same-delete") ? A : B));
    SqlQueryCounter.reset();
    var responses = ImageRequestRaces.run(environment(), side, placeId, actions, 2, true, c -> {});
    assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode().is2xxSuccessful()).isTrue());
    int count = scenario.equals("different-delete") ? 0 : scenario.equals("same-delete") ? 1 : 2;
    assertThat(urls(side)).hasSize(count).doesNotContain(A);
    if (!scenario.equals("different-delete")) assertThat(urls(side)).contains(B);
    assertThat(files(side).keySet()).containsExactlyInAnyOrderElementsOf(urls(side));
    assertThat(summary(side, responses))
        .containsEntry("brokenUrls", 0L)
        .containsEntry("orphanFiles", 0L)
        .containsEntry("lostUploadedUrls", 0);
    assertThat(SqlQueryCounter.statements()).hasSize(scenario.equals("same-delete") ? 9 : 8);
  }

  @ParameterizedTest
  @ValueSource(strings = {"place-deleted", "author-changed"})
  void finalRejectionRechecksTheReasonAndRemovesOnlyThisRequestFile(String reason)
      throws Exception {
    seedBoth(2);
    var side = SharedHarness.Side.V3;
    var before = files(side);
    SqlQueryCounter.reset();
    var responses =
        ImageRequestRaces.run(
            environment(),
            side,
            placeId,
            List.of(uploadAction(side)),
            1,
            true,
            c -> {
              try (var q =
                  c.prepareStatement(
                      reason.equals("place-deleted")
                          ? "DELETE FROM places WHERE id=?"
                          : "UPDATE places SET \"createdById\"='22222222-2222-4222-8222-222222222222' WHERE id=?")) {
                q.setString(1, placeId);
                q.executeUpdate();
              }
            });
    assertThat(responses.getFirst().getStatusCode().value())
        .isEqualTo(reason.equals("place-deleted") ? 404 : 403);
    assertThat(files(side)).isEqualTo(before);
    assertThat(SqlQueryCounter.statements()).hasSize(4);
  }
}
