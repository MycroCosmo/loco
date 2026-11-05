package com.loco.users;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.loco.auth.V2Url;
import com.loco.web.ApiProblem;
import java.util.ArrayList;
import java.util.Set;

public final class UserInputs {
  private UserInputs() {}

  public record Update(boolean hasNickname, String nickname, boolean hasImage, String image) {}

  public static String fcmToken(JsonNode body) {
    JsonNode token = body == null ? MissingNode.getInstance() : body.path("fcmToken");
    if (token.isObject() && token.size() == 1 && token.has("set")) token = token.get("set");
    if (token.isMissingNode() || token.isNull()) return null;
    if (token.isTextual()) return token.textValue();
    throw new IllegalArgumentException("Invalid Prisma-compatible FCM value");
  }

  public static Update update(JsonNode value) {
    JsonNode body = value == null ? MissingNode.getInstance() : value;
    var errors = new ArrayList<String>();
    body.fieldNames()
        .forEachRemaining(
            name -> {
              if (!Set.of("nickname", "profileImageUrl", "__proto__", "constructor").contains(name))
                errors.add("property " + name + " should not exist");
            });
    if (body.isArray())
      for (int i = 0; i < body.size(); i++) errors.add("property " + i + " should not exist");
    var nickname = body.path("nickname");
    if (!nickname.isMissingNode() && !nickname.isNull()) {
      if (!nickname.isTextual()
          || nickname.asText().codePointCount(0, nickname.asText().length()) > 30)
        errors.add("nickname must be shorter than or equal to 30 characters");
      if (!nickname.isTextual()) errors.add("nickname must be a string");
    }
    var image = body.path("profileImageUrl");
    if (!image.isMissingNode()
        && !image.isNull()
        && (!image.isTextual() || !V2Url.validProfileUrl(image.asText())))
      errors.add("profileImageUrl must be a URL address");
    if (!errors.isEmpty()) throw ApiProblem.invalid(errors);
    // 빈 배열은 v2 DTO 검증을 통과하지만 Prisma update의 data로는 허용되지 않는다.
    if (body.isArray()) throw new IllegalArgumentException("Array update data");
    return new Update(
        body.has("nickname"),
        nickname.isNull() ? null : nickname.textValue(),
        body.has("profileImageUrl"),
        image.isNull() ? null : image.textValue());
  }
}
