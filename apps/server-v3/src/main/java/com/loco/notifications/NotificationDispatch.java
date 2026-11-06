package com.loco.notifications;

import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 커밋된 이벤트 1건이 큐 작업 1개다. 포화·종료 거절은 HTTP 호출자에게 전파하지 않는다. */
@Component
public class NotificationDispatch {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(NotificationDispatch.class);
  private final NotificationSender sender;
  private final ThreadPoolExecutor executor;
  private final int shutdownSeconds;
  private final Set<CompletableFuture<Void>> pending = ConcurrentHashMap.newKeySet();
  private final AtomicLong rejected = new AtomicLong();
  private volatile boolean closing;

  public NotificationDispatch(
      NotificationSender sender,
      @Value("${loco.notifications.executor.core:2}") int core,
      @Value("${loco.notifications.executor.max:4}") int max,
      @Value("${loco.notifications.executor.queue:100}") int queue,
      @Value("${loco.notifications.executor.shutdown-seconds:30}") int shutdownSeconds) {
    if (core < 1 || max < core || queue < 1 || shutdownSeconds < 0)
      throw new IllegalArgumentException("Invalid notification executor limits");
    this.sender = sender;
    this.shutdownSeconds = shutdownSeconds;
    executor =
        new ThreadPoolExecutor(
            core,
            max,
            60,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(queue),
            Thread.ofPlatform().daemon().name("notification-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
  }

  public void sendToUser(String userId, NotificationSender.Message message) {
    submit(() -> sender.sendToUser(userId, message));
  }

  public void sendToMap(String mapId, String excludedUserId, NotificationSender.Message message) {
    submit(() -> sender.sendToMap(mapId, excludedUserId, message));
  }

  private void submit(Runnable operation) {
    var job = new Job(operation);
    pending.add(job.done);
    try {
      if (closing) throw new RejectedExecutionException("closing");
      executor.execute(job);
    } catch (RejectedExecutionException full) {
      rejected.incrementAndGet();
      job.finish();
      log.warn(
          "Notification event rejected: closing={}, active={}, queued={}",
          closing,
          executor.getActiveCount(),
          executor.getQueue().size());
    }
  }

  private final class Job implements Runnable {
    private final Runnable operation;
    private final CompletableFuture<Void> done = new CompletableFuture<>();

    private Job(Runnable operation) {
      this.operation = operation;
    }

    public void run() {
      try {
        operation.run();
      } catch (RuntimeException error) {
        log.warn("Notification event failed: cause={}", error.getClass().getSimpleName());
      } finally {
        finish();
      }
    }

    void finish() {
      done.complete(null);
      pending.remove(done);
    }
  }

  /** 호출 시점까지 제출된 이벤트의 완료 경계. HTTP 응답 후 비교·종료 확인에 사용한다. */
  public CompletableFuture<Void> drain() {
    return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new));
  }

  public long rejectedEvents() {
    return rejected.get();
  }

  @PreDestroy
  public void close() {
    if (closing) return;
    closing = true;
    executor.shutdown();
    try {
      if (executor.awaitTermination(shutdownSeconds, TimeUnit.SECONDS)) return;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    var discarded = executor.shutdownNow();
    discarded.forEach(task -> ((Job) task).finish());
    log.warn(
        "Notification shutdown deadline: discarded={}, active={}",
        discarded.size(),
        executor.getActiveCount());
  }
}
