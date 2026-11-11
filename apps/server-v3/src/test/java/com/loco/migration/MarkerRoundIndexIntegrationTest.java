package com.loco.migration;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
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
class MarkerRoundIndexIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> database =
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

  String scalar(Connection db, String sql) throws Exception {
    try (var q = db.createStatement();
        var r = q.executeQuery(sql)) {
      assertThat(r.next()).isTrue();
      return r.getString(1);
    }
  }

  @Test
  void indexesPreserveDataAndServeCustomAndGenericPlansAfterWrites() throws Exception {
    migration("7").migrate();
    try (var db =
            DriverManager.getConnection(
                database.getJdbcUrl(), database.getUsername(), database.getPassword());
        var q = db.createStatement()) {
      ScriptUtils.executeSqlScript(db, new ClassPathResource("places/seed.sql"));
      String before = scalar(db, "SELECT json_agg(p ORDER BY id)::text FROM places p");
      assertThat(migration("8").migrate().migrationsExecuted).isEqualTo(1);
      assertThat(scalar(db, "SELECT json_agg(p ORDER BY id)::text FROM places p"))
          .isEqualTo(before);
      assertThat(migration("8").migrate().migrationsExecuted).isZero();
      q.execute(
          "INSERT INTO places(id,\"mapId\",\"createdById\",name,address,latitude,longitude,tags,\"imageUrls\",\"createdAt\",\"updatedAt\") SELECT 'round-'||n, p.\"mapId\",p.\"createdById\",'round-'||n,'synthetic',n/1000.0-50,n/1000.0,ARRAY[]::text[],ARRAY[]::text[],now(),now() FROM generate_series(1,100000)n CROSS JOIN (SELECT * FROM places LIMIT 1)p");
      q.execute("ANALYZE places");
      String map = scalar(db, "SELECT \"mapId\" FROM places WHERE id='round-12345'");
      var plans = new ObjectMapper().createArrayNode();
      for (String mode : List.of("force_custom_plan", "force_generic_plan")) {
        q.execute("SET plan_cache_mode=" + mode);
        for (int p : new int[] {1, 2, 3, 6}) {
          String cast = p == 6 ? "" : "::text";
          String expression = "ROUND(latitude::numeric," + p + ")" + cast;
          String longitude = "ROUND(longitude::numeric," + p + ")" + cast;
          String lat = scalar(db, "SELECT " + expression + " FROM places WHERE id='round-12345'");
          String lng = scalar(db, "SELECT " + longitude + " FROM places WHERE id='round-12345'");
          String type = p == 6 ? "numeric" : "text";
          String index = p == 6 ? "places_map_round6_idx" : "places_map_round" + p + "_text_idx";
          q.execute(
              "PREPARE marker(text,"
                  + type
                  + ","
                  + type
                  + ") AS SELECT id FROM places WHERE \"mapId\"=$1 AND "
                  + expression
                  + "=$2 AND "
                  + longitude
                  + "=$3 ORDER BY \"createdAt\" DESC,id DESC LIMIT 21");
          String plan =
              scalar(
                  db,
                  "EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) EXECUTE marker('"
                      + map
                      + "','"
                      + lat
                      + "','"
                      + lng
                      + "')");
          assertThat(plan).contains(index).contains("Index Scan").doesNotContain("Seq Scan");
          plans
              .addObject()
              .put("mode", mode)
              .put("precision", p)
              .set("plan", new ObjectMapper().readTree(plan));
          q.execute("DEALLOCATE marker");
        }
      }
      for (double value :
          new double[] {
            37.05,
            -0.00001,
            Math.nextDown(37.0000005),
            37.0000005,
            Math.nextUp(37.0000005),
            -37.0000005,
            -0.0
          }) {
        q.execute("UPDATE places SET latitude=" + value + ",longitude=0 WHERE id='round-12345'");
        for (int p : new int[] {1, 2, 3, 6}) {
          String cast = p == 6 ? "" : "::text";
          String predicate =
              "ROUND(latitude::numeric,"
                  + p
                  + ")"
                  + cast
                  + "=ROUND(('"
                  + value
                  + "'::float8)::numeric,"
                  + p
                  + ")"
                  + cast
                  + " AND ROUND(longitude::numeric,"
                  + p
                  + ")"
                  + cast
                  + "=ROUND(0::numeric,"
                  + p
                  + ")"
                  + cast;
          String query =
              "SELECT coalesce(string_agg(id,',' ORDER BY id),'') FROM places WHERE \"mapId\"='"
                  + map
                  + "' AND "
                  + predicate;
          q.execute("SET enable_indexscan=on; SET enable_bitmapscan=on; SET enable_seqscan=off");
          String indexed = scalar(db, query);
          q.execute("SET enable_indexscan=off; SET enable_bitmapscan=off; SET enable_seqscan=on");
          assertThat(indexed).contains("round-12345").isEqualTo(scalar(db, query));
        }
      }
      q.execute(
          "RESET enable_indexscan; RESET enable_bitmapscan; RESET enable_seqscan; DELETE FROM places WHERE id='round-12345'");
      assertThat(scalar(db, "SELECT count(*) FROM places WHERE id='round-12345'")).isEqualTo("0");
      var file = java.nio.file.Path.of("build/reports/markers/round-index-plans.json");
      java.nio.file.Files.createDirectories(file.getParent());
      new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(file.toFile(), plans);
    }
  }
}
