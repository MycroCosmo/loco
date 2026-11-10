package com.loco.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.loco.compat.*;
import com.loco.maps.SqlQueryCounter;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;

@Import(SqlQueryCounter.Configuration.class)
class MarkerSqlIntegrationTest extends MarkerTestSupport {
  protected boolean usesV2Http() {
    return false;
  }

  @Test
  void explainsTheSqlExecutedByRealHttpRequests() throws Exception {
    seed("basic");
    var output = mapper.createObjectNode();
    var side = SharedHarness.Side.V3;
    String map = PlaceObservationTest.map(1);
    for (String scale : List.of("small", "10000-other-map")) {
      try (var db = environment().connect(side);
          var q = db.createStatement()) {
        if (!scale.equals("small")) {
          q.execute(
              "INSERT INTO places(id,\"mapId\",\"createdById\",name,address,latitude,longitude,tags,\"imageUrls\",\"createdAt\",\"updatedAt\") SELECT 'dddddddd-dddd-4ddd-8ddd-'||lpad(n::text,12,'0'),'"
                  + PlaceObservationTest.map(4)
                  + "','11111111-1111-4111-8111-111111111111','bulk-'||n,'address',n/1000.0,n/2000.0,ARRAY[]::text[],ARRAY[]::text[],'2026-01-01','2026-01-01' FROM generate_series(1,10000)n");
          q.execute("DELETE FROM place_marker_cells");
          org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
              db, new org.springframework.core.io.ClassPathResource("markers/cells.sql"));
        }
        q.execute(
            "ANALYZE places; ANALYZE place_marker_cells; ANALYZE maps; ANALYZE users; ANALYZE place_reviews");
      }
      var plans = mapper.createArrayNode();
      for (String api :
          List.of("markers-places", "markers-cells", "marker-group", "marker-cluster")) {
        String query =
            switch (api) {
              case "markers-places" -> "markers?swLat=37&neLat=38&swLng=127&neLng=129&level=5";
              case "markers-cells" -> "markers?swLat=37&neLat=38&swLng=127&neLng=129&level=6";
              case "marker-group" -> "marker-group?lat=37.4444&lng=127.8888";
              default -> "marker-cluster?precision=3&cellLatKey=37.444&cellLngKey=127.889&limit=2";
            };
        SqlQueryCounter.reset();
        var response =
            request(
                v3Url(), path(query), HttpMethod.GET, PlaceWriteSupport.token(environment()), null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var sqls = SqlQueryCounter.statements();
        assertThat(sqls).hasSize(api.equals("marker-cluster") ? 5 : 3);
        for (int i = 2; i < sqls.size(); i++) {
          String sql = sqls.get(i);
          List<Object> params;
          if (api.equals("markers-places")) params = List.of(map, 37d, 38d, 127d, 129d);
          else if (api.equals("markers-cells"))
            params = List.of(map, 3, 37d, 38d, 127d, 129d, 127d, 37d, 129d, 38d);
          else if (api.equals("marker-group")) params = List.of(map, 37.4444, 127.8888);
          else if (i == 2) params = List.of(map, 3, "37.444", "127.889");
          else if (i == 3) params = List.of(map, "37.444", "127.889");
          else {
            params = new ArrayList<>();
            for (var item : response.getBody().path("items")) params.add(item.path("id").asText());
          }
          try (var db = environment().connect(side);
              var statement =
                  db.prepareStatement("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql)) {
            for (int j = 0; j < params.size(); j++) statement.setObject(j + 1, params.get(j));
            try (var result = statement.executeQuery()) {
              assertThat(result.next()).isTrue();
              var plan = mapper.readTree(result.getString(1));
              assertThat(plan.get(0).path("Plan").isObject()).isTrue();
              var row = plans.addObject().put("api", api).put("sql", sql);
              row.set("params", mapper.valueToTree(params));
              row.set("plan", plan);
            }
          }
        }
      }
      output.set(scale, plans);
    }
    Path target = Path.of("build/reports/markers/explain-v3.json");
    Files.createDirectories(target.getParent());
    mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), output);
  }
}
