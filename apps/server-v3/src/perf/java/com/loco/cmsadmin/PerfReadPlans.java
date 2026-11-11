package com.loco.cmsadmin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.time.ZoneOffset;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;

/** Diagnostic startup only, outside HTTP timings. Uses the actual CMS query builder. */
@Component
@Profile("perf")
public class PerfReadPlans implements ApplicationRunner {
  private final DataSource source;
  private final EntityManager em;
  private final LocoReadRepository reads;
  private final ObjectMapper json;

  public PerfReadPlans(
      DataSource source, EntityManager em, LocoReadRepository reads, ObjectMapper json) {
    this.source = source;
    this.em = em;
    this.reads = reads;
    this.json = json;
  }

  @Override
  public void run(ApplicationArguments arguments) throws Exception {
    var pool = source.unwrap(HikariDataSource.class);
    var sql = new JdbcTemplate(source);
    var runtime = new LinkedHashMap<String, Object>();
    runtime.put("java", System.getProperty("java.runtime.version"));
    runtime.put("cpuCount", Runtime.getRuntime().availableProcessors());
    runtime.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
    runtime.put("jvmArguments", ManagementFactory.getRuntimeMXBean().getInputArguments());
    runtime.put(
        "gc",
        ManagementFactory.getGarbageCollectorMXBeans().stream().map(b -> b.getName()).toList());
    runtime.put("hikariMaximum", pool.getMaximumPoolSize());
    runtime.put("hikariMinimum", pool.getMinimumIdle());
    runtime.put("hikariConnectionTimeoutMs", pool.getConnectionTimeout());
    runtime.put("postgres", sql.queryForObject("SELECT version()", String.class));
    for (String setting :
        List.of("shared_buffers", "work_mem", "max_connections", "pg_stat_statements.track"))
      runtime.put(setting, sql.queryForObject("SHOW " + setting, String.class));
    Files.createDirectories(Path.of("/results"));
    json.writerWithDefaultPrettyPrinter()
        .writeValue(Path.of("/results/runtime.json").toFile(), runtime);
    if (!arguments.containsOption("capture-plans")) return;
    var plans = new ArrayList<Map<String, Object>>();
    for (var resource : LocoReadResource.values()) {
      var mid =
          sql.queryForMap(
              "SELECT id,\"createdAt\" FROM public."
                  + resource.table
                  + " ORDER BY \"createdAt\" DESC,id DESC OFFSET (SELECT count(*)/2 FROM public."
                  + resource.table
                  + ") LIMIT 1");
      String id = mid.get("id").toString();
      var empty = new LinkedMultiValueMap<String, String>();
      String cursor =
          LocoReadFilter.parse(resource, empty)
              .next(
                  Map.of(
                      "id",
                      id,
                      "createdAt",
                      ((java.sql.Timestamp) mid.get("createdAt"))
                          .toLocalDateTime()
                          .toInstant(ZoneOffset.UTC)
                          .toString()));
      var cases = new LinkedHashMap<String, Map<String, String>>();
      cases.put("first", Map.of());
      cases.put("deep", Map.of("cursor", cursor));
      if (resource != LocoReadResource.REPORTS) cases.put("search", Map.of("search", "needle"));
      else {
        cases.put("received", Map.of("status", "RECEIVED"));
        cases.put("in-review", Map.of("status", "IN_REVIEW"));
      }
      for (var c : cases.entrySet()) {
        var query = new LinkedMultiValueMap<String, String>();
        c.getValue().forEach(query::add);
        plans.add(
            capture(
                resource.table,
                c.getKey(),
                "/admin/api/loco/" + resource.table,
                c.getValue(),
                reads.listPlan(resource, LocoReadFilter.parse(resource, query))));
      }
      var detail =
          resource == LocoReadResource.REPORTS
              ? reads.reportDetailPlan(id, EnumSet.allOf(LocoReadResource.class))
              : reads.detailPlan(resource, id);
      plans.add(
          capture(
              resource.table,
              "detail",
              "/admin/api/loco/" + resource.table + "/" + id,
              Map.of(),
              detail));
    }
    json.writerWithDefaultPrettyPrinter()
        .writeValue(Path.of("/results/cms-plans.json").toFile(), plans);
  }

  private Map<String, Object> capture(
      String resource,
      String kind,
      String path,
      Map<String, String> query,
      LocoReadRepository.Plan plan) {
    var explain = em.createNativeQuery("EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) " + plan.sql());
    plan.parameters().forEach(explain::setParameter);
    return Map.of(
        "resource",
        resource,
        "case",
        kind,
        "path",
        path,
        "query",
        query,
        "sql",
        plan.sql(),
        "parameters",
        plan.parameters().toString(),
        "explain",
        explain.getSingleResult().toString());
  }
}
