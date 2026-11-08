package com.cms.core.web;

import java.lang.annotation.*;
import org.springframework.web.bind.annotation.RestController;

/** Marks a host-owned CMS HTTP boundary without coupling core to its host. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@RestController
public @interface CmsController {}
