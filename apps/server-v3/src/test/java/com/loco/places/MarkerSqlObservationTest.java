package com.loco.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.loco.compat.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;

@Tag("v2-comparison")
class MarkerSqlObservationTest extends MarkerTestSupport {
  @Test
  void captureActualServiceSqlAndPlans() throws Exception {
    seed("basic");
    String script =
        new String(
            getClass().getResourceAsStream("/markers/explain-v2.cjs").readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
    var output = mapper.createObjectNode();
    for (String scale : List.of("small", "10000-other-map")) {
      if (!scale.equals("small"))
        try (var db = environment().connect(SharedHarness.Side.V2);
            var q = db.createStatement()) {
          q.execute(
              "INSERT INTO places(id,\"mapId\",\"createdById\",name,address,latitude,longitude,tags,\"imageUrls\",\"createdAt\",\"updatedAt\") SELECT 'dddddddd-dddd-4ddd-8ddd-'||lpad(n::text,12,'0'),'"
                  + PlaceObservationTest.map(4)
                  + "','11111111-1111-4111-8111-111111111111','bulk-'||n,'address',n/1000.0,n/2000.0,ARRAY[]::text[],ARRAY[]::text[],'2026-01-01','2026-01-01' FROM generate_series(1,10000)n");
          q.execute("DELETE FROM place_marker_cells");
          org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
              db, new org.springframework.core.io.ClassPathResource("markers/cells.sql"));
        }
      try (var db = environment().connect(SharedHarness.Side.V2);
          var q = db.createStatement()) {
        q.execute(
            "ANALYZE places; ANALYZE place_marker_cells; ANALYZE maps; ANALYZE users; ANALYZE place_reviews");
      }
      var plans =
          environment()
              .nodeCheck(
                  script,
                  Map.of(
                      "map",
                      PlaceObservationTest.map(1),
                      "user",
                      SharedHarness.Environment.USER_ID));
      assertThat(plans.isArray()).as(plans.toPrettyString()).isTrue();
      assertThat(plans.size()).isGreaterThan(4);
      output.set(scale, plans);
    }
    Path target = Path.of("build/reports/markers/explain-v2.json");
    Files.createDirectories(target.getParent());
    mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), output);
  }
}
