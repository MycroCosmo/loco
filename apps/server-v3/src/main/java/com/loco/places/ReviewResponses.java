package com.loco.places;

import static com.loco.places.PlaceResponses.*;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.loco.maps.MapResponses;
import jakarta.persistence.Tuple;
import java.util.List;
import java.util.Map;

public final class ReviewResponses {
  private ReviewResponses() {}

  public record Core(
      String id,
      String placeId,
      String userId,
      int rating,
      String content,
      String createdAt,
      String updatedAt) {}

  public record Read(@JsonUnwrapped Core review, MapResponses.Owner user) {}

  public record ReviewedPlace(@JsonUnwrapped PlaceResponses.Core place, Map<String, String> map) {}

  public record Mine(@JsonUnwrapped Core review, ReviewedPlace place) {}

  public record Summary(Number averageRating, long reviewCount, List<Read> reviews) {}

  static Core review(Tuple t) {
    return new Core(
        text(t, "reviewId"),
        text(t, "placeId"),
        text(t, "userId"),
        number(t, "rating").intValue(),
        text(t, "content"),
        time(t, "reviewCreated"),
        time(t, "reviewUpdated"));
  }

  static Read read(Tuple t) {
    return new Read(
        review(t),
        new MapResponses.Owner(text(t, "userId"), text(t, "nickname"), text(t, "profileImageUrl")));
  }

  static Mine mine(Tuple t) {
    return new Mine(
        review(t),
        new ReviewedPlace(
            PlaceResponses.core(t), Map.of("id", text(t, "mapId"), "title", text(t, "mapTitle"))));
  }
}
