package com.loco.notifications;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.loco.compat.*;
import com.loco.maps.SqlQueryCounter;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Import(SqlQueryCounter.Configuration.class)
class NotificationDeliveryTest extends CompatibilityTestBase {
  @MockitoBean FcmClient fcm;
  @Autowired NotificationSender sender;
  @Autowired NotificationDispatch dispatch;
  private final NotificationSender.Message message =
      new NotificationSender.Message("title", "body", Map.of());
  private static final String USER = SharedHarness.Environment.USER_ID;
  private static final String MAP = "aaaaaaaa-aaaa-4aaa-8aaa-000000000001";

  @Override
  protected boolean usesV2Http() {
    return false;
  }

  @BeforeEach
  void seed() throws Exception {
    environment().reset("places/seed.sql");
    when(fcm.enabled()).thenReturn(true);
    when(fcm.send(anyString(), any())).thenReturn(FcmClient.Outcome.SENT);
  }

  @AfterEach
  void drain() throws Exception {
    dispatch.drain().get(5, TimeUnit.SECONDS);
  }

  void sql(String query) throws Exception {
    try (var db = environment().connect(SharedHarness.Side.V3)) {
      db.createStatement().execute(query);
    }
  }

  String token() throws Exception {
    try (var db = environment().connect(SharedHarness.Side.V3);
        var r =
            db.createStatement()
                .executeQuery("SELECT \"fcmToken\" FROM users WHERE id='" + USER + "'")) {
      r.next();
      return r.getString(1);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 3, 10})
  void mapUsesOneSelectRegardlessOfRecipientCountAndOneEvent(int n) throws Exception {
    for (int i = 0; i < n; i++) {
      String id = "99999999-9999-4999-8999-%012d".formatted(i);
      sql(
          "INSERT INTO users(id,email,nickname,provider,\"fcmToken\",\"createdAt\",\"updatedAt\",\"deletedAt\") VALUES ('"
              + id
              + "','n"
              + i
              + "@example.test','n','LOCAL','duplicate',now(),now(),now())");
      sql(
          "INSERT INTO map_members(id,\"mapId\",\"userId\",\"createdAt\") VALUES ('"
              + UUID.randomUUID()
              + "','"
              + MAP
              + "','"
              + id
              + "',now())");
    }
    SqlQueryCounter.reset();
    dispatch.sendToMap(MAP, USER, message);
    dispatch.drain().get(5, TimeUnit.SECONDS);
    assertThat(SqlQueryCounter.statements()).hasSize(1);
    verify(fcm, times(n)).send("duplicate", message);
  }

  @ParameterizedTest
  @EnumSource(
      value = FcmClient.Outcome.class,
      names = {"SENT", "FAILED", "UNREGISTERED"})
  void onlyUnregisteredClearsMatchingToken(FcmClient.Outcome outcome) throws Exception {
    sql("UPDATE users SET \"fcmToken\"='old' WHERE id='" + USER + "'");
    when(fcm.send("old", message)).thenReturn(outcome);
    SqlQueryCounter.reset();
    sender.sendToUser(USER, message);
    assertThat(token()).isEqualTo(outcome == FcmClient.Outcome.UNREGISTERED ? null : "old");
    assertThat(SqlQueryCounter.statements())
        .hasSize(outcome == FcmClient.Outcome.UNREGISTERED ? 2 : 1);
  }

  @Test
  void oldTokenFailureDoesNotDeleteReplacementToken() throws Exception {
    sql("UPDATE users SET \"fcmToken\"='old' WHERE id='" + USER + "'");
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(fcm.send("old", message))
        .thenAnswer(
            call -> {
              entered.countDown();
              assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
              return FcmClient.Outcome.UNREGISTERED;
            });
    try {
      dispatch.sendToUser(USER, message);
      assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
      sql("UPDATE users SET \"fcmToken\"='replacement' WHERE id='" + USER + "'");
    } finally {
      release.countDown();
    }
    dispatch.drain().get(5, TimeUnit.SECONDS);
    assertThat(token()).isEqualTo("replacement");
  }

  @Test
  void disabledSkipsAllRecipientQueries() {
    when(fcm.enabled()).thenReturn(false);
    SqlQueryCounter.reset();
    sender.sendToUser(USER, message);
    sender.sendToMap(MAP, USER, message);
    assertThat(SqlQueryCounter.statements()).isEmpty();
    verify(fcm, never()).send(anyString(), any());
  }

  @ParameterizedTest
  @NullAndEmptySource
  void emptyTokenDoesNotSend(String value) throws Exception {
    sql(
        "UPDATE users SET \"fcmToken\"="
            + (value == null ? "NULL" : "''")
            + " WHERE id='"
            + USER
            + "'");
    SqlQueryCounter.reset();
    sender.sendToUser(USER, message);
    assertThat(SqlQueryCounter.statements()).hasSize(1);
    verify(fcm, never()).send(anyString(), any());
  }
}
