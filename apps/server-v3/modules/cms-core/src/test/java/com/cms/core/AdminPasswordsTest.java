package com.cms.core;

import static org.assertj.core.api.Assertions.*;

import com.cms.core.account.AdminPasswords;
import java.util.*;
import org.junit.jupiter.api.Test;

class AdminPasswordsTest {
  @Test
  void usesApprovedArgonParametersAndDoesNotTruncateAt72Bytes() {
    var encoder = AdminPasswords.encoder();
    String password = "가".repeat(80) + "A";
    String hash = encoder.encode(password);
    assertThat(hash).startsWith("{argon2id}$argon2id$v=19$m=19456,t=2,p=1$");
    assertThat(encoder.matches(password, hash)).isTrue();
    assertThat(encoder.matches("가".repeat(80) + "B", hash)).isFalse();
    assertThat(encoder.encode(password)).isNotEqualTo(hash);
  }

  @Test
  void recordsWarmHashCostWithoutPrintingPasswordsOrHashes() {
    var encoder = AdminPasswords.encoder();
    String password = UUID.randomUUID().toString();
    for (int i = 0; i < 3; i++) encoder.encode(password);
    double[] millis = new double[10];
    for (int i = 0; i < millis.length; i++) {
      long start = System.nanoTime();
      encoder.encode(password);
      millis[i] = (System.nanoTime() - start) / 1_000_000.0;
    }
    Arrays.sort(millis);
    System.out.printf(
        Locale.ROOT,
        "ARGON2_HASH_MS samples=10 warmup=3 min=%.3f median=%.3f max=%.3f java=%s os=%s%n",
        millis[0],
        (millis[4] + millis[5]) / 2,
        millis[9],
        System.getProperty("java.version"),
        System.getProperty("os.name"));
    assertThat(millis[0]).isPositive();
  }
}
