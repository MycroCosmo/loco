package com.cms.core.history;

import java.time.Instant;
import java.util.*;

/** Host-owned flat snapshots; core never receives a business entity or serializer. */
public record HistoryContract(
    String resourceType,
    int version,
    boolean current,
    String readPermission,
    Set<String> actions,
    Map<String, Field> fields) {
  public interface Contribution {
    Collection<HistoryContract> contracts();
  }

  public HistoryContract {
    actions = Set.copyOf(actions);
    fields = Map.copyOf(fields);
  }

  public enum Kind {
    TEXT,
    ENUM,
    UUID,
    INSTANT,
    BOOLEAN
  }

  public record Field(Kind kind, boolean nullable, int maxLength, Set<String> values) {
    public Field {
      Objects.requireNonNull(kind);
      values = Set.copyOf(values);
      if (maxLength < 1 || maxLength > 4096 || (kind == Kind.ENUM && values.isEmpty()))
        throw new IllegalArgumentException("Invalid history field contract");
    }

    public boolean accepts(Object value) {
      if (value == null) return nullable;
      if (kind == Kind.BOOLEAN) return value instanceof Boolean;
      if (!(value instanceof String s) || s.codePointCount(0, s.length()) > maxLength) return false;
      try {
        return switch (kind) {
          case TEXT -> true;
          case ENUM -> values.contains(s);
          case UUID -> java.util.UUID.fromString(s).toString().equals(s);
          case INSTANT -> s.endsWith("Z") && Instant.parse(s).getNano() % 1_000_000 == 0;
          case BOOLEAN -> false;
        };
      } catch (RuntimeException invalid) {
        return false;
      }
    }
  }
}
