package com.loco.feedback;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.loco.web.ApiProblem;
import java.util.*;

final class FeedbackInputs {
  static String content(JsonNode input) {
    var body = input == null ? MissingNode.getInstance() : input;
    var errors = new ArrayList<String>();
    body.fieldNames()
        .forEachRemaining(
            k -> {
              if (!Set.of("content", "__proto__", "constructor").contains(k))
                errors.add("property " + k + " should not exist");
            });
    if (body.isArray())
      for (int i = 0; i < body.size(); i++) errors.add("property " + i + " should not exist");
    var text = body.path("content");
    int length = text.isTextual() ? text.asText().codePointCount(0, text.asText().length()) : 0;
    if (!text.isTextual() || length > 1000)
      errors.add("content must be shorter than or equal to 1000 characters");
    if (!text.isTextual() || length < 10)
      errors.add("content must be longer than or equal to 10 characters");
    if (!text.isTextual()) errors.add("content must be a string");
    if (!errors.isEmpty()) throw ApiProblem.invalid(errors);
    return text.asText();
  }
}
