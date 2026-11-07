package com.loco.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.*;
import org.apache.hc.client5.http.impl.classic.*;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.io.entity.*;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;

@Component
public class FcmClient implements AutoCloseable {
  public enum Outcome {
    SENT,
    UNREGISTERED,
    FAILED,
    DISABLED
  }

  @FunctionalInterface
  interface Sleeper {
    void sleep(long millis) throws InterruptedException;
  }

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FcmClient.class);
  private final FcmCredentials credentials;
  private final ObjectMapper json;
  private final String baseUrl;
  private final CloseableHttpClient http;
  private final Sleeper sleeper;
  private final Clock clock;
  private volatile boolean closed;

  @Autowired
  public FcmClient(
      FcmCredentials credentials,
      ObjectMapper json,
      Clock clock,
      @Value("${loco.notifications.base-url:https://fcm.googleapis.com}") String baseUrl,
      @Value("${loco.notifications.timeout-ms:15000}") int timeout) {
    this(credentials, json, clock, baseUrl, timeout, Thread::sleep);
  }

  FcmClient(
      FcmCredentials credentials,
      ObjectMapper json,
      Clock clock,
      String baseUrl,
      int timeout,
      Sleeper sleeper) {
    if (timeout <= 0) throw new IllegalArgumentException("FCM timeout must be positive");
    this.credentials = credentials;
    this.json = json;
    this.clock = clock;
    this.baseUrl = baseUrl;
    this.sleeper = sleeper;
    var limit = Timeout.ofMilliseconds(timeout);
    http =
        HttpClients.custom()
            .disableAutomaticRetries()
            .disableRedirectHandling()
            .disableCookieManagement()
            .setConnectionManager(
                PoolingHttpClientConnectionManagerBuilder.create()
                    .setDefaultConnectionConfig(
                        ConnectionConfig.custom()
                            .setConnectTimeout(limit)
                            .setSocketTimeout(limit)
                            .build())
                    .setDefaultTlsConfig(TlsConfig.custom().setHandshakeTimeout(limit).build())
                    .build())
            .setDefaultRequestConfig(
                RequestConfig.custom()
                    .setConnectionRequestTimeout(limit)
                    .setResponseTimeout(limit)
                    .build())
            .build();
  }

  public boolean enabled() {
    return credentials.enabled();
  }

  public Outcome send(String token, NotificationSender.Message message) {
    if (!enabled()) return Outcome.DISABLED;
    if (closed || Thread.currentThread().isInterrupted()) return Outcome.FAILED;
    try {
      // SDK처럼 인증 갱신은 FCM retry 루프 밖에서 수행한다.
      String accessToken = credentials.accessToken();
      var body = json.createObjectNode();
      var node = body.putObject("message");
      node.put("token", token);
      node.putObject("notification").put("title", message.title()).put("body", message.body());
      if (message.data() != null) node.set("data", json.valueToTree(message.data()));
      node.putObject("apns").putObject("payload").putObject("aps").put("sound", "default");
      node.putObject("android").put("priority", "high");
      for (int retry = 0; ; retry++) {
        if (closed || Thread.currentThread().isInterrupted()) return Outcome.FAILED;
        Response result;
        try {
          var request =
              new HttpPost(baseUrl + "/v1/projects/" + credentials.projectId() + "/messages:send");
          request.setHeader("Authorization", "Bearer " + accessToken);
          request.setEntity(
              new StringEntity(json.writeValueAsString(body), ContentType.APPLICATION_JSON));
          result =
              http.execute(
                  request,
                  response ->
                      new Response(
                          response.getCode(),
                          response.getFirstHeader("Retry-After") == null
                              ? null
                              : response.getFirstHeader("Retry-After").getValue(),
                          response.getEntity() == null
                              ? ""
                              : EntityUtils.toString(
                                  response.getEntity(), StandardCharsets.UTF_8)));
        } catch (IOException error) {
          if (retry < 4 && retryable(error)) {
            sleeper.sleep(backoff(retry));
            continue;
          }
          log.warn("FCM send failed: transport={}", error.getClass().getSimpleName());
          return Outcome.FAILED;
        }
        long delay = retryDelay(result.retryAfter(), retry, clock);
        if (result.status() == 503 && retry < 4 && delay <= 60000) {
          sleeper.sleep(delay);
          continue;
        }
        if (result.status() >= 200 && result.status() < 300) {
          var parsed = json.readTree(result.body());
          if (parsed != null && parsed.path("name").isTextual()) return Outcome.SENT;
        } else {
          try {
            var parsed = json.readTree(result.body());
            if (parsed != null)
              for (var detail : parsed.path("error").path("details"))
                if (detail
                        .path("@type")
                        .asText()
                        .equals("type.googleapis.com/google.firebase.fcm.v1.FcmError")
                    && detail.path("errorCode").asText().equals("UNREGISTERED"))
                  return Outcome.UNREGISTERED;
          } catch (IOException ignored) {
            /* 외부 오류 원문은 로그에 넣지 않는다. */
          }
        }
        log.warn("FCM send failed: status={}", result.status());
        return Outcome.FAILED;
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      log.warn("FCM send interrupted");
      return Outcome.FAILED;
    } catch (Exception error) {
      log.warn("FCM send failed: cause={}", error.getClass().getSimpleName());
      return Outcome.FAILED;
    }
  }

  private record Response(int status, String retryAfter, String body) {}

  static long backoff(int retry) {
    return retry == 0 ? 0 : Math.min(60000, 500L * (1L << retry));
  }

  static long retryDelay(String value, int retry, Clock clock) {
    if (value != null) {
      try {
        var match = java.util.regex.Pattern.compile("^[\\s]*([+-]?[0-9]+)").matcher(value);
        long millis =
            match.find()
                ? Math.multiplyExact(Long.parseLong(match.group(1)), 1000)
                : Duration.between(
                        clock.instant(),
                        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant())
                    .toMillis();
        if (millis > 0) return millis;
      } catch (ArithmeticException overflow) {
        return Long.MAX_VALUE;
      } catch (RuntimeException invalid) {
      }
    }
    return backoff(retry);
  }

  private static boolean retryable(IOException error) {
    return error instanceof SocketTimeoutException
        || error instanceof org.apache.hc.core5.http.NoHttpResponseException
        || (error instanceof SocketException && "Connection reset".equals(error.getMessage()));
  }

  @PreDestroy
  public void close() throws IOException {
    closed = true;
    http.close(org.apache.hc.core5.io.CloseMode.IMMEDIATE);
  }
}
