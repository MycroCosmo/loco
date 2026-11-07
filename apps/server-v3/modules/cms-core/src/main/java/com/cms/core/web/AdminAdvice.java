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
  @ExceptionHandler(org.springframework.web.multipart.MultipartException.class)
  ResponseEntity<?> multipart() {
    return ResponseEntity.badRequest().body(body(400, "Invalid multipart request"));
  }

  @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
  ResponseEntity<?> fileSize() {
    return ResponseEntity.status(413).body(body(413, "File too large"));
  }

  @ExceptionHandler({
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class
  })
  ResponseEntity<?> parameter() {
    return ResponseEntity.badRequest().body(body(400, "Invalid input"));
  }

  @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
  ResponseEntity<?> integrity() {
    return ResponseEntity.status(409).body(body(409, "Resource conflict"));
  }

  @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
  ResponseEntity<?> conflict() {
    return ResponseEntity.status(409).body(body(409, "Stale version"));
  }

  @ExceptionHandler(AdminProblem.class)
  ResponseEntity<?> problem(AdminProblem problem) {
    return ResponseEntity.status(problem.status())
        .body(body(problem.status(), problem.getMessage(), problem.code()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<?> input() {
    return ResponseEntity.badRequest().body(body(400, "Invalid input"));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unexpected(Exception exception) {
    // Exception messages may contain SQL parameters or request secrets. Record the type only.
    org.slf4j.LoggerFactory.getLogger(AdminAdvice.class)
        .error("CMS request failed: {}", exception.getClass().getName());
    return ResponseEntity.internalServerError().body(body(500, "Internal server error"));
  }

  private static Map<String, Object> body(int status, String message) {
    return body(status, message, AdminProblem.defaultCode(status));
  }

  private static Map<String, Object> body(int status, String message, String code) {
    return Map.of("statusCode", status, "message", message, "code", code);
  }

  static void write(HttpServletResponse response, int status, String message, String code)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    new com.fasterxml.jackson.databind.ObjectMapper()
        .writeValue(response.getWriter(), body(status, message, code));
  }
}
