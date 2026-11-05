package com.loco.notifications;

import org.springframework.stereotype.Component;

@Component
public class DeferredNotificationSender implements NotificationSender {
    @Override public void sendToUser(String userId,Message message) {
        // 알려진 차이: Firebase 설정 유무와 관계없이 실제 푸시는 다음 알림 이관 단계까지 보류한다.
    }
}
