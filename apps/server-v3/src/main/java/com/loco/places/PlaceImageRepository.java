package com.loco.places;

import com.loco.web.ApiProblem;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class PlaceImageRepository {
  private final EntityManager em;

  public PlaceImageRepository(EntityManager em) {
    this.em = em;
  }

  public record State(String authorId, String[] urls) {}

  public State find(String id) {
    @SuppressWarnings("unchecked")
    List<Tuple> rows =
        em.createNativeQuery(
                "SELECT \"createdById\",\"imageUrls\" FROM places WHERE id=:id", Tuple.class)
            .setParameter("id", id)
            .getResultList();
    if (rows.isEmpty()) throw ApiProblem.notFound("장소를 찾을 수 없습니다.");
    var row = rows.getFirst();
    return new State(
        PlaceResponses.text(row, "createdById"), PlaceResponses.array(row, "imageUrls"));
  }

  public int append(String id, String user, String url, LocalDateTime now) {
    return em.createNativeQuery(
            """
        UPDATE places SET "imageUrls"=array_append("imageUrls",:url),"updatedAt"=:now
        WHERE id=:id AND "createdById"=:user AND cardinality("imageUrls")<3
        """)
        .setParameter("url", url)
        .setParameter("now", now)
        .setParameter("id", id)
        .setParameter("user", user)
        .executeUpdate();
  }
}
