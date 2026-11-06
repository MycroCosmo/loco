package com.loco.favorites;

import com.fasterxml.jackson.databind.JsonNode;
import com.loco.security.*;
import com.loco.web.ApiController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@ApiController
@AuthenticatedApi
public class FavoriteController {
  private final FavoriteService favorites;

  public FavoriteController(FavoriteService favorites) {
    this.favorites = favorites;
  }

  @GetMapping("/favorites")
  public Object list(@AuthenticationPrincipal JwtPrincipal user) {
    return favorites.list(user.userId());
  }

  @PostMapping("/favorites")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  public Object create(
      @AuthenticationPrincipal JwtPrincipal user, @RequestBody(required = false) JsonNode body) {
    return favorites.add(user.userId(), FavoriteInputs.mapId(body));
  }

  @DeleteMapping("/favorites/{id}")
  public Object delete(@AuthenticationPrincipal JwtPrincipal user, @PathVariable String id) {
    return favorites.remove(id, user.userId());
  }
}
