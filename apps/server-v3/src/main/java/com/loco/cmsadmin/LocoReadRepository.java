package com.loco.cmsadmin;

import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class LocoReadRepository {
  record Plan(String sql, Map<String, Object> parameters) {}

  private final EntityManager em;

  public LocoReadRepository(EntityManager em) {
    this.em = em;
  }

  Plan listPlan(LocoReadResource resource, LocoReadFilter filter) {
    var clauses = new ArrayList<String>();
    var params = new HashMap<String, Object>();
    filter
        .values()
        .forEach(
            (key, value) -> {
              switch (key) {
                case "from", "until" -> {
                  clauses.add("t.\"createdAt\" " + (key.equals("from") ? ">=" : "<") + " :" + key);
                  params.put(key, LocalDateTime.ofInstant(Instant.parse(value), ZoneOffset.UTC));
                }
                case "status" -> {
                  if (!value.equals("ALL"))
                    clauses.add(
                        "t.\"deletedAt\" IS " + (value.equals("ACTIVE") ? "NULL" : "NOT NULL"));
                }
                case "search" -> {
                  String field = resource == LocoReadResource.USERS ? "nickname" : "title";
                  clauses.add("t." + field + " ILIKE :search ESCAPE '\\'");
                  params.put(
                      key,
                      "%"
                          + value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                          + "%");
                }
                case "tag" -> {
                  clauses.add(":tag = ANY(t.tags)");
                  params.put(key, value);
                }
                case "provider", "visibility", "targetType" -> {
                  clauses.add("t.\"" + key + "\"::text = :" + key);
                  params.put(key, value);
                }
                default -> {
                  clauses.add("t.\"" + key + "\" = :" + key);
                  params.put(key, value);
                }
              }
            });
    if (filter.cursor() != null) {
      clauses.add("(t.\"createdAt\", t.id) < (:cursorTime, :cursorId)");
      params.put("cursorTime", filter.cursor().time());
      params.put("cursorId", filter.cursor().id());
    }
    params.put("limit", filter.size() + 1);
    return new Plan(
        projection(resource, false)
            + (clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses))
            + " ORDER BY t.\"createdAt\" DESC,t.id DESC LIMIT :limit",
        Map.copyOf(params));
  }

  Plan detailPlan(LocoReadResource resource, String id) {
    return new Plan(projection(resource, true) + " WHERE t.id=:id", Map.of("id", id));
  }

  private String projection(LocoReadResource resource, boolean detail) {
    String columns =
        resource.columns
            + (detail && !resource.detailColumns.isEmpty() ? "," + resource.detailColumns : "");
    String fields =
        Arrays.stream(columns.split(","))
            .map(c -> "t.\"" + c + "\" AS \"" + c + "\"")
            .collect(java.util.stream.Collectors.joining(","));
    return "SELECT " + fields + " FROM public." + resource.table + " t";
  }

  List<Map<String, Object>> read(Plan plan) {
    var query = em.createNativeQuery(plan.sql(), Tuple.class);
    plan.parameters().forEach(query::setParameter);
    @SuppressWarnings("unchecked")
    List<Tuple> rows = query.getResultList();
    return rows.stream()
        .map(
            tuple -> {
              var row = new LinkedHashMap<String, Object>();
              tuple
                  .getElements()
                  .forEach(field -> row.put(field.getAlias(), wire(tuple.get(field.getAlias()))));
              if (row.containsKey("email"))
                row.put("emailMasked", mask((String) row.remove("email")));
              return (Map<String, Object>) row;
            })
        .toList();
  }

  private Object wire(Object value) {
    if (value instanceof LocalDateTime time) return time.toInstant(ZoneOffset.UTC).toString();
    if (value instanceof java.sql.Timestamp time)
      return time.toLocalDateTime().toInstant(ZoneOffset.UTC).toString();
    if (value instanceof Object[] array) return Arrays.asList(array);
    return value;
  }

  static String mask(String email) {
    if (email == null || !email.matches("[^@\\s]+@[^@\\s]+")) return "***";
    return new String(Character.toChars(email.codePointAt(0))) + "***@***";
  }
}
