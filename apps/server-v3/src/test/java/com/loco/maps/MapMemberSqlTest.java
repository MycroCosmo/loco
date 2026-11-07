package com.loco.maps;

import static org.assertj.core.api.Assertions.assertThat;

import com.loco.compat.CompatibilityTestBase;
import com.loco.compat.SharedHarness;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;

@Import(SqlQueryCounter.Configuration.class)
class MapMemberSqlTest extends CompatibilityTestBase {
  @Override
  protected boolean usesV2Http() {
    return false;
  }

  @Test
  void memberCountDoesNotIncreaseSqlAfterFetchJoin() throws Exception {
    String phase = System.getProperty("maps.query.phase", "fetch");
    var results = new java.util.ArrayList<Map<String, Object>>();
    for (int size : List.of(3, 10)) {
      environment().reset("maps/seed.sql");
      if (size == 10)
        try (var db = environment().connect(SharedHarness.Side.V3)) {
          db.createStatement()
              .execute(
                  "INSERT INTO users(id,email,nickname,\"createdAt\",\"updatedAt\") SELECT '55555555-5555-4555-8555-'||lpad(n::text,12,'0'),'extra'||n||'@example.test','extra'||n,'2026-01-01','2026-01-01' FROM generate_series(1,7)n");
          db.createStatement()
              .execute(
                  "INSERT INTO map_members(id,\"mapId\",\"userId\",\"createdAt\") SELECT '66666666-6666-4666-8666-'||lpad(n::text,12,'0'),'"
                      + MapObservationTest.id(6)
                      + "','55555555-5555-4555-8555-'||lpad(n::text,12,'0'),timestamp '2026-01-04'+n*interval '1 day' FROM generate_series(1,7)n");
        }
      String token =
          environment()
              .token("valid", "22222222-2222-4222-8222-222222222222", "owner@example.test");
      SqlQueryCounter.reset();
      var response =
          request(
              v3Url(),
              "/api/maps/" + MapObservationTest.id(6) + "/members",
              HttpMethod.GET,
              token,
              null);
      assertThat(response.getStatusCode().value()).isEqualTo(200);
      assertThat(response.getBody().size()).isEqualTo(size);
      assertThat(response.getBody().get(0).path("isOwner").asBoolean()).isTrue();
      assertThat(response.getBody().get(2).path("nickname").asText()).isEqualTo("deleted");
      int count = SqlQueryCounter.statements().size();
      assertThat(count)
          .as("member count=%s, phase=%s", size, phase)
          .isEqualTo(phase.equals("naive") ? size + 3 : 3);
      results.add(Map.of("members", size, "sqlCount", count, "sql", SqlQueryCounter.statements()));
    }
    var output = Path.of("build/reports/maps/n-plus-one-" + phase + ".json");
    Files.createDirectories(output.getParent());
    mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), results);
  }
}
