package com.cms.core.history;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.transaction.annotation.*;

public class HistoryWriter {
  private final ChangeHistoryRepository history;
  private final Clock clock;
  private final HistoryContractRegistry contracts;

  public HistoryWriter(
      ChangeHistoryRepository history, Clock clock, HistoryContractRegistry contracts) {
    this.history = history;
    this.clock = clock;
    this.contracts = contracts;
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
    var payload = contracts.writing(resourceType, action, before, after);
    history.saveAndFlush(
        new ChangeHistory(
            actor,
            action,
            resourceType,
            id,
            LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS),
            payload.before(),
            payload.after(),
            payload.version()));
  }
}
