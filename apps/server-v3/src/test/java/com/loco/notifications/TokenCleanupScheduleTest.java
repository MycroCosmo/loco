package com.loco.notifications;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.loco.auth.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.support.SimpleTriggerContext;

class TokenCleanupScheduleTest {
  ApplicationContextRunner context() {
    return new ApplicationContextRunner()
        .withBean(TokenCleanupService.class, () -> mock(TokenCleanupService.class))
        .withUserConfiguration(TokenCleanupSchedule.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"Asia/Seoul", "UTC"})
  void registeredTriggerUsesConfiguredZone(String zone) {
    context()
        .withPropertyValues("loco.cleanup.zone=" + zone)
        .run(
            app -> {
              var tasks =
                  app.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks();
              assertThat(tasks).hasSize(1);
              var task = (CronTask) tasks.iterator().next().getTask();
              assertThat(task.getExpression()).isEqualTo("0 0 3 * * *");
              var next =
                  task.getTrigger()
                      .nextExecution(
                          new SimpleTriggerContext(
                              Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"), ZoneOffset.UTC)));
              assertThat(next)
                  .isEqualTo(
                      Instant.parse(
                          zone.equals("UTC") ? "2026-11-06T03:00:00Z" : "2026-11-06T18:00:00Z"));
              verifyNoInteractions(app.getBean(TokenCleanupService.class));
            });
  }

  @Test
  void disabledRegistersNoScheduler() {
    context()
        .withPropertyValues("loco.cleanup.enabled=false")
        .run(
            app -> {
              assertThat(app).doesNotHaveBean(TokenCleanupSchedule.class);
              assertThat(app).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
            });
  }
}
