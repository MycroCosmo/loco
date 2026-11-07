package com.cms.core;

import com.cms.core.account.AdminService;
import com.cms.core.history.HistoryWriter;
import com.cms.core.web.AdminWebConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Explicit host import keeps core independent of a host's component scan and domain. */
@Configuration(proxyBeanMethods = false)
@EntityScan(basePackageClasses = CmsCoreConfiguration.class)
@EnableJpaRepositories(basePackageClasses = CmsCoreConfiguration.class)
@Import({
  CoreAuditingConfiguration.class,
  AdminService.class,
  HistoryWriter.class,
  AdminWebConfiguration.class
})
public class CmsCoreConfiguration {}
