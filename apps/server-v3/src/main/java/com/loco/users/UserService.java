package com.loco.users;

import com.loco.web.ApiProblem;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
  private final UserRepository users;
  private final Clock clock;
  private final com.loco.auth.RefreshTokenRepository refreshTokens;
  private final com.loco.auth.PasswordResetTokenRepository resetTokens;

  public UserService(
      UserRepository users,
      Clock clock,
      com.loco.auth.RefreshTokenRepository refreshTokens,
      com.loco.auth.PasswordResetTokenRepository resetTokens) {
    this.users = users;
    this.clock = clock;
    this.refreshTokens = refreshTokens;
    this.resetTokens = resetTokens;
  }

  @Transactional(readOnly = true)
  public UserResponses.Me me(String id) {
    return UserResponses.me(active(id));
  }

  @Transactional
  public UserResponses.Updated update(String id, UserInputs.Update input) {
    var user = active(id);
    user.updateProfile(input, LocalDateTime.now(clock));
    users.flush();
    return UserResponses.updated(user);
  }

  private User active(String id) {
    return users
        .findById(id)
        .filter(user -> user.getDeletedAt() == null)
        .orElseThrow(() -> ApiProblem.notFound("사용자를 찾을 수 없습니다."));
  }

  @Transactional
  public void updateFcmToken(String id, String token) {
    active(id).updateFcmToken(token, LocalDateTime.now(clock));
    users.flush();
  }

  @Transactional
  public void withdraw(String id) {
    var now = LocalDateTime.now(clock);
    active(id).withdraw(now);
    users.flush();
    refreshTokens.revokeForUser(id, now);
    resetTokens.invalidate(id, now);
  }
}
