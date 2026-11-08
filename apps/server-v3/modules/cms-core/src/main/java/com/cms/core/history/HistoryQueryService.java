package com.cms.core.history;

import com.cms.core.account.*;
import com.cms.core.permission.PermissionService;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public class HistoryQueryService {
  public record Filter(
      String resourceType,
      String resourceId,
      String actorId,
      Instant from,
      Instant until,
      String cursor,
      int size) {}

  public record Page(List<ChangeHistory.View> items, String nextCursor) {}

  private final EntityManager em;
  private final PermissionService access;
  private final HistoryContractRegistry contracts;

  public HistoryQueryService(
      EntityManager em, PermissionService access, HistoryContractRegistry contracts) {
    this.em = em;
    this.access = access;
    this.contracts = contracts;
  }

  public Page list(AdminIdentity actor, Filter filter) {
    access.systemAdmin(actor);
    if (filter.size() < 1
        || filter.size() > 100
        || (filter.from() != null
            && filter.until() != null
            && !filter.from().isBefore(filter.until())))
      throw new AdminProblem(400, "Invalid history range or page size");
    var params = new LinkedHashMap<String, Object>();
    var where = new ArrayList<String>();
    equality(where, params, "resource_type", "resourceType", filter.resourceType());
    equality(where, params, "resource_id", "resourceId", filter.resourceId());
    equality(where, params, "actor_id", "actorId", filter.actorId());
    if (filter.from() != null) {
      where.add("h.occurred_at >= :from");
      params.put("from", LocalDateTime.ofInstant(filter.from(), ZoneOffset.UTC));
    }
    if (filter.until() != null) {
      where.add("h.occurred_at < :until");
      params.put("until", LocalDateTime.ofInstant(filter.until(), ZoneOffset.UTC));
    }
    if (filter.cursor() != null) {
      try {
        var parts =
            new String(Base64.getUrlDecoder().decode(filter.cursor()), StandardCharsets.UTF_8)
                .split("\\|", -1);
        if (parts.length != 2) throw new IllegalArgumentException();
        var time = LocalDateTime.parse(parts[0]);
        var id = UUID.fromString(parts[1]).toString();
        where.add("(h.occurred_at, h.id) < (:cursorTime, :cursorId)");
        params.put("cursorTime", time);
        params.put("cursorId", id);
      } catch (RuntimeException invalid) {
        throw new AdminProblem(400, "Invalid history cursor");
      }
    }
    String sql =
        "SELECT h.* FROM cms.change_history h"
            + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where))
            + " ORDER BY h.occurred_at DESC, h.id DESC";
    var query = em.createNativeQuery(sql, ChangeHistory.class).setMaxResults(filter.size() + 1);
    params.forEach(query::setParameter);
    @SuppressWarnings("unchecked")
    List<ChangeHistory> rows = query.getResultList();
    var items =
        rows.stream()
            .limit(filter.size())
            .map(ChangeHistory::view)
            .map(contracts::reading)
            .toList();
    String next = rows.size() > filter.size() ? cursor(items.getLast()) : null;
    return new Page(items, next);
  }

  public ChangeHistory.View detail(AdminIdentity actor, String id) {
    access.systemAdmin(actor);
    var history = em.find(ChangeHistory.class, id);
    if (history == null) throw new AdminProblem(404, "Not found");
    return contracts.reading(history.view());
  }

  private void equality(
      List<String> where, Map<String, Object> params, String column, String name, String value) {
    if (value == null) return;
    if (value.isBlank()) throw new AdminProblem(400, "Empty history filter");
    where.add("h." + column + " = :" + name);
    params.put(name, value);
  }

  private String cursor(ChangeHistory.View item) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            (LocalDateTime.ofInstant(item.occurredAt(), ZoneOffset.UTC) + "|" + item.id())
                .getBytes(StandardCharsets.UTF_8));
  }
}
