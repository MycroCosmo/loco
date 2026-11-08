package com.loco.cmsadmin;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.*;
import com.loco.maps.SqlQueryCounter;
import jakarta.persistence.EntityManager;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;

/** Isolated synthetic data only; captures the production SQL, never substitutes a faster query. */
final class LocoReadPlanProbe {
  @FunctionalInterface
  interface Request {
    java.net.http.HttpResponse<String> get(String path) throws Exception;
  }

  static void capture(
      JdbcTemplate sql,
      EntityManager em,
      LocoReadRepository reads,
      ObjectMapper json,
      Request request)
      throws Exception {
    var output = new LinkedHashMap<String, Object>();
    output.put("postgres", sql.queryForObject("SELECT version()", String.class));
    output.put("sharedBuffers", sql.queryForObject("SHOW shared_buffers", String.class));
    output.put("workMem", sql.queryForObject("SHOW work_mem", String.class));
    output.put(
        "environment",
        "Windows Java21, Docker PostgreSQL17/postgis17-3.6, no explicit container CPU/memory limits; synthetic data, ANALYZE, warmup1 + measured10, no cold-cache claim");
    var results = new ArrayList<Map<String, Object>>();
    for (int count : List.of(10000, 100000)) {
      seed(sql, count);
      for (var resource : LocoReadResource.values()) {
        String id =
            sql.queryForObject(
                "SELECT id FROM public." + resource.table + " ORDER BY id LIMIT 1", String.class);
        var cases = new LinkedHashMap<String, LinkedMultiValueMap<String, String>>();
        cases.put("first", query(Map.of()));
        cases.put("id", query(Map.of("id", id)));
        cases.put(
            "period",
            query(Map.of("from", "2026-01-01T00:00:00Z", "until", "2026-01-01T00:00:00.050Z")));
        var mid =
            sql.queryForMap(
                "SELECT id,\"createdAt\" FROM public."
                    + resource.table
                    + " ORDER BY \"createdAt\" DESC,id DESC OFFSET "
                    + count / 2
                    + " LIMIT 1");
        String cursor =
            LocoReadFilter.parse(resource, query(Map.of()))
                .next(
                    Map.of(
                        "id",
                        mid.get("id"),
                        "createdAt",
                        ((java.sql.Timestamp) mid.get("createdAt"))
                            .toLocalDateTime()
                            .toInstant(ZoneOffset.UTC)
                            .toString()));
        cases.put("deep", query(Map.of("cursor", cursor)));
        switch (resource) {
          case USERS -> {
            cases.put("state", query(Map.of("status", "DELETED")));
            cases.put("common-search", query(Map.of("search", "common")));
            cases.put("rare-search", query(Map.of("search", "needle")));
          }
          case MAPS -> {
            cases.put("state", query(Map.of("visibility", "PRIVATE")));
            cases.put("common-search", query(Map.of("search", "common")));
            cases.put("rare-search", query(Map.of("search", "needle")));
            cases.put("tag", query(Map.of("tag", "rare")));
            cases.put("foreign-key", query(Map.of("ownerId", key(sql, "user-1"))));
          }
          case PLACES -> {
            cases.put("foreign-key", query(Map.of("mapId", key(sql, "map-1"))));
            cases.put("common-search", query(Map.of("search", "common")));
            cases.put("rare-search", query(Map.of("search", "needle")));
            cases.put("tag", query(Map.of("tag", "rare")));
          }
          case REPORTS -> {
            cases.put("received", query(Map.of("status", "RECEIVED")));
            cases.put("in-review", query(Map.of("status", "IN_REVIEW")));
            cases.put("state", query(Map.of("targetType", "MAP")));
            cases.put("foreign-key", query(Map.of("reporterId", key(sql, "user-1"))));
            cases.put("target", query(Map.of("targetId", key(sql, "map-1"))));
          }
        }
        for (var entry : cases.entrySet()) {
          var plan = reads.listPlan(resource, LocoReadFilter.parse(resource, entry.getValue()));
          String path =
              "/admin/api/loco/"
                  + resource.table
                  + "?"
                  + entry.getValue().entrySet().stream()
                      .map(
                          e ->
                              e.getKey()
                                  + "="
                                  + URLEncoder.encode(
                                      e.getValue().getFirst(), StandardCharsets.UTF_8))
                      .collect(java.util.stream.Collectors.joining("&"));
          results.add(
              measure(count, resource.table, entry.getKey(), plan, em, reads, json, request, path));
        }
        var detail =
            resource == LocoReadResource.REPORTS
                ? reads.reportDetailPlan(
                    id,
                    EnumSet.of(
                        LocoReadResource.USERS, LocoReadResource.MAPS, LocoReadResource.PLACES))
                : reads.detailPlan(resource, id);
        results.add(
            measure(
                count,
                resource.table,
                "detail",
                detail,
                em,
                reads,
                json,
                request,
                "/admin/api/loco/" + resource.table + "/" + id));
      }
    }
    output.put("results", results);
    var path = Path.of("build/reports/cms-read/plans.json");
    Files.createDirectories(path.getParent());
    Files.writeString(path, json.writerWithDefaultPrettyPrinter().writeValueAsString(output));
    System.out.println("CMS read plans: " + results.size() + " scenarios -> " + path);
  }

  private static Map<String, Object> measure(
      int count,
      String resource,
      String scenario,
      LocoReadRepository.Plan plan,
      EntityManager em,
      LocoReadRepository reads,
      ObjectMapper json,
      Request request,
      String path)
      throws Exception {
    reads.read(plan); // One warmup; measured queries use the same projection and bound parameters.
    var dbTimes = new ArrayList<Double>();
    for (int i = 0; i < 10; i++) {
      long start = System.nanoTime();
      reads.read(plan);
      dbTimes.add((System.nanoTime() - start) / 1_000_000.0);
    }
    var explain = em.createNativeQuery("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + plan.sql());
    plan.parameters().forEach(explain::setParameter);
    JsonNode explained = json.readTree(explain.getSingleResult().toString());
    assertThat(request.get(path).statusCode()).as(path).isEqualTo(200);
    var httpTimes = new ArrayList<Double>();
    var counts = new TreeSet<Integer>();
    for (int i = 0; i < 10; i++) {
      SqlQueryCounter.reset();
      long start = System.nanoTime();
      var response = request.get(path);
      httpTimes.add((System.nanoTime() - start) / 1_000_000.0);
      assertThat(response.statusCode()).isEqualTo(200);
      counts.add(SqlQueryCounter.statements().size());
      assertThat(SqlQueryCounter.statements()).hasSize(3);
      assertThat(
              SqlQueryCounter.statements().stream().filter(s -> s.contains("FROM public.")).count())
          .isEqualTo(1);
    }
    Collections.sort(dbTimes);
    Collections.sort(httpTimes);
    var result = new LinkedHashMap<String, Object>();
    result.put("rowsPerBusinessTable", count);
    result.put("resource", resource);
    result.put("scenario", scenario);
    result.put("sql", plan.sql());
    result.put("parameters", plan.parameters());
    result.put("explain", explained);
    result.put("dbReadMedianMs", (dbTimes.get(4) + dbTimes.get(5)) / 2);
    result.put("dbReadMaxMs", dbTimes.getLast());
    result.put("httpMedianMs", (httpTimes.get(4) + httpTimes.get(5)) / 2);
    result.put("httpMaxMs", httpTimes.getLast());
    result.put("httpSqlCounts", counts);
    result.put("businessSqlCount", 1);
    return result;
  }

  private static LinkedMultiValueMap<String, String> query(Map<String, String> values) {
    var result = new LinkedMultiValueMap<String, String>();
    values.forEach(result::add);
    return result;
  }

  private static String key(JdbcTemplate sql, String value) {
    return sql.queryForObject("SELECT md5(?)::uuid::text", String.class, value);
  }

  private static void seed(JdbcTemplate sql, int count) {
    sql.execute("TRUNCATE cms.loco_report_processing");
    sql.execute("TRUNCATE users CASCADE");
    sql.update(
        """
      INSERT INTO users(id,email,nickname,"createdAt","updatedAt","deletedAt")
      SELECT md5('user-'||i)::uuid::text,'synthetic-'||i||'@example.test',CASE WHEN i%2000=0 THEN 'needle' ELSE 'common' END||i,
      timestamp '2026-01-01'+(i/100)*interval '1 millisecond',timestamp '2026-01-01',CASE WHEN i%10=0 THEN timestamp '2026-02-01' ELSE NULL END
      FROM generate_series(1,?) i
      """,
        count);
    sql.update(
        """
      INSERT INTO maps(id,"ownerId",title,description,tags,visibility,"createdAt","updatedAt")
      SELECT md5('map-'||i)::uuid::text,md5('user-'||(i%1000+1))::uuid::text,CASE WHEN i%2000=0 THEN 'needle' ELSE 'common' END||i,
      'synthetic',ARRAY[CASE WHEN i%1000=0 THEN 'rare' ELSE 'common' END],(CASE WHEN i%2=0 THEN 'PRIVATE' ELSE 'PUBLIC' END)::"Visibility",
      timestamp '2026-01-01'+(i/100)*interval '1 millisecond',timestamp '2026-01-01' FROM generate_series(1,?) i
      """,
        count);
    sql.update(
        """
      INSERT INTO places(id,"mapId","createdById",name,address,latitude,longitude,tags,"createdAt","updatedAt")
      SELECT md5('place-'||i)::uuid::text,md5('map-'||(i%1000+1))::uuid::text,md5('user-'||(i%1000+1))::uuid::text,
      'common'||i,CASE WHEN i%2000=0 THEN 'needle' ELSE 'address' END,37.05,-0.00001,
      ARRAY[CASE WHEN i%1000=0 THEN 'rare' ELSE 'common' END],timestamp '2026-01-01'+(i/100)*interval '1 millisecond',timestamp '2026-01-01'
      FROM generate_series(1,?) i
      """,
        count);
    sql.update(
        """
      INSERT INTO place_reviews(id,"placeId","userId",rating,content,"createdAt","updatedAt")
      SELECT md5('review-'||i)::uuid::text,md5('place-'||(i%1000+1))::uuid::text,md5('user-'||(i/1000+1))::uuid::text,
      i%5+1,'synthetic',timestamp '2026-01-01',timestamp '2026-01-01' FROM generate_series(1,2000) i
      """);
    sql.update(
        """
      INSERT INTO reports(id,"reporterId","targetType","targetId",reason,detail,"createdAt")
      SELECT md5('report-'||i)::uuid::text,md5('user-'||(i%1000+1))::uuid::text,
      (CASE i%3 WHEN 0 THEN 'MAP' WHEN 1 THEN 'PLACE' ELSE 'USER' END)::"ReportTargetType",
      md5((CASE i%3 WHEN 0 THEN 'map-' WHEN 1 THEN 'place-' ELSE 'user-' END)||(CASE WHEN i%10=0 THEN 999999 ELSE i END))::uuid::text,
      'synthetic','synthetic',timestamp '2026-01-01'+(i/100)*interval '1 millisecond' FROM generate_series(1,?) i
      """,
        count);
    sql.update(
        """
      INSERT INTO cms.loco_report_processing(report_id,status,handled_by,handled_at)
      SELECT md5('report-'||i)::uuid::text,'IN_REVIEW',md5('synthetic-admin')::uuid::text,timestamp '2026-01-01'
      FROM generate_series(2,?,2) i
      """,
        count);
    sql.execute("ANALYZE cms.loco_report_processing");
    for (String table : List.of("users", "maps", "places", "reports", "place_reviews"))
      sql.execute("ANALYZE public." + table);
  }
}
