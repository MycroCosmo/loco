package com.loco.notifications;

import java.util.Map;

/** 전송 경계. Firebase 구현은 알림을 발생시키는 API 이관 완료 후 추가한다. */
public interface NotificationSender {
  record Message(String title, String body, Map<String, String> data) {}

  void sendToUser(String userId, Message message);

  void sendToMap(String mapId, String excludeUserId, Message message);
}
