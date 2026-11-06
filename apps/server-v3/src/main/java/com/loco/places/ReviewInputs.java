package com.loco.places;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.loco.web.ApiProblem;
import java.util.ArrayList;
import java.util.Set;

public record ReviewInputs(int rating, String content) {
  public static ReviewInputs parse(JsonNode value) {
    JsonNode body = value == null ? MissingNode.getInstance() : value;
    var errors = new ArrayList<String>();
    body.fieldNames()
        .forEachRemaining(
            key -> {
              if (!Set.of("rating", "content", "__proto__", "constructor").contains(key))
                errors.add("property " + key + " should not exist");
            });
    if (body.isArray())
      for (int i = 0; i < body.size(); i++) errors.add("property " + i + " should not exist");
    var rating = body.path("rating");
    if (!rating.isNumber() || rating.doubleValue() > 5)
      errors.add("rating must not be greater than 5");
    if (!rating.isNumber() || rating.doubleValue() < 1)
      errors.add("rating must not be less than 1");
    if (!rating.isNumber()
        || !Double.isFinite(rating.doubleValue())
        || rating.doubleValue() != Math.rint(rating.doubleValue()))
      errors.add("rating must be an integer number");
    var content = body.path("content");
    if (!content.isTextual()
        || content.asText().codePointCount(0, content.asText().length()) > 1000)
      errors.add("content must be shorter than or equal to 1000 characters");
    if (!content.isTextual()) errors.add("content must be a string");
    if (!errors.isEmpty()) throw ApiProblem.invalid(errors);
    String trimmed =
        content
            .asText()
            .replaceAll(
                "^[\\t\\n\\u000B\\f\\r \\p{Zs}\\u2028\\u2029\\uFEFF]+|[\\t\\n\\u000B\\f\\r \\p{Zs}\\u2028\\u2029\\uFEFF]+$",
                "");
    if (trimmed.isEmpty()) throw ApiProblem.badRequest("리뷰 내용을 입력해주세요.");
    return new ReviewInputs(rating.intValue(), trimmed);
  }
}
