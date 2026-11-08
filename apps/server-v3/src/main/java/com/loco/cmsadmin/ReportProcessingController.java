package com.loco.cmsadmin;

import com.cms.core.account.*;
import com.cms.core.web.CmsController;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@CmsController
@RequestMapping("/admin/api/loco/reports")
public class ReportProcessingController {
  private final ReportProcessingService processing;

  public ReportProcessingController(ReportProcessingService processing) {
    this.processing = processing;
  }

  @PutMapping("/{id}/processing")
  public ReportProcessingService.View transition(
      @AuthenticationPrincipal AdminIdentity actor,
      @PathVariable String id,
      @RequestBody JsonNode body) {
    if (!body.isObject()) throw invalid();
    body.fieldNames()
        .forEachRemaining(
            key -> {
              if (!Set.of("expectedStatus", "nextStatus", "note").contains(key)) throw invalid();
            });
    if (!body.path("expectedStatus").isTextual()
        || !body.path("nextStatus").isTextual()
        || body.hasNonNull("note") && !body.get("note").isTextual()) throw invalid();
    ReportProcessing.Status expected, next;
    try {
      expected = ReportProcessing.Status.valueOf(body.get("expectedStatus").asText());
      next = ReportProcessing.Status.valueOf(body.get("nextStatus").asText());
    } catch (IllegalArgumentException invalid) {
      throw invalid();
    }
    return processing.transition(
        actor,
        id,
        new ReportProcessingService.Command(
            expected, next, body.hasNonNull("note") ? body.get("note").asText() : null));
  }

  private AdminProblem invalid() {
    return new AdminProblem(400, "Invalid report processing input");
  }
}
