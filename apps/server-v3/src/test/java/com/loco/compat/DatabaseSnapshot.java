package com.loco.compat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

public final class DatabaseSnapshot {
  private static final ObjectMapper JSON = new ObjectMapper();

  public record Rules(
      Map<String, List<String>> orderBy,
      Map<String, String> rowQueries,
      Map<String, String> passwords) {
    public Rules {
      orderBy = Map.copyOf(orderBy);
      rowQueries = Map.copyOf(rowQueries);
      passwords = Map.copyOf(passwords);
    }

    public Rules(Map<String, List<String>> orderBy, Map<String, String> rowQueries) {
      this(orderBy, rowQueries, Map.of());
    }

    public static Rules defaults() {
      return new Rules(Map.of(), Map.of());
    }
  }

  private record Table(
      List<ObjectNode> rows,
      Set<String> ids,
      Set<String> primaryIds,
      Set<String> times,
      Set<String> hashes) {}

  private final Map<String, Table> tables;

  private DatabaseSnapshot(Map<String, Table> tables) {
    this.tables = tables;
  }

  public static List<String> tables(Connection connection) throws Exception {
    var names = new ArrayList<String>();
    try (var result =
        connection.getMetaData().getTables(null, "public", "%", new String[] {"TABLE"})) {
      while (result.next()) {
        String name = result.getString("TABLE_NAME");
        if (!name.equals("flyway_schema_history")) names.add(name);
      }
    }
    names.sort(String::compareTo);
    return names;
  }

  public static String quote(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }

  public static DatabaseSnapshot capture(Connection connection, Rules rules) throws Exception {
    Map<String, Table> result = new TreeMap<>();
    for (String name : tables(connection)) {
      Set<String> ids = new HashSet<>(),
          primary = new HashSet<>(),
          times = new HashSet<>(),
          hashes = new HashSet<>();
      try (var keys = connection.getMetaData().getImportedKeys(null, "public", name)) {
        while (keys.next()) ids.add(keys.getString("FKCOLUMN_NAME"));
      }
      try (var keys = connection.getMetaData().getPrimaryKeys(null, "public", name)) {
        while (keys.next()) {
          if (keys.getString("COLUMN_NAME").equals("id")) primary.add("id");
        }
      }
      ids.addAll(primary);
      if (name.equals("place_marker_cells")) ids.add("placeId");
      if (name.equals("refresh_tokens") || name.equals("password_reset_tokens"))
        hashes.add("tokenHash");
      List<ObjectNode> rows = new ArrayList<>();
      Set<String> requiredColumns = new HashSet<>();
      try (var columns = connection.getMetaData().getColumns(null, "public", name, "%")) {
        while (columns.next()) requiredColumns.add(columns.getString("COLUMN_NAME"));
      }
      String query = rules.rowQueries().getOrDefault(name, "SELECT * FROM " + quote(name));
      try (var statement = connection.createStatement();
          var records = statement.executeQuery(query)) {
        var metadata = records.getMetaData();
        Set<String> projectedColumns = new HashSet<>();
        for (int i = 1; i <= metadata.getColumnCount(); i++) {
          projectedColumns.add(metadata.getColumnLabel(i));
          if (metadata.getColumnType(i) == Types.TIMESTAMP) times.add(metadata.getColumnLabel(i));
        }
        if (!projectedColumns.containsAll(requiredColumns))
          throw new AssertionError("DB 비교 컬럼 누락: " + name);
        while (records.next()) {
          ObjectNode row = JSON.createObjectNode();
          for (int i = 1; i <= metadata.getColumnCount(); i++) {
            String column = metadata.getColumnLabel(i);
            Object value = records.getObject(i);
            if (value == null) row.putNull(column);
            else if (times.contains(column))
              row.put(column, records.getTimestamp(i).toLocalDateTime().toString());
            else if (metadata.getColumnType(i) == Types.ARRAY) {
              var array = records.getArray(i);
              row.set(column, JSON.valueToTree(array.getArray()));
              array.free();
            } else if (value instanceof Number || value instanceof Boolean)
              row.set(column, JSON.valueToTree(value));
            else row.put(column, records.getString(i));
          }
          rows.add(row);
        }
      }
      try (var statement = connection.createStatement();
          var count = statement.executeQuery("SELECT count(*) FROM " + quote(name))) {
        count.next();
        if (count.getLong(1) != rows.size()) throw new AssertionError("DB 비교 행 누락/중복: " + name);
      }
      result.put(name, new Table(rows, ids, primary, times, hashes));
    }
    if (!result.keySet().containsAll(rules.orderBy().keySet())
        || !result.keySet().containsAll(rules.rowQueries().keySet())) {
      throw new AssertionError("DB 규칙에 존재하지 않는 테이블");
    }
    return new DatabaseSnapshot(result);
  }

  public Set<String> seedIds() {
    Set<String> ids = new HashSet<>();
    tables
        .values()
        .forEach(
            table ->
                table
                    .rows()
                    .forEach(
                        row ->
                            table
                                .ids()
                                .forEach(
                                    column -> {
                                      var value = row.get(column);
                                      if (value != null && !value.isNull()) {
                                        validateUuid(value.asText());
                                        ids.add(value.asText());
                                      }
                                    })));
    return ids;
  }

  public Map<String, List<ObjectNode>> normalized(Set<String> seedIds, Rules rules) {
    Map<String, List<ObjectNode>> ordered = new TreeMap<>();
    tables.forEach(
        (name, table) -> {
          List<ObjectNode> rows = new ArrayList<>(table.rows());
          List<String> keys = rules.orderBy().get(name);
          if (keys == null && !rows.isEmpty()) {
            keys = new ArrayList<>();
            rows.getFirst().fieldNames().forEachRemaining(keys::add);
            keys.removeAll(table.ids());
            keys.removeAll(table.times());
            keys.removeAll(table.hashes());
            keys.sort(String::compareTo);
          }
          if (keys == null) keys = List.of();
          for (String key : keys) {
            if (table.ids().contains(key)
                || table.times().contains(key)
                || table.hashes().contains(key)
                || (!rows.isEmpty() && !rows.getFirst().has(key))) {
              throw new AssertionError("안정적이지 않거나 존재하지 않는 정렬 컬럼: " + name + "." + key);
            }
          }
          List<String> stableKeys = keys;
          rows.sort(Comparator.comparing(row -> sortKey(row, stableKeys)));
          for (int i = 1; i < rows.size(); i++) {
            if (sortKey(rows.get(i - 1), stableKeys).equals(sortKey(rows.get(i), stableKeys))) {
              throw new AssertionError("행 대응이 모호합니다. 비-ID 식별 기준을 지정하세요: " + name);
            }
          }
          ordered.put(name, rows);
        });
    Map<String, String> aliases = new LinkedHashMap<>();
    // PK를 먼저 대응시켜 외래 키가 바뀌었을 때 별칭까지 함께 바뀌어 오류가 가려지지 않게 한다.
    ordered.forEach(
        (name, rows) ->
            rows.forEach(
                row ->
                    tables.get(name).primaryIds().stream()
                        .sorted()
                        .forEach(column -> alias(row.path(column), seedIds, aliases))));
    Map<String, List<ObjectNode>> normalized = new TreeMap<>();
    ordered.forEach(
        (name, rows) -> {
          var table = tables.get(name);
          var output = new ArrayList<ObjectNode>();
          for (var original : rows) {
            ObjectNode row = original.deepCopy();
            var columns = new ArrayList<String>();
            row.fieldNames().forEachRemaining(columns::add);
            columns.sort(String::compareTo);
            for (String column : columns) {
              JsonNode value = row.get(column);
              if (column.startsWith("__sort_")) {
                row.remove(column);
                continue;
              }
              if (name.equals("users")
                  && column.equals("password")
                  && rules.passwords().containsKey(row.path("email").asText())) {
                String hash = value.asText("");
                if (!hash.matches("\\$2b\\$10\\$[./A-Za-z0-9]{53}")
                    || !org.springframework.security.crypto.bcrypt.BCrypt.checkpw(
                        rules.passwords().get(row.path("email").asText()), hash)) {
                  throw new AssertionError("bcrypt 형식·cost·비밀번호 의미 검증 실패");
                }
                row.put(column, "$bcrypt-2b-10");
                continue;
              }
              if (value.isNull()) continue;
              // 신고 대상은 FK가 아닌 임의 TEXT다. 실제 생성 PK와 연결된 값만 별칭화한다.
              if (name.equals("reports") && column.equals("targetId")) {
                if (aliases.containsKey(value.asText()))
                  row.put(column, aliases.get(value.asText()));
                continue;
              }
              if (table.ids().contains(column)) row.put(column, alias(value, seedIds, aliases));
              else if (table.times().contains(column)) {
                LocalDateTime.parse(value.asText());
                row.put(column, "$timestamp");
              } else if (table.hashes().contains(column)) {
                if (!value.asText().matches("[0-9a-f]{64}"))
                  throw new AssertionError("잘못된 토큰 해시 형식: " + name);
                row.put(column, "$sha256");
              }
            }
            output.add(row);
          }
          normalized.put(name, output);
        });
    return normalized;
  }

  public static void compare(
      DatabaseSnapshot left, DatabaseSnapshot right, Set<String> seedIds, Rules rules) {
    compare(left, right, seedIds, rules, rules);
  }

  public static void compare(
      DatabaseSnapshot left,
      DatabaseSnapshot right,
      Set<String> seedIds,
      Rules leftRules,
      Rules rightRules) {
    if (!left.normalized(seedIds, leftRules).equals(right.normalized(seedIds, rightRules))) {
      throw new AssertionError("업무 DB 상태 차이 (값·토큰·해시는 로그에 출력하지 않음)");
    }
  }

  public void assertUnchanged(DatabaseSnapshot after) {
    assertUnchangedExcept(after, Set.of());
  }

  public void assertUnchangedExcept(DatabaseSnapshot after, Set<String> separatelyComparedTables) {
    if (!tables.keySet().containsAll(separatelyComparedTables)
        || !after.tables.keySet().equals(tables.keySet()))
      throw new AssertionError("DB 비교 테이블 목록 불일치");
    var beforeRows = rawRows();
    var afterRows = after.rawRows();
    separatelyComparedTables.forEach(
        name -> {
          beforeRows.remove(name);
          afterRows.remove(name);
        });
    if (!beforeRows.equals(afterRows)) throw new AssertionError("변경하지 않아야 하는 DB가 변경됨");
  }

  private Map<String, List<String>> rawRows() {
    Map<String, List<String>> result = new TreeMap<>();
    tables.forEach(
        (name, table) ->
            result.put(name, table.rows().stream().map(ObjectNode::toString).sorted().toList()));
    return result;
  }

  private static String alias(JsonNode value, Set<String> seedIds, Map<String, String> aliases) {
    if (value.isNull() || value.isMissingNode()) throw new AssertionError("UUID 누락");
    String id = value.asText();
    validateUuid(id);
    return seedIds.contains(id)
        ? id
        : aliases.computeIfAbsent(id, ignored -> "$id" + (aliases.size() + 1));
  }

  private static void validateUuid(String value) {
    try {
      if (!UUID.fromString(value).toString().equalsIgnoreCase(value))
        throw new IllegalArgumentException();
    } catch (IllegalArgumentException exception) {
      throw new AssertionError("잘못된 UUID 형식");
    }
  }

  private static String sortKey(ObjectNode row, List<String> keys) {
    var values = JSON.createArrayNode();
    keys.forEach(key -> values.add(row.get(key)));
    return values.toString();
  }
}
