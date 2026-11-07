package com.loco.reports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.loco.web.ApiProblem;
import java.util.*;

public record ReportInputs(
    ReportTargetType targetType, String targetId, String reason, String detail) {
  static ReportInputs parse(JsonNode input) {
    var body = input == null ? MissingNode.getInstance() : input;
    var errors = new ArrayList<String>();
    body.fieldNames()
        .forEachRemaining(
            k -> {
              if (!Set.of("targetType", "targetId", "reason", "detail", "__proto__", "constructor")
                  .contains(k)) errors.add("property " + k + " should not exist");
            });
    if (body.isArray())
      for (int i = 0; i < body.size(); i++) errors.add("property " + i + " should not exist");
    var type = body.path("targetType");
    if (!type.isTextual() || !Set.of("MAP", "PLACE", "USER").contains(type.asText()))
      errors.add("targetType must be one of the following values: MAP, PLACE, USER");
    if (!body.path("targetId").isTextual()) errors.add("targetId must be a string");
    var reason = body.path("reason");
    int length =
        reason.isTextual() ? reason.asText().codePointCount(0, reason.asText().length()) : 0;
    if (!reason.isTextual() || length > 200)
      errors.add("reason must be shorter than or equal to 200 characters");
    if (!reason.isTextual() || length < 5)
      errors.add("reason must be longer than or equal to 5 characters");
    if (!reason.isTextual()) errors.add("reason must be a string");
    var detail = body.path("detail");
    if (!detail.isMissingNode() && !detail.isNull()) {
      if (!detail.isTextual() || detail.asText().codePointCount(0, detail.asText().length()) > 1000)
        errors.add("detail must be shorter than or equal to 1000 characters");
      if (!detail.isTextual()) errors.add("detail must be a string");
    }
    if (!errors.isEmpty()) throw ApiProblem.invalid(errors);
    return new ReportInputs(
        ReportTargetType.valueOf(type.asText()),
        body.path("targetId").asText(),
        reason.asText(),
        detail.isTextual() ? detail.asText() : null);
  }
}
