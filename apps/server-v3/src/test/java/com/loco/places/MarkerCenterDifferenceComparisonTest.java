package com.loco.places;

import static org.assertj.core.api.Assertions.*;

import com.loco.compat.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
class MarkerCenterDifferenceComparisonTest extends CompatibilityTestBase {
  @Test
  void onlyDerivedCentersMayDifferFromThePrismaRoundTrip() throws Exception {
    environment().reset("places/seed.sql");
    var rules = PlaceWriteSupport.rules();
    Set<String> ids;
    try (var db = environment().connect(SharedHarness.Side.V2)) {
      ids = DatabaseSnapshot.capture(db, rules).seedIds();
    }
    var snapshots =
        new ArrayList<Map<String, List<com.fasterxml.jackson.databind.node.ObjectNode>>>();
    for (var side : SharedHarness.Side.values()) {
      var response =
          request(
              side == SharedHarness.Side.V2 ? v2Url() : v3Url(),
              PlaceWriteSupport.createPath(1),
              HttpMethod.POST,
              PlaceWriteSupport.token(environment()),
              mapper.readTree(PlaceCreateObservationTest.MINIMAL));
      assertThat(response.getStatusCode().value()).isEqualTo(201);
      try (var db = environment().connect(side)) {
        snapshots.add(DatabaseSnapshot.capture(db, rules).normalized(ids, rules));
      }
    }
    var before =
        snapshots.get(0).get("place_marker_cells").stream().map(n -> n.deepCopy()).toList();
    var after = snapshots.get(1).get("place_marker_cells").stream().map(n -> n.deepCopy()).toList();
    assertThat(after)
        .as("The approved numeric difference is actually reproduced")
        .isNotEqualTo(before);
    PlaceWriteSupport.compareCenters(snapshots.get(0), snapshots.get(1));
    environment()
        .knownDifferences
        .add(
            Map.of(
                "case",
                "markers.pg-derived-centers",
                "kind",
                "intentional",
                "decision",
                "E-20 A: PG aggregate centers; only latitude/longitude centers may differ by <=1e-10; keys/counts/extents remain exact",
                "v2",
                before,
                "v3",
                after));
  }

  @Test
  void numericExceptionRejectsCountKeyExtentAndOutOfBudgetCenterChanges() throws Exception {
    var cell =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            mapper.readTree(
                "{\"mapId\":\"map\",\"precision\":1,\"cellLatKey\":\"0.0\",\"cellLngKey\":\"0.0\",\"count\":1,\"latitude\":0,\"longitude\":0,\"minLat\":0}");
    for (String field : List.of("count", "cellLatKey", "minLat", "latitude")) {
      var wrong = cell.deepCopy();
      if (field.equals("cellLatKey")) wrong.put(field, "1.0");
      else wrong.put(field, 2);
      assertThatThrownBy(
              () ->
                  PlaceWriteSupport.compareCenters(
                      Map.of("place_marker_cells", new ArrayList<>(List.of(cell.deepCopy()))),
                      Map.of("place_marker_cells", new ArrayList<>(List.of(wrong)))))
          .isInstanceOf(AssertionError.class);
    }
  }
}
