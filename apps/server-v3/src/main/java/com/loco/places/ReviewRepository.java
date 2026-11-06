package com.loco.places;

import static com.loco.places.PlaceResponses.*;

import com.loco.web.ApiProblem;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class ReviewRepository {
  private final EntityManager em;

  public ReviewRepository(EntityManager em) {
    this.em = em;
  }

  public record Access(String mapId, String ownerId, String visibility) {}

  public Access access(String placeId) {
    var rows =
        tuples(
            em.createNativeQuery(
                    """
        SELECT p."mapId",m."ownerId",m.visibility FROM places p JOIN maps m ON m.id=p."mapId" WHERE p.id=:id
        """,
                    Tuple.class)
                .setParameter("id", placeId));
    if (rows.isEmpty()) throw ApiProblem.notFound("장소를 찾을 수 없습니다.");
    var t = rows.getFirst();
    return new Access(text(t, "mapId"), text(t, "ownerId"), text(t, "visibility"));
  }

  private static final String REVIEW =
      """
      r.id AS "reviewId", r."placeId", r."userId", r.rating,r.content,
      r."createdAt" AS "reviewCreated",r."updatedAt" AS "reviewUpdated"
      """;

  public ReviewResponses.Summary summary(String placeId) {
    var stats =
        (Tuple)
            em.createNativeQuery(
                    """
        SELECT coalesce(avg(rating),0)::float8 AS average,count(*) AS count FROM place_reviews WHERE "placeId"=:id
        """,
                    Tuple.class)
                .setParameter("id", placeId)
                .getSingleResult();
    // v2도 집계와 목록을 별도 SELECT로 읽는다.
    var reviews =
        tuples(
                em.createNativeQuery(
                        "SELECT "
                            + REVIEW
                            + """
        ,u.nickname,u."profileImageUrl" FROM place_reviews r JOIN users u ON u.id=r."userId"
        WHERE r."placeId"=:id ORDER BY r."updatedAt" DESC
        """,
                        Tuple.class)
                    .setParameter("id", placeId))
            .stream()
            .map(ReviewResponses::read)
            .toList();
    return new ReviewResponses.Summary(
        jsonNumber(number(stats, "average")), number(stats, "count").longValue(), reviews);
  }

  public List<ReviewResponses.Mine> mine(String userId) {
    return tuples(
            em.createNativeQuery(
                    "SELECT "
                        + REVIEW
                        + """
        ,p.*,u.nickname AS "authorNickname",u."profileImageUrl" AS "authorImage",m.title AS "mapTitle"
        FROM place_reviews r JOIN places p ON p.id=r."placeId" JOIN users u ON u.id=p."createdById"
        JOIN maps m ON m.id=p."mapId" WHERE r."userId"=:id ORDER BY r."updatedAt" DESC,r.id DESC
        """,
                    Tuple.class)
                .setParameter("id", userId))
        .stream()
        .map(ReviewResponses::mine)
        .toList();
  }

  @SuppressWarnings("unchecked")
  private static List<Tuple> tuples(jakarta.persistence.Query q) {
    return q.getResultList();
  }

  public void upsert(
      String placeId, String userId, ReviewInputs input, java.time.LocalDateTime now) {
    em.createNativeQuery(
            """
        INSERT INTO place_reviews(id,"placeId","userId",rating,content,"createdAt","updatedAt")
        VALUES (:id,:place,:user,:rating,:content,:now,:now)
        ON CONFLICT ("placeId","userId") DO UPDATE SET rating=EXCLUDED.rating,content=EXCLUDED.content,"updatedAt"=EXCLUDED."updatedAt"
        """)
        .setParameter("id", java.util.UUID.randomUUID().toString())
        .setParameter("place", placeId)
        .setParameter("user", userId)
        .setParameter("rating", input.rating())
        .setParameter("content", input.content())
        .setParameter("now", now)
        .executeUpdate();
  }

  public void delete(String placeId, String userId) {
    em.createNativeQuery("DELETE FROM place_reviews WHERE \"placeId\"=:place AND \"userId\"=:user")
        .setParameter("place", placeId)
        .setParameter("user", userId)
        .executeUpdate();
  }
}
