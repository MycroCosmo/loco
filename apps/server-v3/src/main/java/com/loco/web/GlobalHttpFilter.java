package com.loco.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class GlobalHttpFilter extends OncePerRequestFilter {
  public static final String STREAM_RESOURCE = GlobalHttpFilter.class.getName() + ".stream";
  private static final Map<String, String> HELMET =
      Map.ofEntries(
          Map.entry(
              "Content-Security-Policy",
              "default-src 'self';base-uri 'self';font-src 'self' https: data:;form-action 'self';frame-ancestors 'self';img-src 'self' data:;object-src 'none';script-src 'self';script-src-attr 'none';style-src 'self' https: 'unsafe-inline';upgrade-insecure-requests"),
          Map.entry("Cross-Origin-Opener-Policy", "same-origin"),
          Map.entry("Cross-Origin-Resource-Policy", "same-origin"),
          Map.entry("Origin-Agent-Cluster", "?1"),
          Map.entry("Referrer-Policy", "no-referrer"),
          Map.entry("Strict-Transport-Security", "max-age=31536000; includeSubDomains"),
          Map.entry("X-Content-Type-Options", "nosniff"),
          Map.entry("X-DNS-Prefetch-Control", "off"),
          Map.entry("X-Download-Options", "noopen"),
          Map.entry("X-Frame-Options", "SAMEORIGIN"),
          Map.entry("X-Permitted-Cross-Domain-Policies", "none"),
          Map.entry("X-XSS-Protection", "0"));
  private final ObjectMapper json;
  private final RequestBodyParser parser;
  private final Set<String> origins;

  public GlobalHttpFilter(
      ObjectMapper json, @Value("${loco.http.allowed-origins:}") String allowedOrigins) {
    this.json = json;
    parser = new RequestBodyParser(json);
    var configured = new HashSet<String>();
    for (String origin : allowedOrigins.split(","))
      if (!origin.isBlank()) configured.add(origin.trim());
    origins =
        configured.isEmpty()
            ? Set.of("http://localhost:8080", "http://localhost:3000")
            : Set.copyOf(configured);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    // Static file bodies remain streamed. Their validators are provided by
    // ResourceHttpRequestHandler.
    var buffered =
        new ContentCachingResponseWrapper(response) {
          @Override
          public void setContentLength(int length) {
            if (Boolean.TRUE.equals(request.getAttribute(STREAM_RESOURCE)))
              ((HttpServletResponse) getResponse()).setContentLength(length);
            else super.setContentLength(length);
          }

          @Override
          public void setContentLengthLong(long length) {
            if (Boolean.TRUE.equals(request.getAttribute(STREAM_RESOURCE)))
              ((HttpServletResponse) getResponse()).setContentLengthLong(length);
            else super.setContentLengthLong(length);
          }

          @Override
          public jakarta.servlet.ServletOutputStream getOutputStream() throws IOException {
            return Boolean.TRUE.equals(request.getAttribute(STREAM_RESOURCE))
                ? getResponse().getOutputStream()
                : super.getOutputStream();
          }

          @Override
          public void flushBuffer() throws IOException {
            if (Boolean.TRUE.equals(request.getAttribute(STREAM_RESOURCE)))
              getResponse().flushBuffer();
            else super.flushBuffer();
          }
        };
    HttpServletResponse target = buffered;
    try {
      HttpServletRequest parsed;
      try {
        parsed = parser.parse(request);
      } catch (RequestBodyParser.Failure failure) {
        target.setStatus(failure.status);
        target.setContentType("application/json; charset=utf-8");
        json.writeValue(target.getOutputStream(), failure.body());
        return;
      }
      HELMET.forEach(target::setHeader);
      target.setHeader("Vary", "Origin");
      String origin = request.getHeader("Origin");
      if (origin != null && origins.contains(origin))
        target.setHeader("Access-Control-Allow-Origin", origin);
      if (request.getMethod().equals("OPTIONS")) {
        target.setHeader("Access-Control-Allow-Methods", "GET,HEAD,PUT,PATCH,POST,DELETE");
        target.setHeader("Access-Control-Allow-Headers", "Content-Type,Authorization");
        target.setStatus(204);
        target.setContentLength(0);
        return;
      }
      chain.doFilter(parsed, target);
    } finally {
      if (buffered != null) {
        byte[] body = buffered.getContentAsByteArray();
        if (buffered.getContentType() != null
            && buffered.getContentType().startsWith("application/json")) {
          buffered.setContentType("application/json; charset=utf-8");
          if (body.length > 0 && buffered.getHeader("ETag") == null) {
            String tag = weakEtag(body);
            buffered.setHeader("ETag", tag);
            if ((request.getMethod().equals("GET") || request.getMethod().equals("HEAD"))
                && buffered.getStatus() >= 200
                && buffered.getStatus() < 300
                && matches(request.getHeader("If-None-Match"), tag)) {
              buffered.resetBuffer();
              buffered.setStatus(304);
              buffered.setHeader("Content-Type", null);
            }
          }
        }
        buffered.copyBodyToResponse();
      }
    }
  }

  private static boolean matches(String condition, String etag) {
    if (condition == null) return false;
    for (String tag : condition.split(","))
      if (tag.trim().equals("*")
          || tag.trim().replaceFirst("^W/", "").equals(etag.replaceFirst("^W/", ""))) return true;
    return false;
  }

  private static String weakEtag(byte[] bytes) {
    try {
      return "W/\""
          + Integer.toHexString(bytes.length)
          + "-"
          + Base64.getEncoder()
              .withoutPadding()
              .encodeToString(MessageDigest.getInstance("SHA-1").digest(bytes))
          + "\"";
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
