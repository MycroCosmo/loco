package com.loco.places;

import com.loco.maps.*;
import com.loco.notifications.NotificationSender;
import com.loco.users.User;
import com.loco.web.ApiProblem;
import jakarta.persistence.EntityManager;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PlaceMutationService {
  private final PlaceRepository places;
  private final MapRepository maps;
  private final MapMemberRepository members;
  private final EntityManager em;
  private final TransactionTemplate transaction;
  private final PlaceQueryRepository queries;
  private final MarkerCells cells;
  private final com.loco.notifications.NotificationDispatch sender;
  private final java.time.Clock clock;
  private final com.loco.images.LocalImageFiles files;

  public PlaceMutationService(
      PlaceRepository places,
      MapRepository maps,
      MapMemberRepository members,
      EntityManager em,
      TransactionTemplate transaction,
      PlaceQueryRepository queries,
      MarkerCells cells,
      com.loco.notifications.NotificationDispatch sender,
      java.time.Clock clock,
      com.loco.images.LocalImageFiles files) {
    this.places = places;
    this.maps = maps;
    this.members = members;
    this.em = em;
    this.transaction = transaction;
    this.queries = queries;
    this.cells = cells;
    this.sender = sender;
    this.clock = clock;
    this.files = files;
  }

  private record Deleted(String mapId, double latitude, double longitude, String[] imageUrls) {}

  public void delete(String id, String userId) {
    var deleted =
        transaction.execute(
            status -> {
              var place =
                  places.findById(id).orElseThrow(() -> ApiProblem.notFound("장소를 찾을 수 없습니다."));
              boolean owner = place.getMap().getOwner().getId().equals(userId);
              if (!owner && !place.getCreatedBy().getId().equals(userId))
                throw ApiProblem.forbidden("삭제 권한이 없습니다.");
              // v2처럼 작성자/방장만 검사한다. 지도방을 나간 작성자 차단은 후속 개선 후보다.
              var result =
                  new Deleted(
                      place.getMap().getId(),
                      place.getLatitude(),
                      place.getLongitude(),
                      place.getImageUrls() == null ? new String[0] : place.getImageUrls().clone());
              places.delete(place);
              places.flush();
              return result;
            });
    cells.refresh(deleted.mapId(), deleted.latitude(), deleted.longitude());
    for (String image : deleted.imageUrls()) files.delete(image);
  }

  private record Changed(
      PlaceResponses.Core result,
      double oldLatitude,
      double oldLongitude,
      double latitude,
      double longitude) {}

  private record Created(PlaceResponses.Core result, double latitude, double longitude) {}

  public PlaceResponses.Core update(String id, String userId, PlaceInputs input) {
    var changed =
        transaction.execute(
            status -> {
              var place =
                  places.findById(id).orElseThrow(() -> ApiProblem.notFound("장소를 찾을 수 없습니다."));
              if (!place.getCreatedBy().getId().equals(userId))
                throw ApiProblem.forbidden("수정 권한이 없습니다.");
              // v2는 지도방을 나간 작성자도 수정 가능하다. 멤버십 재검사는 후속 개선 후보다.
              double oldLat = place.getLatitude(), oldLng = place.getLongitude();
              place.update(input, java.time.LocalDateTime.now(clock));
              places.flush();
              return new Changed(
                  queries.core(id), oldLat, oldLng, place.getLatitude(), place.getLongitude());
            });
    var result = changed.result();
    cells.refresh(
        result.mapId(),
        changed.oldLatitude(),
        changed.oldLongitude(),
        changed.latitude(),
        changed.longitude());
    return result;
  }

  public PlaceResponses.Core create(String mapId, String userId, PlaceInputs input) {
    var map = maps.findById(mapId).orElseThrow(() -> ApiProblem.notFound("지도방을 찾을 수 없습니다."));
    if (!map.getOwner().getId().equals(userId)
        && !members.existsByMap_IdAndUser_Id(mapId, userId)) {
      throw ApiProblem.forbidden(
          map.getVisibility() == Visibility.PRIVATE
              ? "비공개 지도방에는 장소를 추가할 수 없습니다."
              : "지도방에 가입해야 장소를 추가할 수 있습니다.");
    }
    var created =
        transaction.execute(
            status -> {
              var place =
                  places.saveAndFlush(
                      Place.create(map, em.getReference(User.class, userId), input));
              return new Created(
                  queries.core(place.getId()), place.getLatitude(), place.getLongitude());
            });
    var result = created.result();
    cells.refresh(mapId, created.latitude(), created.longitude());
    var message =
        new NotificationSender.Message(
            "새 장소 추가",
            (result.createdBy().nickname() == null ? "누군가" : result.createdBy().nickname())
                + "님이 \""
                + map.getTitle()
                + "\"에 \""
                + result.name()
                + "\"을 추가했습니다.",
            Map.of("type", "place_added", "mapId", mapId, "placeId", result.id()));
    sender.sendToMap(mapId, userId, message);
    return result;
  }
}
