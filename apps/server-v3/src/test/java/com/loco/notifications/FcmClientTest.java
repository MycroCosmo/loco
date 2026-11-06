package com.loco.notifications;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FcmClientTest {
  private final ObjectMapper json = new ObjectMapper();
  private final NotificationSender.Message message =
      new NotificationSender.Message("제목", "본문", Map.of("type", "probe"));
  private HttpServer server;
  private String endpoint;
  private FcmCredentials auth;
  private final AtomicInteger calls = new AtomicInteger();
  private final List<Long> waits = new ArrayList<>();

  @BeforeEach
  void setup() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    server.start();
    endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
    auth = mock(FcmCredentials.class);
    when(auth.enabled()).thenReturn(true);
    when(auth.projectId()).thenReturn("offline-project");
    when(auth.accessToken()).thenReturn("offline-access-token");
  }

  @AfterEach
  void close() {
    server.stop(0);
    ((java.util.concurrent.ExecutorService) server.getExecutor()).shutdownNow();
  }

  FcmClient client(int timeout) {
    return new FcmClient(auth, json, Clock.systemUTC(), endpoint, timeout, waits::add);
  }

  void reply(int status, String body, String retryAfter, int idle) {
    server.createContext(
        "/",
        exchange -> {
          calls.incrementAndGet();
          if (status == -1) {
            exchange.close();
            return;
          }
          if (idle > 0)
            try {
              Thread.sleep(idle);
            } catch (InterruptedException stop) {
              Thread.currentThread().interrupt();
            }
          if (retryAfter != null) exchange.getResponseHeaders().set("Retry-After", retryAfter);
          byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
          try {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
          } finally {
            exchange.close();
          }
        });
  }

  @Test
  void sendsV1PayloadAndOneAuthorizationWithoutRealCredentials() throws Exception {
    var received =
        new java.util.concurrent.CompletableFuture<com.fasterxml.jackson.databind.JsonNode>();
    server.createContext(
        "/",
        exchange -> {
          calls.incrementAndGet();
          assertThat(exchange.getRequestURI().getPath())
              .isEqualTo("/v1/projects/offline-project/messages:send");
          assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
              .isEqualTo("Bearer offline-access-token");
          received.complete(json.readTree(exchange.getRequestBody()));
          var bytes = "{\"name\":\"offline/message\"}".getBytes();
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    try (var client = client(15000)) {
      assertThat(client.send("offline-device", message)).isEqualTo(FcmClient.Outcome.SENT);
    }
    assertThat(received.get())
        .isEqualTo(
            json.readTree(
                """
      {"message":{"token":"offline-device","notification":{"title":"제목","body":"본문"},"data":{"type":"probe"},"apns":{"payload":{"aps":{"sound":"default"}}},"android":{"priority":"high"}}}
      """));
    assertThat(calls.get()).isOne();
    verify(auth, times(1)).accessToken();
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 403, 404, 429, 500, 502, 503})
  void retriesOnly503AndUsesTheV2Backoff(int status) {
    reply(status, "{}", null, 0);
    try (var client = client(15000)) {
      assertThat(client.send("offline-device", message)).isEqualTo(FcmClient.Outcome.FAILED);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    assertThat(calls.get()).isEqualTo(status == 503 ? 5 : 1);
    assertThat(waits).isEqualTo(status == 503 ? List.of(0L, 1000L, 2000L, 4000L) : List.of());
  }

  @ParameterizedTest
  @ValueSource(strings = {"1", "60", "61", "0", "invalid"})
  void retryAfterHonors60SecondLimit(String header) throws Exception {
    reply(503, "{}", header, 0);
    try (var client = client(15000)) {
      assertThat(client.send("offline-device", message)).isEqualTo(FcmClient.Outcome.FAILED);
    }
    assertThat(calls.get()).isEqualTo(header.equals("61") ? 1 : 5);
    if (header.equals("1")) assertThat(waits).containsExactly(1000L, 1000L, 1000L, 1000L);
    if (header.equals("60")) assertThat(waits).containsExactly(60000L, 60000L, 60000L, 60000L);
    if (header.equals("61")) assertThat(waits).isEmpty();
  }

  @Test
  void unregisteredIsDistinctFromOtherInvalidTokenErrors() throws Exception {
    reply(
        404,
        "{\"error\":{\"details\":[{\"@type\":\"type.googleapis.com/google.firebase.fcm.v1.FcmError\",\"errorCode\":\"UNREGISTERED\"}]}}",
        null,
        0);
    try (var client = client(15000)) {
      assertThat(client.send("offline-device", message)).isEqualTo(FcmClient.Outcome.UNREGISTERED);
    }
    assertThat(calls.get()).isOne();
    assertThat(waits).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 200})
  void connectionResetAndIdleReadRetryFiveTimes(int variant) throws Exception {
    reply(variant, "{}", null, variant == 200 ? 500 : 0);
    try (var client = client(80)) {
      assertThat(client.send("offline-device", message)).isEqualTo(FcmClient.Outcome.FAILED);
    }
    assertThat(calls.get()).isEqualTo(5);
    assertThat(waits).containsExactly(0L, 1000L, 2000L, 4000L);
  }

  @Test
  void disabledDoesNotReadCredentialsOrCallTransport() throws Exception {
    when(auth.enabled()).thenReturn(false);
    try (var client = client(15000)) {
      assertThat(client.send("offline-device", message)).isEqualTo(FcmClient.Outcome.DISABLED);
    }
    verify(auth, never()).accessToken();
    assertThat(calls.get()).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "{broken", "{}", "null"})
  void absentOrInvalidAccountDisablesWithoutCredentialValues(String raw) {
    assertThat(new FcmCredentials(raw).enabled()).isFalse();
  }

  @Test
  void positiveHttpDateRetryAfterUsesClock() {
    var now = Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"), ZoneOffset.UTC);
    assertThat(FcmClient.retryDelay("Fri, 06 Nov 2026 00:00:02 GMT", 0, now)).isEqualTo(2000);
    assertThat(FcmClient.retryDelay("Fri, 06 Nov 2026 00:00:00 GMT", 2, now)).isEqualTo(2000);
  }
}
