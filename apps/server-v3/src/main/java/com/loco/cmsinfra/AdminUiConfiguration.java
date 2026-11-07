package com.loco.cmsinfra;

import com.cms.core.web.AdminPublicResources;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.web.HttpRequestHandler;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

@Configuration(proxyBeanMethods = false)
public class AdminUiConfiguration {
  public static final Set<String> PAGES =
      Set.of(
          "/admin/",
          "/admin/login",
          "/admin/password",
          "/admin/accounts",
          "/admin/roles",
          "/admin/menus",
          "/admin/access",
          "/admin/history",
          "/admin/files");

  @Bean
  AdminPublicResources adminUiPublicResources() {
    return request -> {
      String path = request.getRequestURI().substring(request.getContextPath().length());
      return PAGES.contains(path) || path.matches("/admin/assets/[A-Za-z0-9_-]+\\.(js|css)");
    };
  }

  @Bean
  ResourceHttpRequestHandler adminUiAssets() {
    var handler =
        new ResourceHttpRequestHandler() {
          @Override
          public void handleRequest(
              jakarta.servlet.http.HttpServletRequest request,
              jakarta.servlet.http.HttpServletResponse response)
              throws java.io.IOException, jakarta.servlet.ServletException {
            if (getResource(request) == null) {
              response.setStatus(404);
              return;
            }
            super.handleRequest(request, response);
          }
        };
    handler.setLocations(List.of(new ClassPathResource("admin-ui/assets/")));
    handler.setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
    return handler;
  }

  @Bean
  SimpleUrlHandlerMapping adminUiMappings(ResourceHttpRequestHandler adminUiAssets) {
    HttpRequestHandler shell =
        (request, response) -> {
          var index = new ClassPathResource("admin-ui/index.html");
          if (!index.exists()) {
            response.setStatus(404);
            return;
          }
          response.setContentType("text/html;charset=" + StandardCharsets.UTF_8.name());
          response.setHeader("Cache-Control", "no-cache");
          if (request.getMethod().equals("GET")) {
            try (var input = index.getInputStream()) {
              input.transferTo(response.getOutputStream());
            }
          } else if (!request.getMethod().equals("HEAD")) response.setStatus(405);
        };
    var paths = new HashMap<String, Object>();
    PAGES.forEach(path -> paths.put(path, shell));
    paths.put("/admin/assets/**", (HttpRequestHandler) adminUiAssets::handleRequest);
    // A missing API stays JSON and never falls back to the SPA or LOCO's error controller.
    paths.put(
        "/admin/**",
        (HttpRequestHandler)
            (request, response) -> {
              response.setStatus(404);
              response.setContentType("application/json");
              response
                  .getWriter()
                  .write("{\"statusCode\":404,\"message\":\"Not found\",\"code\":\"NOT_FOUND\"}");
            });
    return new SimpleUrlHandlerMapping(paths, Integer.MAX_VALUE - 2);
  }
}
