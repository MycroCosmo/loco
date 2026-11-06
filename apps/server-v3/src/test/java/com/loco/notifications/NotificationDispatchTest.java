package com.loco.notifications;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class NotificationDispatchTest {
  private final NotificationSender.Message message =
      new NotificationSender.Message("title", "body", Map.of());

  @Test
  void defaultPoolAccepts104EventsThenRejectsWithoutRunningOnCaller() throws Exception {
    var sender = mock(NotificationSender.class);
    var release = new CountDownLatch(1);
    var started = new CountDownLatch(4);
    var count = new AtomicInteger();
    doAnswer(
            call -> {
              assertThat(Thread.currentThread().getName()).startsWith("notification-");
              started.countDown();
              release.await();
              count.incrementAndGet();
              return null;
            })
        .when(sender)
        .sendToMap(anyString(), anyString(), any());
    var dispatch = new NotificationDispatch(sender, 2, 4, 100, 1);
    try {
      for (int i = 0; i < 104; i++) dispatch.sendToMap("map", "excluded", message);
      assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
      dispatch.sendToMap("overflow", "excluded", message);
      assertThat(dispatch.rejectedEvents()).isOne();
      assertThat(count.get()).isZero();
      var done = dispatch.drain();
      release.countDown();
      done.get(5, TimeUnit.SECONDS);
      assertThat(count.get()).isEqualTo(104);
    } finally {
      release.countDown();
      dispatch.close();
    }
  }

  @Test
  void senderExceptionIsIsolatedAndNextEventStillRuns() throws Exception {
    var sender = mock(NotificationSender.class);
    doThrow(new IllegalStateException("injected")).when(sender).sendToUser(eq("bad"), any());
    var dispatch = new NotificationDispatch(sender, 1, 1, 2, 1);
    try {
      dispatch.sendToUser("bad", message);
      dispatch.sendToUser("good", message);
      dispatch.drain().get(3, TimeUnit.SECONDS);
      verify(sender).sendToUser(eq("good"), any());
    } finally {
      dispatch.close();
    }
  }

  @Test
  void shutdownCancelsQueuedEventsInterruptsWorkerAndRejectsNewEvents() throws Exception {
    var sender = mock(NotificationSender.class);
    var started = new CountDownLatch(1);
    var stopped = new CountDownLatch(1);
    doAnswer(
            call -> {
              started.countDown();
              try {
                new CountDownLatch(1).await();
              } finally {
                stopped.countDown();
              }
              return null;
            })
        .when(sender)
        .sendToUser(eq("running"), any());
    var dispatch = new NotificationDispatch(sender, 1, 1, 2, 0);
    dispatch.sendToUser("running", message);
    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
    dispatch.sendToUser("queued", message);
    var drain = dispatch.drain();
    dispatch.close();
    assertThat(stopped.await(2, TimeUnit.SECONDS)).isTrue();
    drain.get(2, TimeUnit.SECONDS);
    dispatch.sendToUser("after-close", message);
    assertThat(dispatch.rejectedEvents()).isOne();
    verify(sender, never()).sendToUser(eq("queued"), any());
    verify(sender, never()).sendToUser(eq("after-close"), any());
  }
}
