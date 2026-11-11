package com.loco.security;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.DefaultResourceLoader;

/** Rejects a misplaced perf artifact before datasource initialization or Flyway migration. */
public final class PerfEnvironmentGuard implements EnvironmentPostProcessor, Ordered {
  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    new RequestLimitPolicy(environment, new DefaultResourceLoader());
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
