package com.loco.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.loco.compat.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
class MarkerOrderDifferenceComparisonTest extends MarkerTestSupport {
  @Test
  void recordsDeterministicSelectionAsAnApprovedImprovement() throws Exception {
    seed("basic");
    var results = new ArrayList<List<JsonNode>>();
    for (var side : SharedHarness.Side.values()) {
      try (var db = environment().connect(side);
          var q = db.createStatement()) {
        q.execute("DELETE FROM place_reviews; DELETE FROM places; DELETE FROM place_marker_cells");
        q.execute(
            "INSERT INTO places(id,\"mapId\",\"createdById\",name,address,latitude,longitude,\"updatedAt\") SELECT md5(n::text||':'||j)::uuid::text,'"
                + PlaceObservationTest.map(1)
                + "','11111111-1111-4111-8111-111111111111','p','a',(n-1)/100.0+j/1000000.0,0,CURRENT_TIMESTAMP FROM generate_series(1,701)n CROSS JOIN generate_series(1,2)j");
        org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
            db, new org.springframework.core.io.ClassPathResource("markers/cells.sql"));
      }
      var observed = new ArrayList<JsonNode>();
      for (int run = 0; run < 2; run++) {
        var response =
            request(
                side == SharedHarness.Side.V2 ? v2Url() : v3Url(),
                path("markers") + "?" + BOUNDS + "&level=6",
                HttpMethod.GET,
                PlaceWriteSupport.token(environment()),
                null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var body = response.getBody();
        assertThat(body.size()).isEqualTo(700);
        assertThat(body.get(699).path("id").asText()).isEqualTo("overflow");
        int total = 0;
        for (var row : body) total += row.path("count").asInt();
        assertThat(total).isEqualTo(1402);
        observed.add(body);
        try (var db = environment().connect(side);
            var q = db.createStatement()) {
          q.execute(
              "CREATE TEMP TABLE reordered AS SELECT * FROM place_marker_cells; DELETE FROM place_marker_cells; INSERT INTO place_marker_cells SELECT * FROM reordered ORDER BY \"cellLatKey\" DESC,precision DESC; ANALYZE place_marker_cells");
        }
      }
      results.add(observed);
    }
    assertThat(results.get(0).get(0)).isNotEqualTo(results.get(0).get(1));
    assertThat(results.get(1).get(0)).isEqualTo(results.get(1).get(1));
    assertThat(results.get(1).get(0).get(0).path("clusterKey").asText()).isEqualTo("0.000:0.000");
    environment()
        .knownDifferences
        .add(
            Map.of(
                "case",
                "markers.deterministic-overflow",
                "kind",
                "improved",
                "decision",
                "E-12 A: count DESC, cellLatKey C ASC, cellLngKey C ASC; physical reorder preserves visible 699 and overflow",
                "v2",
                results.get(0),
                "v3",
                results.get(1)));
  }
}
