package com.loco.web;

import java.util.Comparator;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final ObjectMapper mapper;

    public ApiExceptionHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public static Map<String, Object> unauthorized() {
        return Map.of("statusCode", 401, "message", "Unauthorized");
    }

    public static Map<String, Object> forbidden() {
        return Map.of("statusCode", 403, "message", "Forbidden resource", "error", "Forbidden");
    }

    @ExceptionHandler(ApiProblem.class)
    ResponseEntity<?> businessFailure(ApiProblem problem) {
        return ResponseEntity.status(problem.status()).body(problem.body());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> malformedJson() {
        return ResponseEntity.badRequest().body(Map.of("statusCode", 400, "message", "Malformed JSON", "error", "Bad Request"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception exception) {
        log.error("Unhandled API exception", exception);
        return ResponseEntity.internalServerError().body(Map.of("statusCode", 500, "message", "Internal server error"));
    }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<?> fileTooLarge() { return ResponseEntity.status(413).body(ApiProblem.payloadTooLarge("File too large").body()); }

    @ExceptionHandler(org.springframework.web.multipart.MultipartException.class)
    ResponseEntity<?> malformedMultipart(org.springframework.web.multipart.MultipartException exception) {
        Throwable cause = exception;
        while (cause.getCause() != null) cause = cause.getCause();
        String message = String.valueOf(cause.getMessage()).toLowerCase(java.util.Locale.ROOT);
        return ResponseEntity.badRequest().body(ApiProblem.badRequest(message.contains("boundary")
                ? "Multipart: Boundary not found" : "Multipart: Unexpected end of form").body());
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<?> authenticationFailed() {
        return ResponseEntity.status(401).body(unauthorized());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<?> accessDenied() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return new AuthenticationTrustResolverImpl().isAuthenticated(authentication)
                ? ResponseEntity.status(403).body(forbidden())
                : ResponseEntity.status(401).body(unauthorized());
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class,
            HttpRequestMethodNotSupportedException.class})
    ResponseEntity<?> notFound(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (request.getQueryString() != null) {
            path += "?" + request.getQueryString();
        }
        return ResponseEntity.status(404).body(Map.of("statusCode", 404, "error", "Not Found",
                "message", "Cannot " + request.getMethod() + " " + path));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validationFailed(MethodArgumentNotValidException exception) {
        var result = exception.getBindingResult();
        var properties = mapper.getSerializationConfig()
                .introspect(mapper.constructType(exception.getParameter().getParameterType()))
                .findProperties().stream().map(property -> property.getName()).toList();
        // Bean Validation의 Set 순서 대신 DTO 속성 선언 순서로 응답한다.
        var messages = result.getAllErrors().stream()
                .sorted(Comparator.comparingInt(error -> error instanceof FieldError fieldError
                        ? properties.indexOf(fieldError.getField()) : Integer.MAX_VALUE))
                .map(ObjectError::getDefaultMessage).toList();
        return ResponseEntity.badRequest().body(Map.of("statusCode", 400, "message", messages,
                "error", "Bad Request"));
    }
}
