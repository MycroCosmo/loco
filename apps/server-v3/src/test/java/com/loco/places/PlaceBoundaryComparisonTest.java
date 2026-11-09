package com.loco.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.loco.compat.*;
import com.loco.maps.SqlQueryCounter;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
@Import(SqlQueryCounter.Configuration.class)
class PlaceBoundaryComparisonTest extends CompatibilityTestBase {
  @Test
  void javascriptWhitespaceAndArrayQueriesMatch() throws Exception {
    environment().reset("places/seed.sql");
    for (String suffix :
        List.of(
            "?tag=%C2%A0foo%C2%A0",
            "?search=%EF%BB%BFroad%EF%BB%BF",
            "?limit=%C2%A02",
            "?limit%5B%5D=2&limit%5B%5D=3",
            "?sort%5B%5D=latest",
            "?search%5B%5D=foo&search%5B%5D=bar")) {
      String path = PlaceWriteSupport.createPath(1) + suffix;
      var left =
          request(v2Url(), path, HttpMethod.GET, PlaceWriteSupport.token(environment()), null);
      var right =
          request(v3Url(), path, HttpMethod.GET, PlaceWriteSupport.token(environment()), null);
      assertThat(right.getStatusCode()).as(suffix).isEqualTo(left.getStatusCode());
      JsonComparison.compare(left.getBody(), right.getBody(), Map.of());
    }
  }

  @Test
  void repeatingAverageAndLargerPagesMatchWithoutExtraQueries() throws Exception {
    environment().reset("places/seed.sql");
    for (var side : SharedHarness.Side.values())
      try (var db = environment().connect(side)) {
        db.createStatement()
            .execute(
                "INSERT INTO place_reviews(id,\"placeId\",\"userId\",rating,content,\"updatedAt\") VALUES('eeeeeeee-eeee-4eee-8eee-000000000099','"
                    + PlaceObservationTest.place(2)
                    + "','33333333-3333-4333-8333-333333333333',3,'third review','2026-01-01')");
        db.createStatement()
            .execute(
                "INSERT INTO places(id,\"mapId\",\"createdById\",name,address,latitude,longitude,tags,\"imageUrls\",\"createdAt\",\"updatedAt\") SELECT 'cccccccc-cccc-4ccc-8ccc-'||lpad(n::text,12,'0'),'"
                    + PlaceObservationTest.map(1)
                    + "','"
                    + SharedHarness.Environment.USER_ID
                    + "','page-'||n,'address',37,127,ARRAY[]::text[],ARRAY[]::text[],'2026-01-01','2026-01-01' FROM generate_series(10,60)n");
      }
    for (String path :
        List.of(
            "/api/places/" + PlaceObservationTest.place(2),
            PlaceWriteSupport.createPath(1) + "?limit=50",
            PlaceWriteSupport.createPath(1) + "?sort=reviews&limit=2",
            PlaceWriteSupport.createPath(1)
                + "?cursor="
                + PlaceObservationTest.place(45)
                + "&limit=20")) {
      var left =
          request(v2Url(), path, HttpMethod.GET, PlaceWriteSupport.token(environment()), null);
      SqlQueryCounter.reset();
      var right =
          request(v3Url(), path, HttpMethod.GET, PlaceWriteSupport.token(environment()), null);
      assertThat(right.getStatusCode()).isEqualTo(left.getStatusCode());
      JsonComparison.compare(left.getBody(), right.getBody(), Map.of());
      assertThat(SqlQueryCounter.statements()).hasSize(path.startsWith("/api/places/") ? 2 : 3);
    }
  }

  @Test
  void ownerWithoutMemberRowAndPreciseCoordinatesMatch() throws Exception {
    environment().reset("places/seed.sql");
    var rules = PlaceWriteSupport.rules();
    DatabaseSnapshot before;
    try (var db = environment().connect(SharedHarness.Side.V2)) {
      before = DatabaseSnapshot.capture(db, rules);
    }
    var responses = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
    for (var side : SharedHarness.Side.values()) {
      SqlQueryCounter.reset();
      var response =
          request(
              side == SharedHarness.Side.V2 ? v2Url() : v3Url(),
              PlaceWriteSupport.createPath(3),
              HttpMethod.POST,
              environment()
                  .token("valid", "22222222-2222-4222-8222-222222222222", "owner@example.test"),
              Map.of(
                  "name",
                  "precision",
                  "address",
                  "address",
                  "latitude",
                  37.12345678901234,
                  "longitude",
                  126.99643769342003));
      assertThat(response.getStatusCode().value()).isEqualTo(201);
      responses.add(PlaceWriteSupport.normalize(response.getBody()));
      if (side == SharedHarness.Side.V3) assertThat(SqlQueryCounter.statements()).hasSize(10);
    }
    assertThat(responses.get(1)).isEqualTo(responses.get(0));
    try (var left = environment().connect(SharedHarness.Side.V2);
        var right = environment().connect(SharedHarness.Side.V3)) {
      DatabaseSnapshot.compare(
          DatabaseSnapshot.capture(left, rules),
          DatabaseSnapshot.capture(right, rules),
          before.seedIds(),
          rules);
    }
  }
}
