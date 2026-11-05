package com.loco.places;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.loco.web.ApiProblem;
import java.util.ArrayList;
import java.util.Set;

/** 누락은 유지, 명시적 null은 Prisma에 전달되는 값으로 구분한다. */
public record PlaceInputs(JsonNode body) {
  private static final Set<String> FIELDS =
      Set.of(
          "name",
          "address",
          "roadAddress",
          "latitude",
          "longitude",
          "phone",
          "category",
          "memo",
          "tags");

  public static PlaceInputs parse(JsonNode value, boolean create) {
    JsonNode body = value == null ? MissingNode.getInstance() : value;
    var errors = new ArrayList<String>();
    body.fieldNames()
        .forEachRemaining(
            key -> {
              if (!FIELDS.contains(key) && !Set.of("__proto__", "constructor").contains(key))
                errors.add("property " + key + " should not exist");
            });
    if (body.isArray())
      for (int i = 0; i < body.size(); i++) errors.add("property " + i + " should not exist");
    string(errors, body.path("name"), "name", 100, !create);
    string(errors, body.path("address"), "address", 0, !create);
    string(errors, body.path("roadAddress"), "roadAddress", 0, true);
    coordinate(errors, body.path("latitude"), "latitude", 90, !create);
    coordinate(errors, body.path("longitude"), "longitude", 180, !create);
    string(errors, body.path("phone"), "phone", 0, true);
    string(errors, body.path("category"), "category", 0, true);
    string(errors, body.path("memo"), "memo", 500, true);
    var tags = body.path("tags");
    if (!absent(tags)) {
      if (tags.isArray()
          ? java.util.stream.StreamSupport.stream(tags.spliterator(), false)
              .anyMatch(t -> !t.isTextual())
          : !tags.isTextual()) errors.add("each value in tags must be a string");
      if (!tags.isArray()) errors.add("tags must be an array");
    }
    if (!errors.isEmpty()) throw ApiProblem.invalid(errors);
    return new PlaceInputs(body);
  }

  private static boolean absent(JsonNode v) {
    return v.isNull() || v.isMissingNode();
  }

  private static void string(
      ArrayList<String> errors, JsonNode v, String key, int max, boolean optional) {
    if (optional && absent(v)) return;
    if (max > 0 && (!v.isTextual() || v.asText().codePointCount(0, v.asText().length()) > max))
      errors.add(key + " must be shorter than or equal to " + max + " characters");
    if (!v.isTextual()) errors.add(key + " must be a string");
  }

  private static void coordinate(
      ArrayList<String> errors, JsonNode v, String key, int bound, boolean optional) {
    if (optional && absent(v)) return;
    if (!v.isNumber() || v.doubleValue() > bound)
      errors.add(key + " must not be greater than " + bound);
    if (!v.isNumber() || v.doubleValue() < -bound)
      errors.add(key + " must not be less than " + -bound);
    if (!v.isNumber() || !Double.isFinite(v.doubleValue()))
      errors.add(key + " must be a number conforming to the specified constraints");
  }

  public boolean has(String key) {
    return body.has(key);
  }

  public String text(String key) {
    return body.path(key).textValue();
  }

  public Double number(String key) {
    return absent(body.path(key)) ? null : body.path(key).doubleValue();
  }

  public String[] tags() {
    return body.path("tags").isArray()
        ? java.util.stream.StreamSupport.stream(body.get("tags").spliterator(), false)
            .map(JsonNode::textValue)
            .toArray(String[]::new)
        : null;
  }
}
