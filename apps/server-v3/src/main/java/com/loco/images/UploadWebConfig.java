package com.loco.images;

import com.loco.web.GlobalHttpFilter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.web.HttpRequestHandler;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

@Configuration(proxyBeanMethods = false)
public class UploadWebConfig {
  @Bean
  ResourceHttpRequestHandler uploadResources(
      ProfileImageStorage storage, com.fasterxml.jackson.databind.ObjectMapper json)
      throws Exception {
    var handler =
        new ResourceHttpRequestHandler() {
          @Override
          public void handleRequest(HttpServletRequest request, HttpServletResponse response)
              throws ServletException, IOException {
            var resource = getResource(request);
            if (resource == null
                || !(request.getMethod().equals("GET") || request.getMethod().equals("HEAD"))) {
              response.setStatus(404);
              response.setContentType("application/json; charset=utf-8");
              json.writeValue(
                  response.getOutputStream(),
                  com.loco.web.ApiExceptionHandler.notFoundBody(request));
              return;
            }
            if (resource != null
                && (request.getMethod().equals("GET") || request.getMethod().equals("HEAD"))) {
              request.setAttribute(GlobalHttpFilter.STREAM_RESOURCE, true);
              response.setHeader("Access-Control-Allow-Origin", "*");
              response.setHeader("Cross-Origin-Resource-Policy", "cross-origin");
              response.setHeader("Cache-Control", "public, max-age=0");
              response.setHeader("Accept-Ranges", "bytes");
            }
            super.handleRequest(request, response);
          }

          @Override
          protected MediaType getMediaType(HttpServletRequest request, Resource resource) {
            var type = super.getMediaType(request, resource);
            return type != null
                    && (type.getType().equals("text")
                        || type.getSubtype().equals("json")
                        || type.getSubtype().equals("javascript")
                        || type.getSubtype().endsWith("+xml"))
                ? new MediaType(type, StandardCharsets.UTF_8)
                : type;
          }
        };
    String uri = storage.root().toUri().toString();
    handler.setLocations(List.of(new UrlResource(uri.endsWith("/") ? uri : uri + "/")));
    handler.setCacheControl(CacheControl.maxAge(Duration.ZERO).cachePublic());
    handler.setEtagGenerator(
        resource -> {
          try {
            return "W/\""
                + Long.toHexString(resource.contentLength())
                + "-"
                + Long.toHexString(resource.lastModified())
                + "\"";
          } catch (IOException error) {
            throw new UncheckedIOException(error);
          }
        });
    return handler;
  }

  @Bean
  SimpleUrlHandlerMapping uploadMapping(ResourceHttpRequestHandler uploadResources) {
    // GlobalHttpFilter owns CORS; avoid MVC adding a second CORS policy/Vary set for resources.
    HttpRequestHandler serving = uploadResources::handleRequest;
    return new SimpleUrlHandlerMapping(Map.of("/uploads/**", serving), Integer.MAX_VALUE - 1);
  }
}
