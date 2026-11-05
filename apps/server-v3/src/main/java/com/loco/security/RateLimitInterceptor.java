package com.loco.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loco.web.ApiController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {
  private final RequestRateLimiter limiter;
  private final ObjectMapper json;

  public RateLimitInterceptor(RequestRateLimiter limiter, ObjectMapper json) {
    this.limiter = limiter;
    this.json = json;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    if (!(handler instanceof HandlerMethod method)
        || !AnnotatedElementUtils.hasAnnotation(method.getBeanType(), ApiController.class))
      return true;
    var annotation =
        AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RequestLimit.class);
    int limit = annotation == null ? 120 : annotation.value();
    var result = limiter.check(method.getMethod(), request.getRemoteAddr(), limit);
    if (!result.allowed()) {
      response.setStatus(429);
      response.setHeader("Retry-After", Long.toString(result.retryAfterSeconds()));
      response.setContentType("application/json");
      response.setCharacterEncoding("UTF-8");
      json.writeValue(
          response.getOutputStream(),
          Map.of("statusCode", 429, "message", "ThrottlerException: Too Many Requests"));
      return false;
    }
    response.setHeader("X-RateLimit-Limit", Integer.toString(limit));
    response.setHeader("X-RateLimit-Remaining", Integer.toString(result.remaining()));
    response.setHeader("X-RateLimit-Reset", Long.toString(result.resetSeconds()));
    return true;
  }
}
