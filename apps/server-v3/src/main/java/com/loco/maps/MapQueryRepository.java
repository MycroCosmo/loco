package com.loco.maps;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.util.HashMap;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class MapQueryRepository {
  private final EntityManager em;

  public MapQueryRepository(EntityManager em) {
    this.em = em;
  }

  private static final String PROJECTION =
      """
        SELECT m.*, u.nickname AS "ownerNickname", u."profileImageUrl" AS "ownerImage",
        (SELECT count(*) FROM places p WHERE p."mapId"=m.id) AS "placeCount",
        (SELECT count(*) FROM favorites f WHERE f."mapId"=m.id) AS "favoriteCount",
        (SELECT count(*) FROM map_members mm WHERE mm."mapId"=m.id) AS "memberCount"
        FROM maps m JOIN users u ON u.id=m."ownerId"
        """;

  public MapResponses.Page list(String userId, MapInputs.Listing input) {
    String order =
        switch (input.sort()) {
              case "popular" -> "\"favoriteCount\" DESC, ";
              case "places" -> "\"placeCount\" DESC, ";
              default -> "";
            }
            + "\"createdAt\" DESC, id DESC";
    String access =
        "PUBLIC".equals(input.visibility())
            ? "visibility='PUBLIC'"
            : "PRIVATE".equals(input.visibility())
                ? "visibility='PRIVATE' AND (\"ownerId\"=:userId OR EXISTS (SELECT 1 FROM map_members mm WHERE mm.\"mapId\"=r.id AND mm.\"userId\"=:userId))"
                : "(visibility='PUBLIC' OR \"ownerId\"=:userId)";
    var parameters = new HashMap<String, Object>();
    if (!"PUBLIC".equals(input.visibility())) parameters.put("userId", userId);
    String sql =
        "WITH data AS ("
            + PROJECTION
            + "), ranked AS (SELECT data.*, row_number() OVER (ORDER BY "
            + order
            + ") AS position FROM data) SELECT * FROM ranked r WHERE "
            + access;
    if (input.search() != null && !input.search().isEmpty()) {
      sql += " AND (title ILIKE :pattern OR array_to_string(tags,' ') ILIKE :pattern)";
      parameters.put("pattern", "%" + input.search() + "%");
    }
    if (input.cursor() != null && !input.cursor().isEmpty()) {
      // Prisma는 커서 행이 필터 밖이어도 조건을 만족하는 첫 행을 skip:1로 제외한다.
      sql += " AND position >= (SELECT position FROM ranked WHERE id=:cursor)";
      parameters.put("cursor", input.cursor());
    }
    var query =
        em.createNativeQuery(sql + " ORDER BY position", Tuple.class)
            .setMaxResults(input.limit() + 1);
    if (input.cursor() != null && !input.cursor().isEmpty()) query.setFirstResult(1);
    parameters.forEach(query::setParameter);
    List<MapResponses.Detail> rows = rows(query);
    boolean more = rows.size() > input.limit();
    var items = more ? rows.subList(0, input.limit()) : rows;
    return new MapResponses.Page(items, more ? items.getLast().map().id() : null, more);
  }

  public List<MapResponses.Detail> mine(String userId, String type) {
    String where =
        "joined".equals(type)
            ? "m.\"ownerId\"<>:userId AND EXISTS(SELECT 1 FROM map_members mm WHERE mm.\"mapId\"=m.id AND mm.\"userId\"=:userId)"
            : "m.\"ownerId\"=:userId";
    return rows(
        em.createNativeQuery(
                PROJECTION + " WHERE " + where + " ORDER BY m.\"createdAt\" DESC,m.id DESC",
                Tuple.class)
            .setParameter("userId", userId));
  }

  public java.util.Optional<MapResponses.Detail> detail(String id) {
    return rows(
            em.createNativeQuery(PROJECTION + " WHERE m.id=:id", Tuple.class)
                .setParameter("id", id))
        .stream()
        .findFirst();
  }

  private static List<MapResponses.Detail> rows(jakarta.persistence.Query query) {
    @SuppressWarnings("unchecked")
    List<Tuple> rows = query.getResultList();
    return rows.stream().map(MapResponses::detail).toList();
  }
}
