package com.loco.cmsadmin;

import com.cms.core.account.*;
import com.cms.core.history.HistoryWriter;
import com.cms.core.permission.*;
import com.loco.cmsadmin.ReportProcessing.Status;
import jakarta.persistence.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReportProcessingService {
  public record Command(Status expectedStatus, Status nextStatus, String note) {}

  public record View(Status status, String note, String handledBy, Instant handledAt) {
    Map<String, Object> history() {
      var result = new LinkedHashMap<String, Object>();
      result.put("status", status.name());
      result.put("hasNote", note != null);
      result.put("handledBy", handledBy);
      result.put("handledAt", handledAt == null ? null : handledAt.toString());
      return result;
    }
  }

  private final EntityManager em;
  private final PermissionService access;
  private final TransactionTemplate transaction;
  private final HistoryWriter history;
  private final Clock clock;

  public ReportProcessingService(
      EntityManager em,
      PermissionService access,
      TransactionTemplate transaction,
      HistoryWriter history,
      Clock clock) {
    this.em = em;
    this.access = access;
    this.transaction = transaction;
    this.history = history;
    this.clock = clock;
  }

  public View transition(AdminIdentity actor, String id, Command input) {
    access.require(actor, "loco.reports", Action.MANAGE);
    LocoReadFilter.uuid(id);
    if (input == null || input.expectedStatus() == null || input.nextStatus() == null)
      throw new AdminProblem(400, "Invalid report processing input");
    String note = input.note() == null ? null : input.note().strip();
    if (note != null && note.isEmpty()) note = null;
    if (note != null && note.codePointCount(0, note.length()) > 1000)
      throw new AdminProblem(400, "Processing note must be at most 1000 characters");
    if ((input.nextStatus() == Status.RESOLVED || input.nextStatus() == Status.DISMISSED)
        && note == null) throw new AdminProblem(400, "A closing note is required");
    String finalNote = note;
    return transaction.execute(
        tx -> {
          if (!Boolean.TRUE.equals(
              em.createNativeQuery("SELECT EXISTS(SELECT 1 FROM public.reports WHERE id=:id)")
                  .setParameter("id", id)
                  .getSingleResult())) throw new AdminProblem(404, "Not found");
          em.createNativeQuery(
                  "INSERT INTO cms.loco_report_processing(report_id,status) SELECT id,'RECEIVED' FROM public.reports WHERE id=:id ON CONFLICT(report_id) DO NOTHING")
              .setParameter("id", id)
              .executeUpdate();
          var before = current(id);
          if (before == null) throw new AdminProblem(404, "Not found");
          if (before.status() != input.expectedStatus()) throw stale();
          boolean valid =
              before.status() == Status.RECEIVED && input.nextStatus() == Status.IN_REVIEW
                  || before.status() == Status.IN_REVIEW
                      && (input.nextStatus() == Status.RESOLVED
                          || input.nextStatus() == Status.DISMISSED);
          if (!valid)
            throw new AdminProblem(
                409, "Report transition is not allowed", "INVALID_REPORT_TRANSITION");
          var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
          int changed =
              em.createNativeQuery(
                      "UPDATE cms.loco_report_processing SET status=:next,note=:note,handled_by=:actor,handled_at=:now WHERE report_id=:id AND status=:expected")
                  .setParameter("next", input.nextStatus().name())
                  .setParameter("note", finalNote)
                  .setParameter("actor", actor.id())
                  .setParameter("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                  .setParameter("id", id)
                  .setParameter("expected", input.expectedStatus().name())
                  .executeUpdate();
          if (changed != 1) throw stale();
          var after = new View(input.nextStatus(), finalNote, actor.id(), now);
          history.record(
              actor.id(), "TRANSITION", "loco.report", id, before.history(), after.history());
          return after;
        });
  }

  private AdminProblem stale() {
    return new AdminProblem(
        409, "Report state changed; reload before retrying", "STALE_REPORT_STATE");
  }

  private View current(String id) {
    var rows =
        em.createNativeQuery(
                "SELECT status,note,handled_by,handled_at FROM cms.loco_report_processing WHERE report_id=:id",
                Tuple.class)
            .setParameter("id", id)
            .getResultList();
    if (rows.isEmpty()) return null;
    var row = (Tuple) rows.getFirst();
    Object time = row.get(3);
    var at =
        time == null
            ? null
            : time instanceof LocalDateTime t ? t : ((java.sql.Timestamp) time).toLocalDateTime();
    return new View(
        Status.valueOf((String) row.get(0)),
        (String) row.get(1),
        (String) row.get(2),
        at == null ? null : at.toInstant(ZoneOffset.UTC));
  }
}
