package com.loco.auth;

import com.loco.users.User;
import com.loco.users.UserRepository;
import com.loco.web.ApiProblem;
import org.springframework.stereotype.Service;

@Service
public class OAuthLoginService {
  private final OAuthProfiles profiles;
  private final UserRepository users;
  private final TokenIssuer tokens;

  public OAuthLoginService(OAuthProfiles profiles, UserRepository users, TokenIssuer tokens) {
    this.profiles = profiles;
    this.users = users;
    this.tokens = tokens;
  }

  public TokenIssuer.Pair login(String provider, AuthInputs.OAuth input) {
    var profile = profiles.fetch(provider, input);
    var user =
        users.findFirstByProviderAndProviderId(profile.provider(), profile.id()).orElse(null);
    if (user == null) {
      if (users.findByEmail(profile.email()).isPresent()) {
        throw ApiProblem.conflict("이미 가입된 이메일입니다. 기존 계정으로 로그인한 뒤 계정을 연결해주세요.");
      }
      user =
          User.oauth(
              profile.provider(),
              profile.id(),
              profile.email(),
              profile.nickname(),
              profile.image());
    } else {
      user.updateOAuth(profile.email(), profile.nickname(), profile.image(), tokens.now());
    }
    user = users.saveAndFlush(user);
    // v2는 프로필 변경을 커밋한 뒤 탈퇴 상태를 검사한다.
    if (user.getDeletedAt() != null) throw ApiProblem.unauthorized("탈퇴한 계정입니다.");
    return tokens.issue(user);
  }
}
