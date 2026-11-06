package com.loco.reports;

import com.fasterxml.jackson.databind.JsonNode;
import com.loco.security.*;
import com.loco.web.ApiController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@ApiController
@AuthenticatedApi
public class ReportController {
  private final ReportService reports;

  public ReportController(ReportService reports) {
    this.reports = reports;
  }

  @PostMapping("/reports")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  public Object submit(
      @AuthenticationPrincipal JwtPrincipal user, @RequestBody(required = false) JsonNode body) {
    return reports.submit(user.userId(), ReportInputs.parse(body));
  }
}
