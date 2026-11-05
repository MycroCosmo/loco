package com.loco.maps;

import com.loco.security.AuthenticatedApi;
import com.loco.security.JwtPrincipal;
import com.loco.web.ApiController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@ApiController
@AuthenticatedApi
public class MapController {
  private final MapService maps;
  private final MapQueryRepository queries;
  private final MapMutationService mutations;

  public MapController(MapService maps, MapQueryRepository queries, MapMutationService mutations) {
    this.maps = maps;
    this.queries = queries;
    this.mutations = mutations;
  }

  @GetMapping("/maps")
  public MapResponses.Page list(
      @AuthenticationPrincipal JwtPrincipal user,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) String limit,
      @RequestParam(required = false) String visibility,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) String search) {
    return queries.list(user.userId(), MapInputs.listing(cursor, limit, visibility, sort, search));
  }

  @GetMapping("/maps/me")
  public java.util.List<MapResponses.Detail> mine(
      @AuthenticationPrincipal JwtPrincipal user, @RequestParam(required = false) String type) {
    return queries.mine(user.userId(), type);
  }

  @GetMapping("/maps/{id}")
  public MapResponses.Detail detail(
      @AuthenticationPrincipal JwtPrincipal user, @PathVariable String id) {
    return maps.detail(id, user.userId());
  }

  @PostMapping("/maps")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  public MapResponses.Core create(
      @AuthenticationPrincipal JwtPrincipal user,
      @RequestBody(required = false) com.fasterxml.jackson.databind.JsonNode body) {
    return mutations.create(user.userId(), MapMutationInputs.parse(body, true));
  }

  @PutMapping("/maps/{id}")
  public MapResponses.Core update(
      @AuthenticationPrincipal JwtPrincipal user,
      @PathVariable String id,
      @RequestBody(required = false) com.fasterxml.jackson.databind.JsonNode body) {
    return mutations.update(id, user.userId(), MapMutationInputs.parse(body, false));
  }

  @DeleteMapping("/maps/{id}")
  public java.util.Map<String, String> delete(
      @AuthenticationPrincipal JwtPrincipal user,
      @PathVariable String id,
      @RequestParam(required = false) String force) {
    mutations.delete(id, user.userId(), "true".equals(force));
    return java.util.Map.of("message", "지도방이 삭제되었습니다.");
  }
}
