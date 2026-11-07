package com.loco.reports;

import com.loco.users.User;
import jakarta.persistence.EntityManager;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReportService {
  private final EntityManager em;
  private final TransactionTemplate transaction;

  public ReportService(EntityManager em, TransactionTemplate transaction) {
    this.em = em;
    this.transaction = transaction;
  }

  public Map<String, String> submit(String user, ReportInputs input) {
    transaction.executeWithoutResult(
        status -> em.persist(Report.create(em.getReference(User.class, user), input)));
    return Map.of("message", "신고가 접수되었습니다.");
  }
}
