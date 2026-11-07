package com.cms.core;

import org.springframework.context.annotation.Configuration;

/** Explicit host import keeps core independent of a host's component scan and domain. */
@Configuration(proxyBeanMethods = false)
public class CmsCoreConfiguration {}
