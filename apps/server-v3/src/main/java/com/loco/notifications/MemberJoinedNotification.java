package com.loco.notifications;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

public record MemberJoinedNotification(String ownerId, NotificationSender.Message message) {
  @Component
  public static class Delivery {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Delivery.class);
    private final NotificationSender sender;

    public Delivery(NotificationSender sender) {
      this.sender = sender;
    }

    @TransactionalEventListener
    public void afterCommit(MemberJoinedNotification event) {
      try {
        sender.sendToUser(event.ownerId(), event.message());
      } catch (RuntimeException error) {
        log.warn("Member join notification failed", error);
      }
    }
  }
}
