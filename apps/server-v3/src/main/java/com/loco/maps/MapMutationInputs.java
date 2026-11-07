package com.loco.maps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.loco.web.ApiProblem;
import java.util.ArrayList;
import java.util.Set;

/** HTTP의 누락과 명시적 null을 구분해 Prisma DTO의 갱신 의미를 유지한다. */
public record MapMutationInputs(
    boolean hasTitle,
    String title,
    boolean hasDescription,
    String description,
    boolean hasTags,
    String[] tags,
    boolean hasVisibility,
    Visibility visibility,
    boolean arrayData) {
  public static MapMutationInputs parse(JsonNode value, boolean create) {
    JsonNode body = value == null ? MissingNode.getInstance() : value;
    var errors = new ArrayList<String>();
    body.fieldNames()
        .forEachRemaining(
            name -> {
              if (!Set.of("title", "description", "tags", "visibility", "__proto__", "constructor")
                  .contains(name)) errors.add("property " + name + " should not exist");
            });
    if (body.isArray())
      for (int i = 0; i < body.size(); i++) errors.add("property " + i + " should not exist");
    string(errors, body.path("title"), "title", 100, !create);
    string(errors, body.path("description"), "description", 500, true);
    JsonNode tags = body.path("tags"), visibility = body.path("visibility");
    if (!tags.isMissingNode() && !tags.isNull()) {
      if (tags.isArray()
          ? java.util.stream.StreamSupport.stream(tags.spliterator(), false)
              .anyMatch(tag -> !tag.isTextual())
          : !tags.isTextual()) errors.add("each value in tags must be a string");
      if (!tags.isArray()) errors.add("tags must be an array");
    }
    if (create || (!visibility.isMissingNode() && !visibility.isNull())) {
      if (!visibility.isTextual() || !Set.of("PUBLIC", "PRIVATE").contains(visibility.asText()))
        errors.add("visibility must be one of the following values: PUBLIC, PRIVATE");
    }
    if (!errors.isEmpty()) throw ApiProblem.invalid(errors);
    String[] tagValues =
        tags.isArray()
            ? java.util.stream.StreamSupport.stream(tags.spliterator(), false)
                .map(JsonNode::textValue)
                .toArray(String[]::new)
            : null;
    return new MapMutationInputs(
        body.has("title"),
        body.path("title").textValue(),
        body.has("description"),
        body.path("description").textValue(),
        body.has("tags"),
        tagValues,
        body.has("visibility"),
        visibility.isTextual() ? Visibility.valueOf(visibility.asText()) : null,
        body.isArray());
  }

  private static void string(
      ArrayList<String> errors, JsonNode value, String key, int max, boolean optional) {
    if (optional && (value.isMissingNode() || value.isNull())) return;
    if (!value.isTextual() || value.asText().codePointCount(0, value.asText().length()) > max)
      errors.add(key + " must be shorter than or equal to " + max + " characters");
    if (!value.isTextual()) errors.add(key + " must be a string");
  }
}
