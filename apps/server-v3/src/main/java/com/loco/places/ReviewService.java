package com.loco.places;

import com.loco.maps.MapMemberRepository;
import com.loco.maps.MapPermissions;
import com.loco.web.ApiProblem;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReviewService {
  private final ReviewRepository reviews;
  private final MapPermissions permissions;
  private final MapMemberRepository members;
  private final TransactionTemplate transaction;
  private final Clock clock;

  public ReviewService(
      ReviewRepository reviews,
      MapPermissions permissions,
      MapMemberRepository members,
      TransactionTemplate transaction,
      Clock clock) {
    this.reviews = reviews;
    this.permissions = permissions;
    this.members = members;
    this.transaction = transaction;
    this.clock = clock;
  }

  public ReviewResponses.Summary list(String placeId, String userId) {
    var map = reviews.access(placeId);
    permissions.requireReadable(map.mapId(), map.ownerId(), map.visibility(), userId);
    return reviews.summary(placeId);
  }

  public java.util.List<ReviewResponses.Mine> mine(String userId) {
    return reviews.mine(userId);
  }

  public ReviewResponses.Summary submit(String placeId, String userId, ReviewInputs input) {
    var map = reviews.access(placeId);
    if (!map.ownerId().equals(userId) && !members.existsByMap_IdAndUser_Id(map.mapId(), userId))
      throw ApiProblem.forbidden(
          map.visibility().equals("PUBLIC")
              ? "지도방에 가입해야 리뷰를 남길 수 있습니다."
              : "비공개 지도방에는 리뷰를 남길 수 없습니다.");
    transaction.executeWithoutResult(
        status -> reviews.upsert(placeId, userId, input, LocalDateTime.now(clock)));
    // v2는 쓰기를 커밋한 뒤 권한과 응답을 다시 조회한다.
    return list(placeId, userId);
  }

  public ReviewResponses.Summary delete(String placeId, String userId) {
    var map = reviews.access(placeId);
    permissions.requireReadable(map.mapId(), map.ownerId(), map.visibility(), userId);
    transaction.executeWithoutResult(status -> reviews.delete(placeId, userId));
    return list(placeId, userId);
  }
}
