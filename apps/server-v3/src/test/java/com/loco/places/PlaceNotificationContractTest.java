package com.loco.places;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.loco.compat.*;
import com.loco.notifications.NotificationSender;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class PlaceNotificationContractTest extends CompatibilityTestBase {
  @MockitoBean NotificationSender sender;

  @org.springframework.beans.factory.annotation.Autowired
  com.loco.notifications.NotificationDispatch dispatch;

  @Override
  protected boolean usesV2Http() {
    return false;
  }

  @Test
  void sendsAfterEveryCommitWithoutWaitingForDelivery() throws Exception {
    environment().reset("places/seed.sql");
    var release = new CountDownLatch(1);
    var observed = new CompletableFuture<Integer>();
    doAnswer(
            call -> {
              try (var db = environment().connect(SharedHarness.Side.V3);
                  var rows =
                      db.createStatement()
                          .executeQuery("SELECT count(*) FROM place_marker_cells")) {
                rows.next();
                observed.complete(rows.getInt(1));
              } catch (Throwable error) {
                observed.completeExceptionally(error);
              }
              if (!release.await(10, TimeUnit.SECONDS))
                throw new AssertionError("HTTP waited for sender");
              return null;
            })
        .when(sender)
        .sendToMap(anyString(), anyString(), any());
    try {
      var response =
          request(
              v3Url(),
              PlaceWriteSupport.createPath(1),
              HttpMethod.POST,
              PlaceWriteSupport.token(environment()),
              mapper.readTree(PlaceCreateObservationTest.MINIMAL));
      assertThat(response.getStatusCode().value()).isEqualTo(201);
      assertThat(observed.get(5, TimeUnit.SECONDS)).isEqualTo(3);
      verify(sender)
          .sendToMap(
              PlaceObservationTest.map(1),
              SharedHarness.Environment.USER_ID,
              new NotificationSender.Message(
                  "새 장소 추가",
                  "compat님이 \"room-1\"에 \"new-place\"을 추가했습니다.",
                  Map.of(
                      "type",
                      "place_added",
                      "mapId",
                      PlaceObservationTest.map(1),
                      "placeId",
                      response.getBody().path("id").asText())));
    } finally {
      release.countDown();
      dispatch.drain().get(5, TimeUnit.SECONDS);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void noNotificationWhenPlaceOrAnyCellFails(int precision) throws Exception {
    environment().reset("places/seed.sql");
    try (var db = environment().connect(SharedHarness.Side.V3)) {
      var sql = db.createStatement();
      String table = precision == 0 ? "places" : "place_marker_cells";
      sql.execute(
          "CREATE FUNCTION notification_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
              + (precision == 0 ? "" : "IF NEW.precision=" + precision + " THEN ")
              + "RAISE EXCEPTION 'injected'; "
              + (precision == 0 ? "" : "END IF; RETURN NEW;")
              + " END; $$");
      sql.execute(
          "CREATE TRIGGER notification_failure BEFORE INSERT ON "
              + table
              + " FOR EACH ROW EXECUTE FUNCTION notification_failure()");
      try {
        assertThat(
                request(
                        v3Url(),
                        PlaceWriteSupport.createPath(1),
                        HttpMethod.POST,
                        PlaceWriteSupport.token(environment()),
                        mapper.readTree(PlaceCreateObservationTest.MINIMAL))
                    .getStatusCode()
                    .value())
            .isEqualTo(500);
        verifyNoInteractions(sender);
      } finally {
        sql.execute("DROP TRIGGER notification_failure ON " + table);
        sql.execute("DROP FUNCTION notification_failure()");
      }
    }
  }
}
