package com.loco.notifications;

import java.time.*;
import org.springframework.stereotype.Component;

/** 한 이벤트 작업 안에서만 fan-out한다. 멤버 수만큼 실행기 큐에 넣지 않는다. */
@Component
public class FirebaseNotificationSender implements NotificationSender {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(FirebaseNotificationSender.class);
  private final NotificationRecipients recipients;
  private final FcmClient client;
  private final Clock clock;

  public FirebaseNotificationSender(
      NotificationRecipients recipients, FcmClient client, Clock clock) {
    this.recipients = recipients;
    this.client = client;
    this.clock = clock;
  }

  @Override
  public void sendToUser(String userId, Message message) {
    if (client.enabled()) recipients.one(userId).ifPresent(recipient -> send(recipient, message));
  }

  @Override
  public void sendToMap(String mapId, String excludeUserId, Message message) {
    if (!client.enabled()) return;
    for (var recipient : recipients.members(mapId, excludeUserId)) {
      if (Thread.currentThread().isInterrupted()) return;
      send(recipient, message);
    }
  }

  private void send(NotificationRecipients.Recipient recipient, Message message) {
    String token = recipient.getToken();
    if (token == null || token.isEmpty()) return;
    try {
      if (client.send(token, message) == FcmClient.Outcome.UNREGISTERED
          && !Thread.currentThread().isInterrupted())
        recipients.clearIfUnchanged(recipient.getUserId(), token, LocalDateTime.now(clock));
    } catch (RuntimeException error) {
      log.warn(
          "FCM recipient failed: userId={}, cause={}",
          recipient.getUserId(),
          error.getClass().getSimpleName());
    }
  }
}
