package com.loco.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.loco.compat.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
class PlaceObservationTest extends CompatibilityTestBase {
  static String map(int n) {
    return "aaaaaaaa-aaaa-4aaa-8aaa-%012d".formatted(n);
  }

  static String place(int n) {
    return "cccccccc-cccc-4ccc-8ccc-%012d".formatted(n);
  }

  static String list() {
    return "/api/maps/" + map(1) + "/places";
  }

  @Test
  void readBoundaries() throws Exception {
    environment().reset("places/seed.sql");
    String token =
        environment()
            .token("valid", SharedHarness.Environment.USER_ID, SharedHarness.Environment.EMAIL);
    var paths = new ArrayList<String>();
    for (String suffix :
        List.of(
            "",
            "?limit=1",
            "?limit=2",
            "?limit=0",
            "?limit=-1",
            "?limit=100",
            "?limit=2x",
            "?limit=NaN",
            "?limit=1.5",
            "?limit=1&limit=2",
            "?sort=bad",
            "?sort=name",
            "?sort=reviews",
            "?sort=",
            "?sort=latest&sort=name",
            "?search=SeOuL",
            "?search=road",
            "?search=foo%20bar",
            "?search=%25",
            "?search=%5C",
            "?search=%20%20",
            "?search=missing",
            "?tag=foo",
            "?tag=FOO",
            "?tag=%20foo%20",
            "?tag=foo&search=Alpha",
            "?search=a&search=b",
            "?tag=foo&tag=bar",
            "?cursor=missing",
            "?cursor=a&cursor=b",
            "?search%5Bx%5D=a")) paths.add(list() + suffix);
    for (String sort : List.of("latest", "name", "reviews"))
      for (int i = 1; i <= 7; i++)
        paths.add(list() + "?sort=" + sort + "&limit=2&cursor=" + place(i));
    paths.add(list() + "?search=foo&cursor=" + place(2));
    paths.add("/api/places/me");
    paths.add("/api/places/missing");
    for (int n = 1; n <= 7; n++) paths.add("/api/places/" + place(n));
    for (int n = 2; n <= 4; n++) paths.add("/api/maps/" + map(n) + "/places");
    paths.add("/api/maps/missing/places");
    paths.add("/api/maps/missing/places?sort=bad");
    var rows = mapper.createArrayNode();
    for (String path : paths) {
      var r = request(v2Url(), path, HttpMethod.GET, token, null);
      rows.addObject()
          .put("path", path)
          .put("status", r.getStatusCode().value())
          .set("body", r.getBody());
    }
    if (Boolean.getBoolean("places.observe")) {
      Path p = Path.of("build/reports/places/read-v2.json");
      Files.createDirectories(p.getParent());
      mapper.writerWithDefaultPrettyPrinter().writeValue(p.toFile(), rows);
    } else
      try (var in = getClass().getResourceAsStream("/places/read-v2.json")) {
        assertThat(rows).isEqualTo(mapper.readTree(in));
      }
  }
}
