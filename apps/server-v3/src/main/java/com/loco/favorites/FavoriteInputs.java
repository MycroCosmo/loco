package com.loco.favorites;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.loco.web.ApiProblem;
import java.util.*;

final class FavoriteInputs {
  static String mapId(JsonNode input) {
    var body = input == null ? MissingNode.getInstance() : input;
    var errors = new ArrayList<String>();
    body.fieldNames()
        .forEachRemaining(
            k -> {
              if (!Set.of("mapId", "__proto__", "constructor").contains(k))
                errors.add("property " + k + " should not exist");
            });
    if (body.isArray())
      for (int i = 0; i < body.size(); i++) errors.add("property " + i + " should not exist");
    if (!body.path("mapId").isTextual()) errors.add("mapId must be a string");
    if (!errors.isEmpty()) throw ApiProblem.invalid(errors);
    return body.path("mapId").asText();
  }
}
