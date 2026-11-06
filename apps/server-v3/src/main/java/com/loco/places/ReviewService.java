package com.loco.places;

import com.loco.maps.MapPermissions;
import org.springframework.stereotype.Service;

@Service
public class ReviewService {
  private final ReviewRepository reviews;
  private final MapPermissions permissions;

  public ReviewService(ReviewRepository reviews, MapPermissions permissions) {
    this.reviews = reviews;
    this.permissions = permissions;
  }

  public ReviewResponses.Summary list(String placeId, String userId) {
    var map = reviews.access(placeId);
    permissions.requireReadable(map.mapId(), map.ownerId(), map.visibility(), userId);
    return reviews.summary(placeId);
  }

  public java.util.List<ReviewResponses.Mine> mine(String userId) {
    return reviews.mine(userId);
  }
}
