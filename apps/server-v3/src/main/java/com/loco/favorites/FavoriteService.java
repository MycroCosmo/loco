package com.loco.favorites;

import com.loco.maps.*;
import com.loco.users.*;
import com.loco.web.ApiProblem;
import jakarta.persistence.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class FavoriteService {
  private final EntityManager em;
  private final TransactionTemplate transaction;

  public FavoriteService(EntityManager em, TransactionTemplate transaction) {
    this.em = em;
    this.transaction = transaction;
  }

  public FavoriteResponses.Created add(String user, String mapId) {
    try {
      var favorite =
          transaction.execute(
              status -> {
                var map = em.find(MapRoom.class, mapId);
                if (map == null) throw ApiProblem.notFound("지도방을 찾을 수 없습니다.");
                if (map.getOwner().getId().equals(user))
                  throw ApiProblem.forbidden("내가 만든 지도방은 즐겨찾기할 수 없습니다.");
                var item = Favorite.create(em.getReference(User.class, user), map);
                em.persist(item);
                return item;
              });
      return favorite.response();
    } catch (RuntimeException error) {
      // v2 P2002와 같이 유니크 오류만 롤백 완료 후 409로 변환한다.
      for (Throwable cause = error; cause != null; cause = cause.getCause())
        if (cause instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState()))
          throw ApiProblem.conflict("이미 즐겨찾기한 지도방입니다.");
      throw error;
    }
  }

  public Map<String, String> remove(String id, String user) {
    transaction.executeWithoutResult(
        status -> {
          var favorite = em.find(Favorite.class, id);
          if (favorite == null) throw ApiProblem.notFound("즐겨찾기를 찾을 수 없습니다.");
          if (!favorite.userId().equals(user)) throw ApiProblem.forbidden("삭제 권한이 없습니다.");
          // 둘 다 사전 조회한 동시 삭제의 stale delete는 v2처럼 기본 500으로 남긴다.
          em.remove(favorite);
        });
    return Map.of("message", "즐겨찾기가 삭제되었습니다.");
  }

  public List<FavoriteResponses.Item> list(String user) {
    @SuppressWarnings("unchecked")
    List<Tuple> rows =
        em.createNativeQuery(
                """
      SELECT f.id AS "favoriteId",f."userId",f."mapId",f."createdAt" AS "favoriteCreated",m.*,u.nickname AS "ownerNickname",
      (SELECT count(*) FROM places p WHERE p."mapId"=m.id) AS "placeCount",
      (SELECT count(*) FROM favorites x WHERE x."mapId"=m.id) AS "favoriteCount",
      (SELECT count(*) FROM map_members mm WHERE mm."mapId"=m.id) AS "memberCount"
      FROM favorites f JOIN maps m ON m.id=f."mapId" JOIN users u ON u.id=m."ownerId"
      WHERE f."userId"=:user ORDER BY f."createdAt" DESC
      """,
                Tuple.class)
            .setParameter("user", user)
            .getResultList();
    return rows.stream()
        .map(
            t -> {
              var room =
                  new MapResponses.Core(
                      text(t, "id"),
                      text(t, "ownerId"),
                      text(t, "title"),
                      text(t, "description"),
                      t.get("tags") == null ? new String[0] : (String[]) t.get("tags"),
                      text(t, "coverImageUrl"),
                      text(t, "visibility"),
                      text(t, "inviteCode"),
                      time(t, "createdAt"),
                      time(t, "updatedAt"));
              var detail =
                  new FavoriteResponses.Room(
                      room,
                      new FavoriteResponses.Owner(room.ownerId(), text(t, "ownerNickname")),
                      new MapResponses.Counts(
                          number(t, "placeCount"),
                          number(t, "favoriteCount"),
                          number(t, "memberCount")));
              return new FavoriteResponses.Item(
                  new FavoriteResponses.Created(
                      text(t, "favoriteId"),
                      text(t, "userId"),
                      text(t, "mapId"),
                      time(t, "favoriteCreated")),
                  detail);
            })
        .toList();
  }

  private static String text(Tuple t, String key) {
    var v = t.get(key);
    return v == null ? null : v.toString();
  }

  private static long number(Tuple t, String key) {
    return ((Number) t.get(key)).longValue();
  }

  private static String time(Tuple t, String key) {
    Object v = t.get(key);
    return UserResponses.timestamp(
        v instanceof java.sql.Timestamp x ? x.toLocalDateTime() : (java.time.LocalDateTime) v);
  }
}
