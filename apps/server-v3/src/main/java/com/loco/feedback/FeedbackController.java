package com.loco.feedback;

import com.fasterxml.jackson.databind.JsonNode;
import com.loco.security.*;
import com.loco.web.ApiController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@ApiController
@AuthenticatedApi
public class FeedbackController {
  private final FeedbackService feedback;

  public FeedbackController(FeedbackService feedback) {
    this.feedback = feedback;
  }

  @PostMapping("/feedback")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  public Object submit(
      @AuthenticationPrincipal JwtPrincipal user, @RequestBody(required = false) JsonNode body) {
    return feedback.submit(user.userId(), FeedbackInputs.content(body));
  }
}
