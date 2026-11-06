package com.loco.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.loco.compat.*;
import com.loco.maps.SqlQueryCounter;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
@Import(SqlQueryCounter.Configuration.class)
class ReviewMemberComparisonTest extends CompatibilityTestBase {
  @Test
  void publicMemberCanWriteAndOwnerCannotDeleteTheirReview() throws Exception {
    ReviewTestSupport.seed(environment());
    com.fasterxml.jackson.databind.JsonNode first = null;
    for (var side : SharedHarness.Side.values()) {
      try (var db = environment().connect(side);
          var sql = db.createStatement()) {
        sql.execute(
            "INSERT INTO map_members(id,\"mapId\",\"userId\",\"createdAt\") VALUES('bbbbbbbb-bbbb-4bbb-8bbb-000000000003','"
                + PlaceObservationTest.map(1)
                + "','"
                + ReviewTestSupport.user(3)
                + "','2026-01-01')");
      }
      String base = side == SharedHarness.Side.V2 ? v2Url() : v3Url(),
          token = environment().token("valid", ReviewTestSupport.user(3), "member@example.test");
      SqlQueryCounter.reset();
      var response =
          request(
              base,
              ReviewTestSupport.path(1),
              HttpMethod.POST,
              token,
              Map.of("rating", 4.0, "content", "  member  "));
      assertThat(response.getStatusCode().value()).isEqualTo(201);
      if (side == SharedHarness.Side.V3) assertThat(SqlQueryCounter.statements()).hasSize(7);
      var body = ReviewTestSupport.normalize(response.getBody());
      assertThat(body.path("reviews").get(0).path("content").asText()).isEqualTo("member");
      assertThat(body.path("reviews").get(0).path("userId").asText())
          .isEqualTo(ReviewTestSupport.user(3));
      if (first == null) first = body;
      else assertThat(body).isEqualTo(first);
      var deletion =
          request(
              base,
              ReviewTestSupport.path(1) + "/me",
              HttpMethod.DELETE,
              PlaceWriteSupport.token(environment()),
              null);
      assertThat(deletion.getStatusCode().value()).isEqualTo(200);
      assertThat(ReviewTestSupport.normalize(deletion.getBody())).isEqualTo(body);
      var mine = request(base, "/api/places/reviews/me", HttpMethod.GET, token, null);
      assertThat(mine.getStatusCode().value()).isEqualTo(200);
      assertThat(mine.getBody()).hasSize(1);
      assertThat(mine.getBody().get(0).path("placeId").asText())
          .isEqualTo(PlaceObservationTest.place(1));
    }
  }
}
