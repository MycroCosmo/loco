package com.loco.notifications;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loco.compat.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("v2-comparison")
class NotificationComparisonTest extends CompatibilityTestBase {
  @MockitoBean FcmClient fcm;
  @Autowired NotificationSender sender;
  @Autowired NotificationDispatch dispatch;
  static final String USER = SharedHarness.Environment.USER_ID;
  static final String OWNER = "22222222-2222-4222-8222-222222222222";

  static String map(int n) {
    return "aaaaaaaa-aaaa-4aaa-8aaa-%012d".formatted(n);
  }

  @BeforeEach
  void seed() throws Exception {
    environment().reset("places/seed.sql");
    when(fcm.enabled()).thenReturn(true);
  }

  @AfterEach
  void finish() throws Exception {
    dispatch.drain().get(5, TimeUnit.SECONDS);
  }

  JsonNode observe(String mode) throws Exception {
    String script;
    try (var in = getClass().getResourceAsStream("/notifications/observe-v2.cjs")) {
      script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    var observed = environment().nodeCheck(script, Map.of("mode", mode));
    assertThat(observed.has("error")).as(observed.toString()).isFalse();
    return observed.path("rows");
  }

  void sql(String query) throws Exception {
    try (var db = environment().connect(SharedHarness.Side.V3)) {
      db.createStatement().execute(query);
    }
  }

  int count(String query) throws Exception {
    try (var db = environment().connect(SharedHarness.Side.V3);
        var r = db.createStatement().executeQuery(query)) {
      r.next();
      return r.getInt(1);
    }
  }

  ObjectNode wire(String token, NotificationSender.Message message) {
    var node = mapper.createObjectNode();
    node.put("token", token);
    node.putObject("notification").put("title", message.title()).put("body", message.body());
    node.set("data", mapper.valueToTree(message.data()));
    node.putObject("apns").putObject("payload").putObject("aps").put("sound", "default");
    node.putObject("android").put("priority", "high");
    return node;
  }

  @Test
  void threeApiCallSitesMatchV2WithoutWaitingForDelivery() throws Exception {
    var expected = observe("call-sites");
    sql(
        "UPDATE users SET \"fcmToken\"=CASE WHEN id='"
            + USER
            + "' THEN 'self-token' ELSE 'owner-token' END WHERE id IN ('"
            + USER
            + "','"
            + OWNER
            + "')");
    String jwt = environment().token("valid", USER, SharedHarness.Environment.EMAIL);
    int[] maps = {4, 4, 3, 1, 1};
    for (int i = 0; i < 5; i++) {
      if (i == 4)
        sql(
            "INSERT INTO map_members(id,\"mapId\",\"userId\",\"createdAt\") VALUES ('"
                + UUID.randomUUID()
                + "','"
                + map(1)
                + "','"
                + OWNER
                + "',now())");
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var messages = new CopyOnWriteArrayList<ObjectNode>();
      doAnswer(
              call -> {
                messages.add(wire(call.getArgument(0), call.getArgument(1)));
                entered.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                return FcmClient.Outcome.SENT;
              })
          .when(fcm)
          .send(anyString(), any());
      var body =
          i == 2
              ? mapper.readTree("{\"inviteCode\":\" invite03 \"}")
              : i == 4
                  ? mapper.readTree(
                      "{\"name\":\"notification-place\",\"address\":\"Seoul\",\"latitude\":37.4444,\"longitude\":127.8888}")
                  : null;
      String path =
          i == 2
              ? "/api/maps/join-by-code"
              : "/api/maps/" + map(maps[i]) + (i == 4 ? "/places" : "/join");
      try {
        var response = request(v3Url(), path, HttpMethod.POST, jwt, body);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        var actual =
            mapper
                .createObjectNode()
                .put("name", expected.get(i).path("name").asText())
                .put("status", 201)
                .put("deliveryPendingAtResponse", true);
        if (i == 4) {
          String place = response.getBody().path("id").asText();
          assertThat(messages.getFirst().at("/data/placeId").asText()).isEqualTo(place);
          ((ObjectNode) messages.getFirst().path("data")).put("placeId", "$createdPlace");
          actual.put(
              "committedPlace", count("SELECT count(*) FROM places WHERE id='" + place + "'") == 1);
          actual.put(
              "committedCells",
              count("SELECT count(*) FROM place_marker_cells WHERE \"mapId\"='" + map(1) + "'"));
        } else
          actual.put(
              "committedMembers",
              count(
                  "SELECT count(*) FROM map_members WHERE \"mapId\"='"
                      + map(maps[i])
                      + "' AND \"userId\"='"
                      + USER
                      + "'"));
        actual.set("messages", mapper.valueToTree(messages));
        assertThat(actual).isEqualTo(expected.get(i));
      } finally {
        release.countDown();
        dispatch.drain().get(5, TimeUnit.SECONDS);
      }
    }
    clearInvocations(fcm);
    sql(
        "CREATE FUNCTION notification_probe_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected'; END; $$");
    sql(
        "CREATE TRIGGER notification_probe_fail BEFORE INSERT ON place_marker_cells FOR EACH ROW EXECUTE FUNCTION notification_probe_fail()");
    try {
      var response =
          request(
              v3Url(),
              "/api/maps/" + map(1) + "/places",
              HttpMethod.POST,
              jwt,
              mapper.readTree(
                  "{\"name\":\"partial-place\",\"address\":\"Seoul\",\"latitude\":38,\"longitude\":128}"));
      dispatch.drain().get(3, TimeUnit.SECONDS);
      verify(fcm, never()).send(anyString(), any());
      var actual =
          mapper
              .createObjectNode()
              .put("name", "marker-failure-after-place-commit")
              .put("status", response.getStatusCode().value())
              .put(
                  "committedPlaces",
                  count("SELECT count(*) FROM places WHERE name='partial-place'"))
              .put("sends", 0);
      assertThat(actual).isEqualTo(expected.get(5));
    } finally {
      sql("DROP TRIGGER notification_probe_fail ON place_marker_cells");
      sql("DROP FUNCTION notification_probe_fail()");
    }
  }

  @Test
  void tokenCleanupMatchesV2ExceptTheApprovedReplacementRace() throws Exception {
    var baseline = observe("service");
    var payload =
        new NotificationSender.Message("관측 제목", "관측 내용", Map.of("type", "probe", "mapId", map(1)));
    for (String code :
        List.of(
            "messaging/internal-error",
            "messaging/invalid-registration-token",
            "messaging/registration-token-not-registered")) {
      sql("UPDATE users SET \"fcmToken\"='old-token' WHERE id='" + USER + "'");
      when(fcm.send(anyString(), any()))
          .thenReturn(
              code.endsWith("/registration-token-not-registered")
                  ? FcmClient.Outcome.UNREGISTERED
                  : FcmClient.Outcome.FAILED);
      sender.sendToUser(USER, payload);
      var expected =
          java.util.stream.StreamSupport.stream(baseline.spliterator(), false)
              .filter(r -> r.path("name").asText().equals(code))
              .findFirst()
              .orElseThrow();
      try (var db = environment().connect(SharedHarness.Side.V3);
          var row =
              db.createStatement()
                  .executeQuery("SELECT \"fcmToken\" FROM users WHERE id='" + USER + "'")) {
        row.next();
        assertThat(mapper.<com.fasterxml.jackson.databind.JsonNode>valueToTree(row.getString(1)))
            .isEqualTo(expected.path("remainingToken"));
      }
    }
    sql("UPDATE users SET \"fcmToken\"='old-token' WHERE id='" + USER + "'");
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(fcm.send(anyString(), any()))
        .thenAnswer(
            call -> {
              entered.countDown();
              assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
              return FcmClient.Outcome.UNREGISTERED;
            });
    try {
      dispatch.sendToUser(USER, payload);
      assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
      sql("UPDATE users SET \"fcmToken\"='new-token' WHERE id='" + USER + "'");
    } finally {
      release.countDown();
    }
    dispatch.drain().get(5, TimeUnit.SECONDS);
    assertThat(
            count(
                "SELECT count(*) FROM users WHERE id='" + USER + "' AND \"fcmToken\"='new-token'"))
        .isOne();
    var v2 =
        java.util.stream.StreamSupport.stream(baseline.spliterator(), false)
            .filter(r -> r.path("name").asText().equals("replacement-token-race"))
            .findFirst()
            .orElseThrow();
    assertThat(v2.path("remainingToken").isNull()).isTrue();
    environment()
        .knownDifferences
        .add(
            Map.of(
                "case",
                "notifications.token-replacement",
                "kind",
                "improved",
                "reason",
                "9-6 B: conditional UPDATE preserves a replacement FCM token",
                "v2",
                "new token cleared",
                "v3",
                "new token preserved"));
  }
}
