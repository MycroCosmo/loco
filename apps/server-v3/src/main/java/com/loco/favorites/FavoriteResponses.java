package com.loco.favorites;

import com.fasterxml.jackson.annotation.*;
import com.loco.maps.MapResponses;

public final class FavoriteResponses {
  public record Created(String id, String userId, String mapId, String createdAt) {}

  public record Owner(String id, String nickname) {}

  public record Room(
      @JsonUnwrapped MapResponses.Core map,
      Owner owner,
      @JsonProperty("_count") MapResponses.Counts count) {}

  public record Item(@JsonUnwrapped Created favorite, Room map) {}
}
