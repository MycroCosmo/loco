package com.loco.places;

import com.loco.security.AuthenticatedApi;
import com.loco.security.JwtPrincipal;
import com.loco.web.ApiController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@ApiController
@AuthenticatedApi
public class PlaceController {
  private final PlaceQueryRepository queries;
  private final PlaceMutationService mutations;

  public PlaceController(PlaceQueryRepository queries, PlaceMutationService mutations) {
    this.queries = queries;
    this.mutations = mutations;
  }

  @PostMapping("/maps/{mapId}/places")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  public PlaceResponses.Core create(
      @PathVariable String mapId,
      @AuthenticationPrincipal JwtPrincipal user,
      @RequestBody(required = false) com.fasterxml.jackson.databind.JsonNode body) {
    return mutations.create(mapId, user.userId(), PlaceInputs.parse(body, true));
  }

  @GetMapping("/maps/{mapId}/places")
  public PlaceResponses.Page list(
      @PathVariable String mapId,
      @AuthenticationPrincipal JwtPrincipal user,
      jakarta.servlet.http.HttpServletRequest request) {
    return queries.list(mapId, user.userId(), PlaceListing.parse(request));
  }

  @GetMapping("/places/me")
  public java.util.List<PlaceResponses.Read> mine(@AuthenticationPrincipal JwtPrincipal user) {
    return queries.mine(user.userId());
  }

  @GetMapping("/places/{id}")
  public PlaceResponses.Read detail(
      @PathVariable String id, @AuthenticationPrincipal JwtPrincipal user) {
    return queries.detail(id, user.userId());
  }

  @PutMapping("/places/{id}")
  public PlaceResponses.Core update(
      @PathVariable String id,
      @AuthenticationPrincipal JwtPrincipal user,
      @RequestBody(required = false) com.fasterxml.jackson.databind.JsonNode body) {
    return mutations.update(id, user.userId(), PlaceInputs.parse(body, false));
  }
}
