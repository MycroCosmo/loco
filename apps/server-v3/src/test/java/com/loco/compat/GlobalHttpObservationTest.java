package com.loco.compat;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("v2-comparison")
class GlobalHttpObservationTest extends CompatibilityTestBase {
  @Test
  void observesOriginalBootstrapWithoutChangingV2Middleware() throws Exception {
    environment().reset("places/seed.sql");
    String script;
    try (var stream = getClass().getResourceAsStream("/globals/observe-v2.cjs")) {
      script = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
    com.fasterxml.jackson.databind.JsonNode observed;
    try (var db = environment().connect(SharedHarness.Side.V2);
        var sql = db.createStatement()) {
      sql.execute(
          "CREATE FUNCTION harness_global_failure() RETURNS trigger LANGUAGE plpgsql AS $$ "
              + "BEGIN RAISE EXCEPTION 'global HTTP fixture failure'; END $$");
      try {
        sql.execute(
            "CREATE TRIGGER harness_global_failure BEFORE INSERT ON feedbacks "
                + "FOR EACH ROW EXECUTE FUNCTION harness_global_failure()");
        observed = environment().nodeCheck(script, Map.of());
      } finally {
        sql.execute("DROP TRIGGER IF EXISTS harness_global_failure ON feedbacks");
        sql.execute("DROP FUNCTION harness_global_failure()");
      }
    }
    assertThat(observed.has("error")).as(observed.toPrettyString()).isFalse();
    assertThat(observed.path("cases").size()).isGreaterThan(40);
    var failures = new java.util.ArrayList<Integer>();
    observed
        .path("cases")
        .forEach(
            row -> {
              if (row.path("name").asText().endsWith("-failure"))
                failures.add(row.path("response").path("status").asInt());
            });
    assertThat(failures).containsExactly(500, 500, 500, 500, 500);
    var target = Path.of("build/reports/globals/http-v2.json");
    Files.createDirectories(target.getParent());
    mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), observed);
    if (!Boolean.getBoolean("globals.observe")) {
      try (var stream = getClass().getResourceAsStream("/globals/http-v2.json")) {
        assertThat(observed).isEqualTo(mapper.readTree(stream));
      }
    }
  }
}
