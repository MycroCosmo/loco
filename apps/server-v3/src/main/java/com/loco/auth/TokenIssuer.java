package com.loco.auth;

import com.loco.users.User;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TokenIssuer {
  private final byte[] secret;
  private final Clock clock;
  private final RefreshTokenRepository refreshTokens;
  private final SecureRandom random = new SecureRandom();

  public TokenIssuer(
      @Value("${loco.jwt.secret}") String secret,
      Clock clock,
      RefreshTokenRepository refreshTokens) {
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
    this.clock = clock;
    this.refreshTokens = refreshTokens;
  }

  public record Pair(String accessToken, String refreshToken) {}

  public Pair issue(User user) {
    String raw = newRefreshToken();
    refreshTokens.saveAndFlush(RefreshToken.issue(user.getId(), hash(raw), refreshExpiry()));
    return pair(user, raw);
  }

  public Pair pair(User user, String refreshToken) {
    return new Pair(accessToken(user), refreshToken);
  }

  public String newRefreshToken() {
    byte[] bytes = new byte[48];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public LocalDateTime now() {
    return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
  }

  public LocalDateTime refreshExpiry() {
    return now().plusDays(30);
  }

  public static String hash(String raw) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private String accessToken(User user) {
    var issued = clock.instant().truncatedTo(ChronoUnit.SECONDS);
    var claims =
        new JWTClaimsSet.Builder()
            .subject(user.getId())
            .claim("email", user.getEmail())
            .issueTime(Date.from(issued))
            .expirationTime(Date.from(issued.plus(7, ChronoUnit.DAYS)))
            .build();
    var jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(), claims);
    try {
      jwt.sign(new MACSigner(secret));
      return jwt.serialize();
    } catch (JOSEException exception) {
      throw new IllegalStateException("JWT signing failed", exception);
    }
  }
}
