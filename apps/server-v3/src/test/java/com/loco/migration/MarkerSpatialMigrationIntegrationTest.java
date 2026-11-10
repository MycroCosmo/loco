package com.loco.migration;

import static org.assertj.core.api.Assertions.*;

import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class MarkerSpatialMigrationIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> database =
      new PostgreSQLContainer<>(
              DockerImageName.parse("postgis/postgis:17-3.6").asCompatibleSubstituteFor("postgres"))
          .withPassword(UUID.randomUUID().toString())
          .withTmpFs(Map.of("/docker-entrypoint-initdb.d", "rw"));

  Flyway migration(String version) {
    return Flyway.configure()
        .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
        .locations("classpath:db/migration", "classpath:db/cms")
        .target(version)
        .load();
  }

  String scalar(Connection db, String sql) throws Exception {
    try (var s = db.createStatement();
        var r = s.executeQuery(sql)) {
      r.next();
      return r.getString(1);
    }
  }

  @Test
  void v10AddsOnlyTheCellIndexWithoutChangingRowsAndTracksLaterBounds() throws Exception {
    migration("9").migrate();
    try (var db =
            DriverManager.getConnection(
                database.getJdbcUrl(), database.getUsername(), database.getPassword());
        var s = db.createStatement()) {
      ScriptUtils.executeSqlScript(db, new ClassPathResource("places/seed.sql"));
      ScriptUtils.executeSqlScript(db, new ClassPathResource("markers/cells.sql"));
      String places = scalar(db, "SELECT json_agg(p ORDER BY id)::text FROM places p");
      String cells =
          scalar(
              db,
              "SELECT json_agg(c ORDER BY \"mapId\",precision,\"cellLatKey\",\"cellLngKey\")::text FROM place_marker_cells c");
      assertThat(scalar(db, "SELECT count(*) FROM pg_extension WHERE extname='postgis'"))
          .isEqualTo("0");
      assertThat(migration("10").migrate().migrationsExecuted).isEqualTo(1);
      assertThat(migration("10").migrate().migrationsExecuted).isZero();
      assertThat(com.loco.compat.DatabaseSnapshot.tables(db))
          .contains("places", "place_marker_cells")
          .doesNotContain("spatial_ref_sys", "flyway_schema_history");
      assertThat(scalar(db, "SELECT json_agg(p ORDER BY id)::text FROM places p"))
          .isEqualTo(places);
      assertThat(
              scalar(
                  db,
                  "SELECT json_agg(c ORDER BY \"mapId\",precision,\"cellLatKey\",\"cellLngKey\")::text FROM place_marker_cells c"))
          .isEqualTo(cells);
      assertThat(
              scalar(
                  db,
                  "SELECT count(*) FROM pg_indexes WHERE schemaname='public' AND indexdef LIKE '%USING gist%'"))
          .isEqualTo("1");
      assertThat(
              scalar(
                  db,
                  "SELECT indexdef FROM pg_indexes WHERE indexname='place_marker_cells_bbox_gist_idx'"))
          .contains("st_makeenvelope");
      String map = scalar(db, "SELECT id FROM maps ORDER BY id LIMIT 1");
      s.execute("DELETE FROM place_marker_cells");
      s.execute(
          "INSERT INTO place_marker_cells(\"mapId\",precision,\"cellLatKey\",\"cellLngKey\",latitude,longitude,\"minLat\",\"maxLat\",\"minLng\",\"maxLng\",count,\"exactKeyCount\",\"updatedAt\") SELECT '"
              + map
              + "',3,n::text,'0',n/1000.0,0,n/1000.0,n/1000.0,0,0,1,1,now() FROM generate_series(1,10000)n");
      s.execute("ANALYZE place_marker_cells");
      String filter =
          "ST_MakeEnvelope(\"minLng\",\"minLat\",\"maxLng\",\"maxLat\",4326) && ST_MakeEnvelope(-0.001,1.234,0.001,1.234,4326)";
      for (String mode : List.of("force_custom_plan", "force_generic_plan")) {
        s.execute("SET plan_cache_mode=" + mode);
        s.execute(
            "PREPARE spatial(float8) AS SELECT \"cellLatKey\" FROM place_marker_cells WHERE ST_MakeEnvelope(\"minLng\",\"minLat\",\"maxLng\",\"maxLat\",4326) && ST_MakeEnvelope(-0.001,$1,0.001,$1,4326) AND \"minLat\"<=$1 AND \"maxLat\">=$1");
        assertThat(scalar(db, "EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) EXECUTE spatial(1.234)"))
            .contains("place_marker_cells_bbox_gist_idx");
        assertThat(scalar(db, "EXECUTE spatial(1.234)")).isEqualTo("1234");
        s.execute("DEALLOCATE spatial");
      }
      s.execute(
          "UPDATE place_marker_cells SET \"minLat\"=20,\"maxLat\"=20 WHERE \"cellLatKey\"='1234'");
      assertThat(scalar(db, "SELECT count(*) FROM place_marker_cells WHERE " + filter))
          .isEqualTo("0");
      s.execute("DELETE FROM place_marker_cells WHERE \"cellLatKey\"='1234'");
      assertThat(scalar(db, "SELECT count(*) FROM place_marker_cells WHERE \"cellLatKey\"='1234'"))
          .isEqualTo("0");
    }
  }
}
