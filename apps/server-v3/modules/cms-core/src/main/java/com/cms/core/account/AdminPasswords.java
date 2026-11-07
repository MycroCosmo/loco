package com.cms.core.account;

import java.util.Map;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

public final class AdminPasswords {
  private AdminPasswords() {}

  public static PasswordEncoder encoder() {
    // C-10 / OWASP: memory in KiB, iterations 2, parallelism 1.
    return new DelegatingPasswordEncoder(
        "argon2id", Map.of("argon2id", new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2)));
  }
}
