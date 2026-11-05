package com.loco.config;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(dateTimeProviderRef = "auditTime")
public class JpaAuditingConfig {
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  DateTimeProvider auditTime(Clock clock) {
    return () -> Optional.of(LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS));
  }
}
