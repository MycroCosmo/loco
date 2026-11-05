package com.loco.auth;

import com.loco.mail.PasswordResetMail;
import com.loco.users.Provider;
import com.loco.users.User;
import com.loco.users.UserRepository;
import com.loco.web.ApiProblem;
import java.security.SecureRandom;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class PasswordRecoveryService {
  private final UserRepository users;
  private final PasswordResetTokenRepository codes;
  private final PasswordResetTransaction transaction;
  private final V2PasswordEncoder passwords;
  private final PasswordResetMail mail;
  private final TokenIssuer clock;
  private final SecureRandom random = new SecureRandom();

  public PasswordRecoveryService(
      UserRepository users,
      PasswordResetTokenRepository codes,
      PasswordResetTransaction transaction,
      V2PasswordEncoder passwords,
      PasswordResetMail mail,
      TokenIssuer clock) {
    this.users = users;
    this.codes = codes;
    this.transaction = transaction;
    this.passwords = passwords;
    this.mail = mail;
    this.clock = clock;
  }

  public Map<String, String> forgot(String email) {
    var user = users.findByEmail(email).orElse(null);
    if (eligible(user)) {
      String code = Integer.toString(100000 + random.nextInt(900000));
      var expiry = clock.now().plusMinutes(15);
      // v2처럼 각각 커밋한다. SMTP 실패도 앞선 토큰 변경을 취소하지 않는다.
      codes.invalidate(user.getId(), clock.now());
      codes.saveAndFlush(PasswordResetToken.issue(user.getId(), TokenIssuer.hash(code), expiry));
      mail.send(email, user.getNickname(), code);
    }
    return Map.of("message", "이메일로 재설정 코드를 전송했습니다.");
  }

  public Map<String, String> reset(AuthInputs.Reset input) {
    var user = users.findByEmail(input.email()).orElse(null);
    if (!eligible(user)) throw ApiProblem.unauthorized("유효하지 않은 요청입니다.");
    var code =
        codes
            .findFirstByUserIdAndTokenHashAndUsedAtIsNullAndExpiresAtGreaterThan(
                user.getId(), TokenIssuer.hash(input.code()), clock.now())
            .orElseThrow(PasswordResetTransaction::invalidCode);
    transaction.reset(code.getId(), user.getId(), passwords.encode(input.newPassword()));
    return Map.of("message", "비밀번호가 재설정되었습니다.");
  }

  private static boolean eligible(User user) {
    return user != null && user.getDeletedAt() == null && user.getProvider() == Provider.LOCAL;
  }
}
