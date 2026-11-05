package com.loco.users;

import com.fasterxml.jackson.databind.JsonNode;
import com.loco.security.AuthenticatedApi;
import com.loco.security.JwtPrincipal;
import com.loco.web.ApiController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

@ApiController
@AuthenticatedApi
public class UserController {
  private final UserService users;
  private final com.loco.images.ProfileImageService images;

  public UserController(UserService users, com.loco.images.ProfileImageService images) {
    this.users = users;
    this.images = images;
  }

  @GetMapping("/users/me")
  public UserResponses.Me me(@AuthenticationPrincipal JwtPrincipal user) {
    return users.me(user.userId());
  }

  @PutMapping("/users/me")
  public UserResponses.Updated update(
      @AuthenticationPrincipal JwtPrincipal user, @RequestBody(required = false) JsonNode body) {
    return users.update(user.userId(), UserInputs.update(body));
  }

  @PutMapping("/users/me/fcm-token")
  public java.util.Map<String, String> fcm(
      @AuthenticationPrincipal JwtPrincipal user, @RequestBody(required = false) JsonNode body) {
    users.updateFcmToken(user.userId(), UserInputs.fcmToken(body));
    return java.util.Map.of("message", "FCM 토큰이 업데이트되었습니다.");
  }

  @DeleteMapping("/users/me")
  public java.util.Map<String, String> withdraw(@AuthenticationPrincipal JwtPrincipal user) {
    users.withdraw(user.userId());
    return java.util.Map.of("message", "회원 탈퇴가 완료되었습니다.");
  }

  @org.springframework.web.bind.annotation.PostMapping("/users/me/profile-image")
  @org.springframework.web.bind.annotation.ResponseStatus(
      org.springframework.http.HttpStatus.CREATED)
  public UserResponses.Image image(
      @AuthenticationPrincipal JwtPrincipal user, jakarta.servlet.http.HttpServletRequest request) {
    return images.update(user.userId(), com.loco.images.ImageUploadRequest.file(request));
  }
}
