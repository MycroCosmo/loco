package com.loco.feedback;

import com.loco.users.User;
import jakarta.persistence.EntityManager;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class FeedbackService {
  private final EntityManager em;
  private final TransactionTemplate transaction;

  public FeedbackService(EntityManager em, TransactionTemplate transaction) {
    this.em = em;
    this.transaction = transaction;
  }

  public Map<String, String> submit(String user, String content) {
    var feedback =
        transaction.execute(
            status -> {
              var item = Feedback.create(em.getReference(User.class, user), content);
              em.persist(item);
              return item;
            });
    return Map.of("message", "피드백이 제출되었습니다.", "id", feedback.id());
  }
}
