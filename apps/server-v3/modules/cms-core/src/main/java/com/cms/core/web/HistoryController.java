package com.cms.core.web;

import com.cms.core.account.AdminIdentity;
import com.cms.core.history.*;
import java.time.Instant;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/api/core/history")
public class HistoryController {
  private final HistoryQueryService history;

  public HistoryController(HistoryQueryService history) {
    this.history = history;
  }

  @GetMapping
  public HistoryQueryService.Page list(
      @AuthenticationPrincipal AdminIdentity actor,
      @RequestParam(required = false) String resourceType,
      @RequestParam(required = false) String resourceId,
      @RequestParam(required = false) String actorId,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant until,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "50") int size) {
    return history.list(
        actor,
        new HistoryQueryService.Filter(
            resourceType, resourceId, actorId, from, until, cursor, size));
  }

  @GetMapping("/{id}")
  public ChangeHistory.View detail(
      @AuthenticationPrincipal AdminIdentity actor, @PathVariable String id) {
    return history.detail(actor, id);
  }
}
