package com.loco.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.loco.compat.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("v2-comparison")
class PlaceImageDifferenceComparisonTest extends PlaceImageTestSupport {
  @ParameterizedTest
  @ValueSource(strings = {"concurrent-limit", "concurrent-delete", "concurrent-upload-delete"})
  void reportImprovementWithBothActualResults(String scenario) throws Exception {
    seedBoth(2);
    environment().reserveV2Requests("POST", uploadPath(), 2);
    environment().reserveV2Requests("DELETE", uploadPath(), 2);
    var results = new ArrayList<Map<String, Object>>();
    for (var side : sides()) {
      var actions =
          scenario.equals("concurrent-limit")
              ? List.of(uploadAction(side), uploadAction(side))
              : scenario.equals("concurrent-delete")
                  ? List.of(deleteAction(side, A), deleteAction(side, B))
                  : List.of(uploadAction(side), deleteAction(side, A));
      var responses =
          ImageRequestRaces.run(environment(), side, placeId, actions, 2, true, c -> {});
      var result = summary(side, responses);
      results.add(result);
      int images =
          scenario.equals("concurrent-limit")
              ? (side == SharedHarness.Side.V2 ? 4 : 3)
              : scenario.equals("concurrent-delete")
                  ? (side == SharedHarness.Side.V2 ? 1 : 0)
                  : (side == SharedHarness.Side.V2 ? 1 : 2);
      assertThat(result)
          .containsEntry("images", images)
          .containsEntry(
              "brokenUrls",
              side == SharedHarness.Side.V2 && scenario.equals("concurrent-delete") ? 1L : 0L)
          .containsEntry(
              "orphanFiles",
              side == SharedHarness.Side.V2 && scenario.equals("concurrent-upload-delete")
                  ? 1L
                  : 0L)
          .containsEntry(
              "lostUploadedUrls",
              side == SharedHarness.Side.V2 && scenario.equals("concurrent-upload-delete") ? 1 : 0);
      assertThat(result.get("statuses"))
          .isEqualTo(
              scenario.equals("concurrent-limit")
                  ? (side == SharedHarness.Side.V2 ? List.of(201, 201) : List.of(201, 400))
                  : scenario.equals("concurrent-delete") ? List.of(200, 200) : List.of(200, 201));
    }
    environment()
        .knownDifferences
        .add(
            Map.of(
                "case",
                "images." + scenario,
                "kind",
                "improved",
                "v2",
                results.get(0),
                "v3",
                results.get(1)));
  }
}
