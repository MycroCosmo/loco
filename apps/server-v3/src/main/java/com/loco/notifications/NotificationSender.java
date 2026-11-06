package com.loco.notifications;

import java.util.Map;

/** 작업 내부 전송 경계. HTTP 호출 지점에서는 NotificationDispatch를 사용한다. */
public interface NotificationSender {
  record Message(String title, String body, Map<String, String> data) {}

  void sendToUser(String userId, Message message);

  void sendToMap(String mapId, String excludeUserId, Message message);
}
