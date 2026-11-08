package com.loco.places;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class MarkerCells {
  private final EntityManager em;
  private final TransactionTemplate transaction;
  private final Clock clock;

  public MarkerCells(EntityManager em, TransactionTemplate transaction, Clock clock) {
    this.em = em;
    this.transaction = transaction;
    this.clock = clock;
  }

  public void refresh(String mapId, double latitude, double longitude) {
    for (int precision = 1; precision <= 3; precision++)
      refreshCell(mapId, precision, latitude, longitude);
  }

  private void refreshCell(String mapId, int precision, double latitude, double longitude) {
    // SELECT와 쓰기도 분리한다. v2의 동시 갱신/부분 커밋 제약을 이관 중 유지한다.
    var cell =
        (Tuple)
            em.createNativeQuery(
                    """
      SELECT ROUND(CAST(:latitude AS float8)::numeric,:p)::text AS "cellLatKey",
      ROUND(CAST(:longitude AS float8)::numeric,:p)::text AS "cellLngKey",
      AVG(latitude)::float8 AS latitude, AVG(longitude)::float8 AS longitude,
      MIN(latitude) AS "minLat", MAX(latitude) AS "maxLat", MIN(longitude) AS "minLng", MAX(longitude) AS "maxLng",
      COUNT(*)::int AS count,
      COUNT(DISTINCT CONCAT(ROUND(latitude::numeric,6)::text,':',ROUND(longitude::numeric,6)::text))::int AS "exactKeyCount",
      MIN(CONCAT(ROUND(latitude::numeric,6)::text,':',ROUND(longitude::numeric,6)::text)) AS "groupKey",
      MIN(id) AS "placeId", MIN(name) AS "placeName"
      FROM places WHERE "mapId"=:map
      AND ROUND(latitude::numeric,:p)=ROUND(CAST(:latitude AS float8)::numeric,:p)
      AND ROUND(longitude::numeric,:p)=ROUND(CAST(:longitude AS float8)::numeric,:p)
      """,
                    Tuple.class)
                .setParameter("map", mapId)
                .setParameter("p", precision)
                .setParameter("latitude", latitude)
                .setParameter("longitude", longitude)
                .getSingleResult();
    String lat = cell.get("cellLatKey").toString(), lng = cell.get("cellLngKey").toString();
    int count = ((Number) cell.get("count")).intValue();
    transaction.executeWithoutResult(
        status -> {
          if (count == 0) {
            em.createNativeQuery(
                    "DELETE FROM place_marker_cells WHERE \"mapId\"=:map AND precision=:p AND \"cellLatKey\"=:lat AND \"cellLngKey\"=:lng")
                .setParameter("map", mapId)
                .setParameter("p", precision)
                .setParameter("lat", lat)
                .setParameter("lng", lng)
                .executeUpdate();
            return;
          }
          int exact = ((Number) cell.get("exactKeyCount")).intValue();
          var q =
              em.createNativeQuery(
                  """
        INSERT INTO place_marker_cells("mapId",precision,"cellLatKey","cellLngKey",latitude,longitude,"minLat","maxLat","minLng","maxLng",count,"exactKeyCount","groupKey","placeId","placeName","updatedAt")
        VALUES(:map,:p,:lat,:lng,:latitude,:longitude,:minLat,:maxLat,:minLng,:maxLng,:count,:exact,:groupKey,:placeId,:placeName,:now)
        ON CONFLICT ("mapId",precision,"cellLatKey","cellLngKey") DO UPDATE SET
        latitude=EXCLUDED.latitude,longitude=EXCLUDED.longitude,"minLat"=EXCLUDED."minLat","maxLat"=EXCLUDED."maxLat","minLng"=EXCLUDED."minLng","maxLng"=EXCLUDED."maxLng",
        count=EXCLUDED.count,"exactKeyCount"=EXCLUDED."exactKeyCount","groupKey"=EXCLUDED."groupKey","placeId"=EXCLUDED."placeId","placeName"=EXCLUDED."placeName","updatedAt"=EXCLUDED."updatedAt"
        """);
          q.setParameter("map", mapId)
              .setParameter("p", precision)
              .setParameter("lat", lat)
              .setParameter("lng", lng);
          for (String key :
              new String[] {"latitude", "longitude", "minLat", "maxLat", "minLng", "maxLng"})
            q.setParameter(key, PrismaFloats.input(((Number) cell.get(key)).doubleValue()));
          q.setParameter("count", count)
              .setParameter("exact", exact)
              .setParameter("groupKey", exact == 1 ? cell.get("groupKey") : null)
              .setParameter("placeId", count == 1 ? cell.get("placeId") : null)
              .setParameter("placeName", count == 1 ? cell.get("placeName") : null)
              .setParameter("now", LocalDateTime.now(clock))
              .executeUpdate();
        });
  }
}
