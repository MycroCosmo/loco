package com.loco.places;

import com.loco.security.AuthenticatedApi;
import com.loco.security.JwtPrincipal;
import com.loco.web.ApiController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@ApiController
@AuthenticatedApi
public class MarkerController {
  private final MarkerQueryRepository markers;

  public MarkerController(MarkerQueryRepository markers) {
    this.markers = markers;
  }

  @GetMapping("/maps/{mapId}/places/markers")
  public Object markers(
      @PathVariable String mapId,
      @AuthenticationPrincipal JwtPrincipal user,
      HttpServletRequest request) {
    var input = new MarkerInputs(request);
    int level = input.level();
    return markers.markers(mapId, user.userId(), input, level);
  }

  @GetMapping("/maps/{mapId}/places/marker-group")
  public Object group(
      @PathVariable String mapId,
      @AuthenticationPrincipal JwtPrincipal user,
      HttpServletRequest request) {
    return markers.group(mapId, user.userId(), new MarkerInputs(request));
  }

  @GetMapping("/maps/{mapId}/places/marker-cluster")
  public Object cluster(
      @PathVariable String mapId,
      @AuthenticationPrincipal JwtPrincipal user,
      HttpServletRequest request) {
    var input = new MarkerInputs(request);
    int precision = input.precision();
    return markers.cluster(mapId, user.userId(), input, precision);
  }
}
