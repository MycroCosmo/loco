package com.loco.places;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class MarkerCells {
  private final EntityManager em;
  private final TransactionTemplate transaction;
  private final Clock clock;

  public MarkerCells(EntityManager em, TransactionTemplate transaction, Clock clock) {
    this.em = em;
    this.transaction = new TransactionTemplate(transaction.getTransactionManager(), transaction);
    this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.clock = clock;
  }

  public void refresh(String mapId, double latitude, double longitude) {
    refresh(mapId, latitude, longitude, latitude, longitude);
  }

  public void refresh(
      String mapId, double oldLatitude, double oldLongitude, double latitude, double longitude) {
    int mapKey = UUID.fromString(mapId).hashCode();
    for (int precision = 1; precision <= 3; precision++) {
      final int scale = precision;
      // E-17: each precision commits both affected cells; the place has already committed.
      transaction.executeWithoutResult(
          status -> {
            // E-22: a separate statement gives the DML a fresh READ COMMITTED snapshot
            // after waiting. UUID hash collisions only serialize unrelated maps.
            em.createNativeQuery("SELECT 1 FROM pg_advisory_xact_lock(:mapKey, :precision)")
                .setParameter("mapKey", mapKey)
                .setParameter("precision", scale)
                .getSingleResult();
            em.createNativeQuery(SQL.replace("$scale", Integer.toString(scale)))
                .setParameter("map", mapId)
                .setParameter("p", scale)
                .setParameter("oldLat", oldLatitude)
                .setParameter("oldLng", oldLongitude)
                .setParameter("lat", latitude)
                .setParameter("lng", longitude)
                .setParameter("now", LocalDateTime.now(clock))
                .executeUpdate();
          });
    }
  }

  private static final String SQL =
      """
      WITH keys AS MATERIALIZED (
        SELECT DISTINCT ROUND(lat::numeric,$scale)::text AS lat,ROUND(lng::numeric,$scale)::text AS lng
        FROM (VALUES (CAST(:oldLat AS float8),CAST(:oldLng AS float8)),
                     (CAST(:lat AS float8),CAST(:lng AS float8))) AS coords(lat,lng)
      ), aggregated AS MATERIALIZED (
        SELECT k.lat,k.lng,a.* FROM keys k CROSS JOIN LATERAL (
          SELECT AVG(latitude)::float8 AS latitude,AVG(longitude)::float8 AS longitude,
            MIN(latitude) AS "minLat",MAX(latitude) AS "maxLat",MIN(longitude) AS "minLng",MAX(longitude) AS "maxLng",
            COUNT(*)::int AS count,
            COUNT(DISTINCT ROUND(latitude::numeric,6)::text||':'||ROUND(longitude::numeric,6)::text)::int AS exact,
            MIN(ROUND(latitude::numeric,6)::text||':'||ROUND(longitude::numeric,6)::text) AS "groupKey",
            MIN(id) AS "placeId",MIN(name) AS "placeName"
          FROM places WHERE "mapId"=:map
            AND ROUND(latitude::numeric,$scale)::text=k.lat AND ROUND(longitude::numeric,$scale)::text=k.lng
        ) a
      ), removed AS (
        DELETE FROM place_marker_cells c USING aggregated a
        WHERE a.count=0 AND c."mapId"=:map AND c.precision=:p
          AND c."cellLatKey"=a.lat AND c."cellLngKey"=a.lng
      )
      INSERT INTO place_marker_cells("mapId",precision,"cellLatKey","cellLngKey",latitude,longitude,
        "minLat","maxLat","minLng","maxLng",count,"exactKeyCount","groupKey","placeId","placeName","updatedAt")
      SELECT :map,:p,lat,lng,latitude,longitude,"minLat","maxLat","minLng","maxLng",count,exact,
        CASE WHEN exact=1 THEN "groupKey" END,CASE WHEN count=1 THEN "placeId" END,
        CASE WHEN count=1 THEN "placeName" END,:now
      FROM aggregated WHERE count>0 ORDER BY lat COLLATE "C",lng COLLATE "C"
      ON CONFLICT ("mapId",precision,"cellLatKey","cellLngKey") DO UPDATE SET
        latitude=EXCLUDED.latitude,longitude=EXCLUDED.longitude,"minLat"=EXCLUDED."minLat","maxLat"=EXCLUDED."maxLat",
        "minLng"=EXCLUDED."minLng","maxLng"=EXCLUDED."maxLng",count=EXCLUDED.count,"exactKeyCount"=EXCLUDED."exactKeyCount",
        "groupKey"=EXCLUDED."groupKey","placeId"=EXCLUDED."placeId","placeName"=EXCLUDED."placeName","updatedAt"=EXCLUDED."updatedAt"
      """;
}
