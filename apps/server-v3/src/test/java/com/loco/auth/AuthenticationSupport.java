package com.loco.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.loco.compat.ComparisonStep;
import com.loco.compat.CompatibilityTestBase;
import com.loco.compat.DatabaseSnapshot;
import com.loco.compat.ScenarioValues;
import com.loco.compat.SharedHarness;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpMethod;

abstract class AuthenticationSupport extends CompatibilityTestBase {
  static ComparisonStep.Builder issue(String action, Object body) {
    return ComparisonStep.named(action)
        .method(HttpMethod.POST)
        .path("/api/auth/" + action)
        .body(v -> body)
        .expectedStatus(201)
        .valueException("/accessToken", "서버별 회원 ID·발급 시각; 별도 서명·claim 검증")
        .valueException("/refreshToken", "무작위 토큰; DB 해시·만료 검증")
        .capture(action + "Access", "/accessToken")
        .capture(action + "Refresh", "/refreshToken");
  }

  static DatabaseSnapshot.Rules tokenRules(ScenarioValues values, Map<String, String> passwords) {
    var names = values.names().stream().filter(n -> n.endsWith("Refresh")).sorted().toList();
    if (names.isEmpty()) return new DatabaseSnapshot.Rules(Map.of(), Map.of(), passwords);
    StringBuilder role = new StringBuilder("CASE");
    for (String name : names) {
      role.append(" WHEN \"tokenHash\"='")
          .append(TokenIssuer.hash(values.text(name)))
          .append("' THEN '")
          .append(name.replace("'", "''"))
          .append("'");
    }
    role.append(" ELSE 'untracked' END");
    return new DatabaseSnapshot.Rules(
        Map.of("refresh_tokens", List.of("__sort_role")),
        Map.of(
            "refresh_tokens", "SELECT t.*, " + role + " AS \"__sort_role\" FROM refresh_tokens t"),
        passwords);
  }

  void assertPair(SharedHarness.Side side, ScenarioValues values, String prefix, String email)
      throws Exception {
    var jwt = SignedJWT.parse(values.text(prefix + "Access"));
    assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("HS256");
    assertThat(jwt.verify(new MACVerifier(environment().secret.getBytes(StandardCharsets.UTF_8))))
        .isTrue();
    var claims = jwt.getJWTClaimsSet();
    assertThat(claims.getClaims().keySet()).containsExactlyInAnyOrder("sub", "email", "iat", "exp");
    assertThat(claims.getStringClaim("email")).isEqualTo(email);
    assertThat(claims.getExpirationTime().getTime() - claims.getIssueTime().getTime())
        .isEqualTo(604800000L);
    assertThat(claims.getIssueTime().toInstant())
        .isBetween(Instant.now().minusSeconds(180), Instant.now().plusSeconds(1));
    String raw = values.text(prefix + "Refresh");
    assertThat(raw.matches("[A-Za-z0-9_-]{64}")).isTrue();
    try (var connection = environment().connect(side);
        var statement =
            connection.prepareStatement(
                """
                     SELECT u.id, t."expiresAt", t."createdAt" FROM refresh_tokens t
                     JOIN users u ON u.id=t."userId" WHERE u.email=? AND t."tokenHash"=?
                     """)) {
      statement.setString(1, email);
      statement.setString(2, TokenIssuer.hash(raw));
      try (var row = statement.executeQuery()) {
        assertThat(row.next()).isTrue();
        assertThat(claims.getSubject()).isEqualTo(row.getString(1));
        long lifetime =
            java.time.Duration.between(
                    row.getTimestamp(3).toLocalDateTime(), row.getTimestamp(2).toLocalDateTime())
                .toSeconds();
        assertThat(lifetime).isBetween(2591990L, 2592001L);
        assertThat(row.next()).isFalse();
      }
    }
  }
}
