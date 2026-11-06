package com.loco.places;

import com.loco.security.AuthenticatedApi;
import com.loco.security.JwtPrincipal;
import com.loco.web.ApiController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@ApiController
@AuthenticatedApi
public class ReviewController {
  private final ReviewService reviews;

  public ReviewController(ReviewService reviews) {
    this.reviews = reviews;
  }

  @GetMapping("/places/{placeId}/reviews")
  public ReviewResponses.Summary list(
      @PathVariable String placeId, @AuthenticationPrincipal JwtPrincipal user) {
    return reviews.list(placeId, user.userId());
  }

  @GetMapping("/places/reviews/me")
  public java.util.List<ReviewResponses.Mine> mine(@AuthenticationPrincipal JwtPrincipal user) {
    return reviews.mine(user.userId());
  }

  @PostMapping("/places/{placeId}/reviews")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  public ReviewResponses.Summary submit(
      @PathVariable String placeId,
      @AuthenticationPrincipal JwtPrincipal user,
      @RequestBody(required = false) com.fasterxml.jackson.databind.JsonNode body) {
    return reviews.submit(placeId, user.userId(), ReviewInputs.parse(body));
  }

  @DeleteMapping("/places/{placeId}/reviews/me")
  public ReviewResponses.Summary delete(
      @PathVariable String placeId, @AuthenticationPrincipal JwtPrincipal user) {
    return reviews.delete(placeId, user.userId());
  }
}
