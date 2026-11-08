package com.loco.security;

import java.util.Arrays;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/** A separate local-only artifact is required before a benchmark can raise HTTP quotas. */
@Component
public final class RequestLimitPolicy {
  private final boolean performance;

  public RequestLimitPolicy(Environment environment, ResourceLoader resources) {
    var profiles = Arrays.asList(environment.getActiveProfiles());
    performance = profiles.contains("perf");
    boolean artifact = resources.getResource("classpath:META-INF/loco-perf-local-only").exists();
    if (artifact != performance
        || (performance
            && (profiles.size() != 1
                || !environment.getProperty("loco.perf.isolated", Boolean.class, false)
                || !"jdbc:postgresql://perf-db:5432/loco_perf"
                    .equals(environment.getProperty("spring.datasource.url"))
                || environment.getProperty("loco.cleanup.enabled", Boolean.class, true)
                || environment.getProperty("cms.storage.enabled", Boolean.class, false)
                || !environment.getProperty("loco.notifications.service-account", "").isBlank()))) {
      throw new IllegalStateException(
          "Local perf artifact requires only the perf profile, isolated loco_perf DB, disabled external services and cleanup; production artifacts cannot enable perf.");
    }
  }

  public int limit(int configured) {
    return performance ? 600000 : configured;
  }
}
