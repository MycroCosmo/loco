package com.loco.cmsadmin;

import com.cms.core.history.HistoryContract;
import java.util.*;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
public class ReportHistoryConfiguration {
  @Bean
  HistoryContract.Contribution reportHistory() {
    return () ->
        List.of(
            new HistoryContract(
                "loco.report",
                1,
                true,
                "loco.reports",
                Set.of("TRANSITION"),
                Map.of(
                    "status",
                        new HistoryContract.Field(
                            HistoryContract.Kind.ENUM,
                            false,
                            16,
                            Set.of("RECEIVED", "IN_REVIEW", "RESOLVED", "DISMISSED")),
                    "hasNote",
                        new HistoryContract.Field(HistoryContract.Kind.BOOLEAN, false, 1, Set.of()),
                    "handledBy",
                        new HistoryContract.Field(HistoryContract.Kind.UUID, true, 36, Set.of()),
                    "handledAt",
                        new HistoryContract.Field(
                            HistoryContract.Kind.INSTANT, true, 32, Set.of()))));
  }
}
