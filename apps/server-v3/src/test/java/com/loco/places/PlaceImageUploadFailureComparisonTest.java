package com.loco.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.loco.compat.*;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("v2-comparison")
class PlaceImageUploadFailureComparisonTest extends PlaceImageTestSupport {
  @ParameterizedTest
  @ValueSource(strings = {"update", "commit", "storage"})
  void uploadFailurePreservesDatabaseAndOnlyRemovesNewFile(String phase) throws Exception {
    seedBoth(2);
    for (var side : sides())
      try (var db = environment().connect(side);
          var sql = db.createStatement()) {
        var before = DatabaseSnapshot.capture(db, PlaceWriteSupport.rules());
        var beforeFiles = files(side);
        if (phase.equals("storage")) {
          if (side == SharedHarness.Side.V2)
            environment()
                .nodeCheck(
                    "const fs=require('fs');fs.renameSync('/app/uploads/places','/app/uploads/places.saved');fs.writeFileSync('/app/uploads/places','blocked');console.log('{}')",
                    Map.of());
          else {
            Files.move(
                environment().uploads.resolve("places"),
                environment().uploads.resolve("places.saved"));
            Files.writeString(environment().uploads.resolve("places"), "blocked");
          }
        } else {
          sql.execute(
              "CREATE FUNCTION image_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected'; END; $$");
          sql.execute(
              phase.equals("commit")
                  ? "CREATE CONSTRAINT TRIGGER image_failure AFTER UPDATE ON places DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION image_failure()"
                  : "CREATE TRIGGER image_failure BEFORE UPDATE ON places FOR EACH ROW EXECUTE FUNCTION image_failure()");
        }
        try {
          var response = uploadCase(base(side), "png", token(1));
          assertThat(response.getStatusCode().value()).isEqualTo(500);
          assertThat(response.getBody())
              .isEqualTo(
                  mapper.readTree("{\"statusCode\":500,\"message\":\"Internal server error\"}"));
          before.assertUnchanged(DatabaseSnapshot.capture(db, PlaceWriteSupport.rules()));
        } finally {
          if (phase.equals("storage")) {
            if (side == SharedHarness.Side.V2)
              environment()
                  .nodeCheck(
                      "const fs=require('fs');fs.unlinkSync('/app/uploads/places');fs.renameSync('/app/uploads/places.saved','/app/uploads/places');console.log('{}')",
                      Map.of());
            else {
              Files.delete(environment().uploads.resolve("places"));
              Files.move(
                  environment().uploads.resolve("places.saved"),
                  environment().uploads.resolve("places"));
            }
          } else {
            sql.execute("DROP TRIGGER image_failure ON places");
            sql.execute("DROP FUNCTION image_failure()");
          }
        }
        assertThat(files(side)).isEqualTo(beforeFiles);
      }
  }
}
