package com.loco.places;

import com.loco.maps.MapPermissions;
import com.loco.maps.MapRepository;
import com.loco.web.ApiProblem;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class PlaceQueryRepository {
  private final EntityManager em;
  private final MapRepository maps;
  private final MapPermissions permissions;

  public PlaceQueryRepository(EntityManager em, MapRepository maps, MapPermissions permissions) {
    this.em = em;
    this.maps = maps;
    this.permissions = permissions;
  }

  private static final String PROJECTION =
      """
 SELECT p.*, u.nickname AS "authorNickname",u."profileImageUrl" AS "authorImage",
 m."ownerId" AS "mapOwner", m.title AS "mapTitle",m.description AS "mapDescription",m.tags AS "mapTags",
 m."coverImageUrl" AS "mapCover",m.visibility AS "mapVisibility",m."inviteCode" AS "mapCode",
 m."createdAt" AS "mapCreated",m."updatedAt" AS "mapUpdated",
 (SELECT count(*) FROM place_reviews r WHERE r."placeId"=p.id) AS "reviewCount",
 coalesce((SELECT avg(r.rating) FROM place_reviews r WHERE r."placeId"=p.id),0)::float8 AS "averageRating"
 FROM places p JOIN users u ON u.id=p."createdById" JOIN maps m ON m.id=p."mapId"
 """;

  public PlaceResponses.Page list(String mapId, String userId, PlaceListing input) {
    var map = maps.findById(mapId).orElseThrow(() -> ApiProblem.notFound("지도방을 찾을 수 없습니다."));
    permissions.requireReadable(mapId, map.getOwner().getId(), map.getVisibility().name(), userId);
    String order =
        switch (input.sort()) {
          case "name" -> "name ASC,id DESC";
          case "reviews" -> "\"reviewCount\" DESC,\"createdAt\" DESC,id DESC";
          default -> "\"createdAt\" DESC,id DESC";
        };
    String sql =
        "WITH data AS ("
            + PROJECTION
            + "), ranked AS (SELECT data.*,row_number() OVER(ORDER BY "
            + order
            + ") AS position FROM data) SELECT * FROM ranked WHERE \"mapId\"=:mapId";
    var params = new HashMap<String, Object>();
    params.put("mapId", mapId);
    if (input.search() != null && !input.search().isEmpty()) {
      sql +=
          " AND (name ILIKE :pattern OR address ILIKE :pattern OR \"roadAddress\" ILIKE :pattern OR array_to_string(tags,' ') ILIKE :pattern)";
      params.put("pattern", "%" + input.search() + "%");
    }
    if (input.tag() != null && !input.tag().isEmpty()) {
      sql += " AND :tag=ANY(tags)";
      params.put("tag", input.tag());
    }
    boolean cursor = input.cursor() != null && !input.cursor().isEmpty();
    if (cursor) {
      sql += " AND position >= (SELECT position FROM ranked WHERE id=:cursor)";
      params.put("cursor", input.cursor());
    }
    var q =
        em.createNativeQuery(sql + " ORDER BY position", Tuple.class)
            .setMaxResults(input.limit() + 1);
    if (cursor) q.setFirstResult(1);
    params.forEach(q::setParameter);
    var rows = rows(q, "list");
    boolean more = rows.size() > input.limit();
    var items = more ? rows.subList(0, input.limit()) : rows;
    return new PlaceResponses.Page(items, more ? items.getLast().place().id() : null, more);
  }

  public PlaceResponses.Read detail(String id, String userId) {
    var t = find(id);
    permissions.requireReadable(
        PlaceResponses.text(t, "mapId"),
        PlaceResponses.text(t, "mapOwner"),
        PlaceResponses.text(t, "mapVisibility"),
        userId);
    return PlaceResponses.read(t, "detail");
  }

  public List<PlaceResponses.Read> mine(String userId) {
    return rows(
        em.createNativeQuery(
                PROJECTION
                    + " WHERE p.\"createdById\"=:userId ORDER BY p.\"createdAt\" DESC,p.id DESC",
                Tuple.class)
            .setParameter("userId", userId),
        "mine");
  }

  public PlaceResponses.Core core(String id) {
    return PlaceResponses.core(find(id));
  }

  private Tuple find(String id) {
    @SuppressWarnings("unchecked")
    List<Tuple> r =
        em.createNativeQuery(PROJECTION + " WHERE p.id=:id", Tuple.class)
            .setParameter("id", id)
            .getResultList();
    if (r.isEmpty()) throw ApiProblem.notFound("장소를 찾을 수 없습니다.");
    return r.getFirst();
  }

  private static List<PlaceResponses.Read> rows(jakarta.persistence.Query q, String shape) {
    @SuppressWarnings("unchecked")
    List<Tuple> r = q.getResultList();
    return r.stream().map(t -> PlaceResponses.read(t, shape)).toList();
  }
}
