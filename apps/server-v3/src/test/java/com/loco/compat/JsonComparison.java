package com.loco.compat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class JsonComparison {
  private JsonComparison() {}

  public static void compare(
      JsonNode expected, JsonNode actual, Map<String, String> valueExceptions) {
    valueExceptions.forEach(
        (path, reason) -> {
          if (!path.startsWith("/") || reason.isBlank()) {
            throw new IllegalArgumentException("비교 예외에는 JSON 경로와 이유가 필요합니다");
          }
        });
    Set<String> used = new HashSet<>();
    visit(expected, actual, "", valueExceptions, used);
    if (!used.equals(valueExceptions.keySet())) throw new AssertionError("존재하지 않는 비교 제외 경로");
  }

  private static void visit(
      JsonNode left,
      JsonNode right,
      String path,
      Map<String, String> exceptions,
      Set<String> used) {
    if (left == null || right == null || left.getNodeType() != right.getNodeType()) {
      throw new AssertionError("JSON 타입/null 차이: " + path);
    }
    if (left.isObject()) {
      Set<String> keys = new TreeSet<>();
      left.fieldNames().forEachRemaining(keys::add);
      Set<String> actualKeys = new TreeSet<>();
      right.fieldNames().forEachRemaining(actualKeys::add);
      if (!keys.equals(actualKeys)) throw new AssertionError("JSON 키 차이: " + path);
      for (String key : keys)
        visit(
            left.get(key),
            right.get(key),
            path + "/" + key.replace("~", "~0").replace("/", "~1"),
            exceptions,
            used);
    } else if (left.isArray()) {
      if (left.size() != right.size()) throw new AssertionError("JSON 배열 길이 차이: " + path);
      for (int i = 0; i < left.size(); i++)
        visit(left.get(i), right.get(i), path + "/" + i, exceptions, used);
    } else {
      boolean ignored = false;
      for (String rule : exceptions.keySet()) {
        if (matches(rule, path)) {
          used.add(rule);
          ignored = true;
        }
      }
      if (!ignored && !left.equals(right)) throw new AssertionError("JSON 값 차이: " + path);
    }
  }

  private static boolean matches(String rule, String path) {
    String[] rules = rule.split("/", -1), parts = path.split("/", -1);
    if (rules.length != parts.length) return false;
    for (int i = 0; i < parts.length; i++) {
      if (!rules[i].equals(parts[i])
          && !(rules[i].equals("*") && parts[i].matches("0|[1-9][0-9]*"))) return false;
    }
    return true;
  }
}
