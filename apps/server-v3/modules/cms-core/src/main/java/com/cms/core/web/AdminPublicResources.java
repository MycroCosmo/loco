package com.cms.core.web;

import jakarta.servlet.http.HttpServletRequest;

/** The assembly host may expose a UI shell; core never exposes API paths this way. */
@FunctionalInterface
public interface AdminPublicResources {
  boolean matches(HttpServletRequest request);

  static boolean permitted(AdminPublicResources resources, HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return (request.getMethod().equals("GET") || request.getMethod().equals("HEAD"))
        && path.startsWith("/admin/")
        && !path.equals("/admin/api")
        && !path.startsWith("/admin/api/")
        && resources.matches(request);
  }
}
