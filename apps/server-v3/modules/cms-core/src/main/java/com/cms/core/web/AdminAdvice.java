package com.cms.core.web;

import com.cms.core.account.AdminProblem;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(basePackages = "com.cms.core")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminAdvice {
  @ExceptionHandler(AdminProblem.class)
  ResponseEntity<?> problem(AdminProblem problem) {
    return ResponseEntity.status(problem.status())
        .body(Map.of("statusCode", problem.status(), "message", problem.getMessage()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<?> input() {
    return ResponseEntity.badRequest().body(Map.of("statusCode", 400, "message", "Invalid input"));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unexpected(Exception exception) {
    // Exception messages may contain SQL parameters or request secrets. Record the type only.
    org.slf4j.LoggerFactory.getLogger(AdminAdvice.class)
        .error("CMS request failed: {}", exception.getClass().getName());
    return ResponseEntity.internalServerError()
        .body(Map.of("statusCode", 500, "message", "Internal server error"));
  }

  static void write(HttpServletResponse response, int status, String message) throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.getWriter().write("{\"statusCode\":" + status + ",\"message\":\"" + message + "\"}");
  }
}
