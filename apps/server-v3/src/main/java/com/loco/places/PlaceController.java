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

  public PlaceController(PlaceQueryRepository queries) {
    this.queries = queries;
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
}
