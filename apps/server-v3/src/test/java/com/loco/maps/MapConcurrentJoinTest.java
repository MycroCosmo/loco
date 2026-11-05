package com.loco.maps;

import static org.assertj.core.api.Assertions.assertThat;

import com.loco.compat.CompatibilityTestBase;
import com.loco.compat.DatabaseRequestRace;
import com.loco.compat.SharedHarness;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
class MapConcurrentJoinTest extends CompatibilityTestBase {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void observeV2RaceAndRequireBothV3RequestsToSucceed(boolean invite) throws Exception {
    environment().reset("maps/seed.sql");
    String id = MapObservationTest.id(invite ? 5 : 3),
        token =
            environment()
                .token("valid", SharedHarness.Environment.USER_ID, SharedHarness.Environment.EMAIL);
    var observed = new java.util.LinkedHashMap<String, List<Integer>>();
    for (var side : SharedHarness.Side.values()) {
      String base = side == SharedHarness.Side.V2 ? v2Url() : v3Url();
      var results =
          DatabaseRequestRace.run(
              environment(),
              side,
              "maps",
              id,
              "map_members",
              () ->
                  request(
                      base,
                      invite ? "/api/maps/join-by-code" : "/api/maps/" + id + "/join",
                      HttpMethod.POST,
                      token,
                      invite ? Map.of("inviteCode", "INVITE05") : null));
      var statuses =
          results.stream().map(response -> response.getStatusCode().value()).sorted().toList();
      observed.put(side.name(), statuses);
      if (side == SharedHarness.Side.V3) assertThat(statuses).containsExactly(201, 201);
      else {
        assertThat(statuses).contains(201);
        assertThat(statuses).allMatch(status -> status == 201 || status == 500);
      }
      try (var db = environment().connect(side);
          var query =
              db.prepareStatement(
                  "SELECT count(*) FROM map_members WHERE \"mapId\"=? AND \"userId\"=?")) {
        query.setString(1, id);
        query.setString(2, SharedHarness.Environment.USER_ID);
        try (var row = query.executeQuery()) {
          row.next();
          assertThat(row.getInt(1)).isEqualTo(1);
        }
      }
    }
    if (observed.get("V2").contains(500))
      environment()
          .knownDifferences
          .add(
              Map.of(
                  "case",
                  invite ? "maps.concurrent-code-join" : "maps.concurrent-public-join",
                  "kind",
                  "improved",
                  "v2",
                  observed.get("V2"),
                  "v3",
                  observed.get("V3"),
                  "reason",
                  "UNIQUE/ON CONFLICT keeps both requests successful"));
    var output = Path.of("build/reports/maps/race-" + (invite ? "code" : "public") + ".json");
    Files.createDirectories(output.getParent());
    mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), observed);
  }
}
