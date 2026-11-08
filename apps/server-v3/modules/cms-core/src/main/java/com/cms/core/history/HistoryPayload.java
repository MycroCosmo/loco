package com.cms.core.history;

import java.util.*;
import java.util.function.Predicate;

/**
 * Only explicit, typed snapshots cross the JSONB boundary. Extend with a tested resource contract.
 */
final class HistoryPayload {
  private HistoryPayload() {}

  /** JSONB round trips enums as strings and small whole numbers as Integer. */
  static Map<String, Object> read(String resource, Map<String, Object> input) {
    if (input == null) return checked(resource, null);
    var copy = copyMap(input);
    if (resource.equals("role") && copy.get("permissions") instanceof Map<?, ?> values) {
      var converted = new LinkedHashMap<String, Object>();
      values.forEach(
          (key, value) ->
              converted.put((String) key, com.cms.core.permission.Action.valueOf((String) value)));
      copy.put("permissions", converted);
    }
    if (resource.equals("file") && copy.containsKey("byteSize"))
      copy.put("byteSize", whole(copy.get("byteSize")));
    if (resource.equals("menu")) {
      if (copy.containsKey("treeRevision"))
        copy.put("treeRevision", whole(copy.get("treeRevision")));
      if (copy.get("sortOrders") instanceof Map<?, ?> values) {
        var converted = new LinkedHashMap<String, Object>();
        values.forEach((key, value) -> converted.put((String) key, Math.toIntExact(whole(value))));
        copy.put("sortOrders", converted);
      }
      if (copy.get("node") instanceof Map<?, ?> node) {
        var converted = copyMap(node);
        if (converted.containsKey("sortOrder"))
          converted.put("sortOrder", Math.toIntExact(whole(converted.get("sortOrder"))));
        copy.put("node", converted);
      }
    }
    return checked(resource, copy);
  }

  private static long whole(Object value) {
    if (!(value instanceof Integer
        || value instanceof Long
        || value instanceof java.math.BigInteger))
      throw new IllegalArgumentException("Invalid history integer");
    return new java.math.BigInteger(value.toString()).longValueExact();
  }

  static Map<String, Object> checked(String resource, Map<String, Object> input) {
    if (resource == null || !Set.of("admin-account", "role", "menu", "file").contains(resource))
      throw new IllegalArgumentException("Unregistered history resource");
    if (input == null) return null;
    switch (resource) {
      case "file" -> {
        keys(
            input,
            Set.of("originalName", "mediaType", "byteSize", "sha256", "createdBy", "status"));
        optional(input, "originalName", v -> v instanceof String);
        optional(input, "mediaType", v -> v instanceof String);
        optional(input, "byteSize", v -> v instanceof Long n && n > 0);
        optional(input, "sha256", v -> v instanceof String s && s.matches("[a-f0-9]{64}"));
        optional(input, "createdBy", HistoryPayload::uuid);
        optional(input, "status", v -> "ACTIVE".equals(v) || "DELETE_PENDING".equals(v));
      }
      case "admin-account" -> {
        keys(input, Set.of("loginId", "displayName", "active", "mustChangePassword", "roles"));
        optional(input, "loginId", v -> v instanceof String);
        optional(input, "displayName", v -> v instanceof String);
        optional(input, "active", v -> v instanceof Boolean);
        optional(input, "mustChangePassword", v -> v instanceof Boolean);
        optional(
            input,
            "roles",
            v -> v instanceof List<?> list && list.stream().allMatch(String.class::isInstance));
      }
      case "role" -> {
        keys(input, Set.of("code", "name", "permissions"));
        optional(input, "code", v -> v instanceof String);
        optional(input, "name", v -> v instanceof String);
        optional(
            input,
            "permissions",
            v ->
                v instanceof Map<?, ?> map
                    && map.entrySet().stream()
                        .allMatch(
                            e ->
                                e.getKey() instanceof String
                                    && (e.getValue() == com.cms.core.permission.Action.READ
                                        || e.getValue() == com.cms.core.permission.Action.MANAGE)));
      }
      case "menu" -> {
        keys(input, Set.of("node", "sortOrders", "treeRevision"));
        optional(input, "treeRevision", v -> v instanceof Long);
        optional(
            input,
            "sortOrders",
            v ->
                v instanceof Map<?, ?> map
                    && map.entrySet().stream()
                        .allMatch(
                            e -> uuid(e.getKey()) && e.getValue() instanceof Integer i && i >= 0));
        optional(input, "node", HistoryPayload::node);
      }
      default -> throw new IllegalArgumentException("Unregistered history resource");
    }
    // Detach nested maps/lists before the persistence boundary; arbitrary objects are rejected
    // above.
    return copyMap(input);
  }

  private static boolean node(Object value) {
    if (!(value instanceof Map<?, ?> map)) return false;
    if (!map.keySet()
        .equals(
            Set.of(
                "id",
                "parentId",
                "sortOrder",
                "title",
                "type",
                "routeKey",
                "permissionKey",
                "visible"))) return false;
    return uuid(map.get("id"))
        && (map.get("parentId") == null || uuid(map.get("parentId")))
        && map.get("sortOrder") instanceof Integer i
        && i >= 0
        && map.get("title") instanceof String
        && Set.of("GROUP", "PAGE").contains(map.get("type"))
        && (map.get("routeKey") == null || map.get("routeKey") instanceof String)
        && (map.get("permissionKey") == null || map.get("permissionKey") instanceof String)
        && map.get("visible") instanceof Boolean;
  }

  private static boolean uuid(Object value) {
    if (!(value instanceof String text)) return false;
    try {
      return UUID.fromString(text).toString().equals(text);
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  private static void keys(Map<String, Object> input, Set<String> allowed) {
    if (!allowed.containsAll(input.keySet()))
      throw new IllegalArgumentException("Unapproved history field");
  }

  private static void optional(Map<String, Object> input, String field, Predicate<Object> valid) {
    if (input.containsKey(field) && !valid.test(input.get(field)))
      throw new IllegalArgumentException("Invalid history field type");
  }

  private static Map<String, Object> copyMap(Map<?, ?> input) {
    var result = new LinkedHashMap<String, Object>();
    input.forEach((key, value) -> result.put((String) key, copy(value)));
    return result;
  }

  private static Object copy(Object value) {
    if (value instanceof Map<?, ?> map) return copyMap(map);
    if (value instanceof List<?> list) return list.stream().map(HistoryPayload::copy).toList();
    if (value instanceof Enum<?> e) return e.name();
    return value;
  }
}
