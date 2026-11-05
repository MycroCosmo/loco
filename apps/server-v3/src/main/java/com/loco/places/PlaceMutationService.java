package com.loco.places;

import com.loco.maps.*;
import com.loco.notifications.NotificationSender;
import com.loco.users.User;
import com.loco.web.ApiProblem;
import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
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
  private final NotificationSender sender;
  private final Executor executor;
  private final java.time.Clock clock;

  public PlaceMutationService(
      PlaceRepository places,
      MapRepository maps,
      MapMemberRepository members,
      EntityManager em,
      TransactionTemplate transaction,
      PlaceQueryRepository queries,
      MarkerCells cells,
      NotificationSender sender,
      @Qualifier("applicationTaskExecutor") Executor executor,
      java.time.Clock clock) {
    this.places = places;
    this.maps = maps;
    this.members = members;
    this.em = em;
    this.transaction = transaction;
    this.queries = queries;
    this.cells = cells;
    this.sender = sender;
    this.executor = executor;
    this.clock = clock;
  }

  private record Changed(PlaceResponses.Core result, double oldLatitude, double oldLongitude) {}

  public PlaceResponses.Core update(String id, String userId, PlaceInputs input) {
    var changed =
        transaction.execute(
            status -> {
              var place =
                  places.findById(id).orElseThrow(() -> ApiProblem.notFound("장소를 찾을 수 없습니다."));
              if (!place.getCreatedBy().getId().equals(userId))
                throw ApiProblem.forbidden("수정 권한이 없습니다.");
              // v2는 지도방을 나간 작성자도 수정 가능하다. 멤버십 재검사는 후속 개선 후보다.
              double oldLat = PrismaFloats.input(place.getLatitude()),
                  oldLng = PrismaFloats.input(place.getLongitude());
              place.update(input, java.time.LocalDateTime.now(clock));
              places.flush();
              return new Changed(queries.core(id), oldLat, oldLng);
            });
    var result = changed.result();
    cells.refresh(result.mapId(), changed.oldLatitude(), changed.oldLongitude());
    if (changed.oldLatitude() != result.latitude().doubleValue()
        || changed.oldLongitude() != result.longitude().doubleValue())
      cells.refresh(
          result.mapId(), result.latitude().doubleValue(), result.longitude().doubleValue());
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
    var result =
        transaction.execute(
            status -> {
              var place =
                  places.saveAndFlush(
                      Place.create(map, em.getReference(User.class, userId), input));
              return queries.core(place.getId());
            });
    cells.refresh(mapId, result.latitude().doubleValue(), result.longitude().doubleValue());
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
    executor.execute(
        () -> {
          try {
            sender.sendToMap(mapId, userId, message);
          } catch (RuntimeException error) {
            org.slf4j.LoggerFactory.getLogger(PlaceMutationService.class)
                .warn("Place notification failed", error);
          }
        });
    return result;
  }
}
