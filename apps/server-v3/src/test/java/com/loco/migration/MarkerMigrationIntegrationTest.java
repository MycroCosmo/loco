package com.loco.migration;

import static org.assertj.core.api.Assertions.*;

import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class MarkerMigrationIntegrationTest {
  @Container
  final PostgreSQLContainer<?> database =
      new PostgreSQLContainer<>(
              DockerImageName.parse("postgis/postgis:17-3.6").asCompatibleSubstituteFor("postgres"))
          .withPassword(UUID.randomUUID().toString())
          .withTmpFs(Map.of("/docker-entrypoint-initdb.d", "rw"));

  Flyway migration(String target) {
    return Flyway.configure()
        .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
        .locations("classpath:db/migration", "classpath:db/cms")
        .target(target)
        .load();
  }

  Connection connection() throws Exception {
    return DriverManager.getConnection(
        database.getJdbcUrl(), database.getUsername(), database.getPassword());
  }

  String scalar(String sql) throws Exception {
    try (var db = connection();
        var q = db.createStatement();
        var r = q.executeQuery(sql)) {
      assertThat(r.next()).isTrue();
      return r.getString(1);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"7", "9"})
  void rebuildsExistingMissingStaleAndNegativeZeroCellsWithoutChangingPlaces(String target)
      throws Exception {
    migration(Integer.toString(Integer.parseInt(target) - 1)).migrate();
    try (var db = connection();
        var q = db.createStatement()) {
      ScriptUtils.executeSqlScript(db, new ClassPathResource("places/seed.sql"));
      q.execute(
          "UPDATE places SET latitude=CASE WHEN name='Alpha' THEN 37.05 ELSE -0.00001 END,longitude=0");
      ScriptUtils.executeSqlScript(db, new ClassPathResource("markers/cells.sql"));
      q.execute(
          "DELETE FROM place_marker_cells WHERE \"cellLatKey\"='37.1'; UPDATE place_marker_cells SET count=999,\"cellLatKey\"='-0.0' WHERE precision=1 AND \"cellLatKey\"='0.0'");
    }
    String places = scalar("SELECT json_agg(p ORDER BY id)::text FROM places p");
    String baseline = scalar("SELECT checksum FROM flyway_schema_history WHERE version='1'");
    assertThat(migration(target).migrate().migrationsExecuted).isEqualTo(1);
    assertThat(scalar("SELECT json_agg(p ORDER BY id)::text FROM places p")).isEqualTo(places);
    assertThat(scalar("SELECT checksum FROM flyway_schema_history WHERE version='1'"))
        .isEqualTo(baseline);
    assertThat(scalar("SELECT count(*) FROM place_marker_cells WHERE \"cellLatKey\" LIKE '-0.%'"))
        .isEqualTo("0");
    assertThat(
            scalar(
                "SELECT count(*) FROM (SELECT precision,sum(count) n FROM place_marker_cells GROUP BY precision HAVING sum(count)=(SELECT count(*) FROM places))s"))
        .isEqualTo("3");
    assertThat(
            scalar(
                "WITH expected AS (SELECT \"mapId\",p,ROUND(latitude::numeric,p)::text lat,ROUND(longitude::numeric,p)::text lng,count(*) n FROM places CROSS JOIN generate_series(1,3)p GROUP BY 1,2,3,4) SELECT count(*) FROM expected e FULL JOIN place_marker_cells c ON c.\"mapId\"=e.\"mapId\" AND c.precision=e.p AND c.\"cellLatKey\"=e.lat AND c.\"cellLngKey\"=e.lng WHERE c.count IS DISTINCT FROM e.n"))
        .isEqualTo("0");
    assertThat(migration(target).migrate().migrationsExecuted).isZero();
    migration(target).validate();
  }

  @ParameterizedTest
  @ValueSource(strings = {"7", "9"})
  void failedRebuildRollsBackDeletionAndDoesNotRecordVersion(String target) throws Exception {
    migration(Integer.toString(Integer.parseInt(target) - 1)).migrate();
    try (var db = connection();
        var q = db.createStatement()) {
      ScriptUtils.executeSqlScript(db, new ClassPathResource("places/seed.sql"));
      ScriptUtils.executeSqlScript(db, new ClassPathResource("markers/cells.sql"));
      q.execute(
          "CREATE FUNCTION reject_rebuild() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test rebuild failure'; END $$; CREATE TRIGGER reject_rebuild BEFORE INSERT ON place_marker_cells FOR EACH ROW EXECUTE FUNCTION reject_rebuild()");
    }
    String cells =
        scalar(
            "SELECT json_agg(c ORDER BY \"mapId\",precision,\"cellLatKey\",\"cellLngKey\")::text FROM place_marker_cells c");
    assertThatThrownBy(() -> migration(target).migrate())
        .hasMessageContaining("V" + target + "__rebuild_marker_");
    assertThat(
            scalar(
                "SELECT json_agg(c ORDER BY \"mapId\",precision,\"cellLatKey\",\"cellLngKey\")::text FROM place_marker_cells c"))
        .isEqualTo(cells);
    assertThat(scalar("SELECT count(*) FROM flyway_schema_history WHERE version='" + target + "'"))
        .isEqualTo("0");
    try (var db = connection();
        var q = db.createStatement()) {
      q.execute("DROP TRIGGER reject_rebuild ON place_marker_cells");
    }
    assertThat(migration(target).migrate().migrationsExecuted).isEqualTo(1);
  }
}
