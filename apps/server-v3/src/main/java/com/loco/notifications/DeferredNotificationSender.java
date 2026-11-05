package com.loco.notifications;

import org.springframework.stereotype.Component;

@Component
public class DeferredNotificationSender implements NotificationSender {
  @Override
  public void sendToMap(String mapId, String excludeUserId, Message message) {
    // 지도방 알림도 전체 알림 API 이관 후 Firebase 구현에 연결한다.
  }

  @Override
  public void sendToUser(String userId, Message message) {
    // 알려진 차이: Firebase 설정 유무와 관계없이 실제 푸시는 다음 알림 이관 단계까지 보류한다.
  }
}
