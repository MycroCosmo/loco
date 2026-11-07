package com.loco.auth;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class V2PasswordEncoder implements PasswordEncoder {
  private final SecureRandom random = new SecureRandom();

  @Override
  public String encode(CharSequence rawPassword) {
    byte[] utf8 = rawPassword.toString().getBytes(StandardCharsets.UTF_8);
    // Node bcrypt 5는 UTF-8 첫 72바이트를 사용한다. 잘린 바이트를 문자열로 다시 변환하지 않는다.
    byte[] input = Arrays.copyOf(utf8, Math.min(utf8.length, 72));
    try {
      return BCrypt.hashpw(input, BCrypt.gensalt("$2b", 10, random));
    } finally {
      Arrays.fill(utf8, (byte) 0);
      Arrays.fill(input, (byte) 0);
    }
  }

  @Override
  public boolean matches(CharSequence rawPassword, String encodedPassword) {
    if (encodedPassword == null) return false;
    try {
      return BCrypt.checkpw(rawPassword.toString(), encodedPassword);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }
}
