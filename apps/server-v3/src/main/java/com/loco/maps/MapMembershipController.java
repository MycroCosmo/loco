package com.loco.maps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.loco.security.AuthenticatedApi;
import com.loco.security.JwtPrincipal;
import com.loco.web.ApiController;
import com.loco.web.ApiProblem;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@ApiController
@AuthenticatedApi
public class MapMembershipController {
  private final MapMembershipService members;

  public MapMembershipController(MapMembershipService members) {
    this.members = members;
  }

  @GetMapping("/maps/{id}/membership")
  public Map<String, Boolean> membership(
      @AuthenticationPrincipal JwtPrincipal user, @PathVariable String id) {
    return Map.of("isMember", members.membership(id, user.userId()));
  }

  @PostMapping("/maps/{id}/join")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Boolean> join(
      @AuthenticationPrincipal JwtPrincipal user, @PathVariable String id) {
    members.join(id, user.userId());
    return Map.of("isMember", true);
  }

  @PostMapping("/maps/join-by-code")
  @ResponseStatus(HttpStatus.CREATED)
  public MapResponses.Detail code(
      @AuthenticationPrincipal JwtPrincipal user, @RequestBody(required = false) JsonNode value) {
    JsonNode body = value == null ? MissingNode.getInstance() : value;
    var errors = new ArrayList<String>();
    body.fieldNames()
        .forEachRemaining(
            key -> {
              if (!Set.of("inviteCode", "__proto__", "constructor").contains(key))
                errors.add("property " + key + " should not exist");
            });
    if (body.isArray())
      for (int i = 0; i < body.size(); i++) errors.add("property " + i + " should not exist");
    var code = body.path("inviteCode");
    if (code.isMissingNode() || code.isNull() || code.isTextual() && code.asText().isEmpty())
      errors.add("inviteCode should not be empty");
    if (!code.isTextual()) errors.add("inviteCode must be a string");
    if (!errors.isEmpty()) throw ApiProblem.invalid(errors);
    return members.joinByCode(code.asText(), user.userId());
  }

  @PostMapping("/maps/{id}/leave")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, String> leave(
      @AuthenticationPrincipal JwtPrincipal user, @PathVariable String id) {
    members.leave(id, user.userId());
    return Map.of("message", "지도방에서 나갔습니다.");
  }

  @GetMapping("/maps/{id}/members")
  public java.util.List<MapMembershipService.Member> list(
      @AuthenticationPrincipal JwtPrincipal user, @PathVariable String id) {
    return members.members(id, user.userId());
  }

  @DeleteMapping("/maps/{id}/members/{target}")
  public Map<String, String> remove(
      @AuthenticationPrincipal JwtPrincipal user,
      @PathVariable String id,
      @PathVariable String target) {
    members.remove(id, user.userId(), target);
    return Map.of("message", "멤버를 내보냈습니다.");
  }
}
