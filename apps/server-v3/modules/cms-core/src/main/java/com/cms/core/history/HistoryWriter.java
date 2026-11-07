package com.cms.core.history;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.transaction.annotation.*;

public class HistoryWriter {
  private final ChangeHistoryRepository history;
  private final Clock clock;

  public HistoryWriter(ChangeHistoryRepository history, Clock clock) {
    this.history = history;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void account(
      String actor,
      String action,
      String id,
      Map<String, Object> before,
      Map<String, Object> after) {
    record(actor, action, "admin-account", id, before, after);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(
      String actor,
      String action,
      String resourceType,
      String id,
      Map<String, Object> before,
      Map<String, Object> after) {
    history.saveAndFlush(
        new ChangeHistory(
            actor,
            action,
            resourceType,
            id,
            LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS),
            HistoryPayload.checked(resourceType, before),
            HistoryPayload.checked(resourceType, after)));
  }
}
