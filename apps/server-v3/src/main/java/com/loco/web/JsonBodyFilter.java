package com.loco.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// Express는 JSON 파싱을 Guard보다 먼저 수행한다. 깨진 JSON은 요청 제한·회원 조회에 도달하지 않는다.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class JsonBodyFilter extends OncePerRequestFilter {
  private static final int MAX_JSON_BYTES = 10 * 1024 * 1024;
  private final ObjectMapper json;

  public JsonBodyFilter(ObjectMapper json) {
    this.json = json;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    String type = request.getContentType();
    if (type == null || !type.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
      chain.doFilter(request, response);
      return;
    }
    byte[] body = request.getInputStream().readNBytes(MAX_JSON_BYTES + 1);
    if (body.length > MAX_JSON_BYTES) {
      response.setStatus(413);
      response.setContentType("application/json");
      response.setCharacterEncoding("UTF-8");
      json.writeValue(
          response.getOutputStream(),
          Map.of("statusCode", 413, "message", "request entity too large"));
      return;
    }
    if (body.length != 0) {
      try {
        var parsed =
            json.reader()
                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readTree(body);
        if (parsed == null || !parsed.isContainerNode()) {
          badJson(response);
          return;
        }
      } catch (JsonProcessingException exception) {
        badJson(response);
        return;
      }
    }
    chain.doFilter(
        new HttpServletRequestWrapper(request) {
          @Override
          public ServletInputStream getInputStream() {
            var bytes = new ByteArrayInputStream(body);
            return new ServletInputStream() {
              @Override
              public int read() {
                return bytes.read();
              }

              @Override
              public int read(byte[] target, int offset, int length) {
                return bytes.read(target, offset, length);
              }

              @Override
              public boolean isFinished() {
                return bytes.available() == 0;
              }

              @Override
              public boolean isReady() {
                return true;
              }

              @Override
              public void setReadListener(ReadListener listener) {
                throw new IllegalStateException("Synchronous JSON body");
              }
            };
          }

          @Override
          public java.io.BufferedReader getReader() {
            return new java.io.BufferedReader(
                new java.io.InputStreamReader(
                    getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
          }
        },
        response);
  }

  private void badJson(HttpServletResponse response) throws IOException {
    response.setStatus(400);
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    json.writeValue(
        response.getOutputStream(),
        Map.of("statusCode", 400, "message", "Malformed JSON", "error", "Bad Request"));
  }
}
