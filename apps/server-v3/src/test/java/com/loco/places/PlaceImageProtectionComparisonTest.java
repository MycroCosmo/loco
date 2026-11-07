package com.loco.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.loco.compat.*;
import com.loco.maps.SqlQueryCounter;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;

@Tag("v2-comparison")
@Import(SqlQueryCounter.Configuration.class)
class PlaceImageProtectionComparisonTest extends CompatibilityTestBase {
  static Stream<Arguments> routes() {
    return Stream.of(
        Arguments.of("POST", "/api/places/" + PlaceObservationTest.place(1) + "/images"),
        Arguments.of("DELETE", "/api/places/" + PlaceObservationTest.place(1) + "/images"));
  }

  @ParameterizedTest
  @MethodSource("routes")
  void authRunsBeforeInputAndBusinessDatabase(String method, String path) throws Exception {
    environment().reset("places/seed.sql");
    String[] tokens = {
      null,
      environment()
          .token("expired", SharedHarness.Environment.USER_ID, SharedHarness.Environment.EMAIL),
      environment()
          .token(
              "wrong-signature",
              SharedHarness.Environment.USER_ID,
              SharedHarness.Environment.EMAIL),
      "not-a-jwt",
      environment().token("valid", "44444444-4444-4444-8444-444444444444", "deleted@example.test"),
      environment().token("valid", "99999999-9999-4999-8999-999999999999", "missing@example.test")
    };
    for (var side : SharedHarness.Side.values())
      try (var db = environment().connect(side)) {
        var before = DatabaseSnapshot.capture(db, PlaceWriteSupport.rules());
        for (int i = 0; i < tokens.length; i++) {
          SqlQueryCounter.reset();
          var response =
              request(
                  side == SharedHarness.Side.V2 ? v2Url() : v3Url(),
                  path,
                  HttpMethod.valueOf(method),
                  tokens[i],
                  Map.of("unknown", "invalid"));
          assertThat(response.getStatusCode().value()).isEqualTo(401);
          assertThat(response.getBody())
              .isEqualTo(mapper.readTree("{\"message\":\"Unauthorized\",\"statusCode\":401}"));
          if (side == SharedHarness.Side.V3)
            assertThat(SqlQueryCounter.statements()).hasSize(i >= 4 ? 1 : 0);
        }
        before.assertUnchanged(DatabaseSnapshot.capture(db, PlaceWriteSupport.rules()));
      }
  }
}
