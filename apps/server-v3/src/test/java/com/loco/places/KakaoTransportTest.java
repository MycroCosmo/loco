package com.loco.places;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loco.web.ApiProblem;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class KakaoTransportTest {
  @Test
  void addingApacheDoesNotReplaceTheExistingBootJdkFactory() {
    new org.springframework.boot.test.context.runner.ApplicationContextRunner()
        .withInitializer(
            new org.springframework.boot.test.context.ConfigDataApplicationContextInitializer())
        .withConfiguration(
            org.springframework.boot.autoconfigure.AutoConfigurations.of(
                org.springframework.boot.autoconfigure.http.client.HttpClientAutoConfiguration
                    .class))
        .run(
            context ->
                assertThat(
                        context
                            .getBean(
                                org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder
                                    .class)
                            .build())
                    .isInstanceOf(
                        org.springframework.http.client.JdkClientHttpRequestFactory.class));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void failedOrIdleTlsHandshakeMakesOneConnectionAndNoHttpRequest(boolean stall) throws Exception {
    var connections = new AtomicInteger();
    var sockets = new java.util.concurrent.CopyOnWriteArrayList<java.net.Socket>();
    try (var server = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      var worker =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      while (!server.isClosed()) {
                        var socket = server.accept();
                        sockets.add(socket);
                        connections.incrementAndGet();
                        if (!stall) {
                          socket
                              .getOutputStream()
                              .write("not a TLS server".getBytes(StandardCharsets.UTF_8));
                          socket.close();
                        }
                      }
                    } catch (java.io.IOException expectedOnClose) {
                    }
                  });
      try (var client =
          new KakaoSearchClient(
              URI.create("https://127.0.0.1:" + server.getLocalPort()),
              "",
              2000,
              5000,
              new ObjectMapper())) {
        long start = System.nanoTime();
        assertThatThrownBy(() -> client.search("query=test"))
            .isInstanceOf(ApiProblem.class)
            .satisfies(
                e ->
                    assertThat(((ApiProblem) e).body().get("message"))
                        .isEqualTo("카카오 검색 API 호출 실패"));
        double seconds = (System.nanoTime() - start) / 1e9;
        assertThat(connections.get()).isEqualTo(1);
        if (stall) assertThat(seconds).isBetween(1.5, 4.0);
        else assertThat(seconds).isLessThan(4.0);
      } finally {
        server.close();
        for (var socket : sockets) socket.close();
        worker.join(1000);
        assertThat(worker.isAlive()).isFalse();
      }
    }
  }

  @Test
  void logsStatusAndBoundedLengthWithoutBodyHeadersUrlOrSecrets() throws Exception {
    var hits = new AtomicInteger();
    var server =
        com.sun.net.httpserver.HttpServer.create(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          hits.incrementAndGet();
          assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
          byte[] bytes =
              ("Authorization: Bearer reflected-sentinel\nKakaoAK reflected-key\n"
                      + "x".repeat(5000))
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(503, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    var logger =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(KakaoSearchClient.class);
    var appender =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    server.start();
    try (var client =
        new KakaoSearchClient(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
            "",
            2000,
            5000,
            new ObjectMapper())) {
      assertThatThrownBy(() -> client.search("query=private-search-term"))
          .isInstanceOf(ApiProblem.class);
      assertThat(hits.get()).isEqualTo(1);
      assertThat(appender.list).hasSize(1);
      assertThat(appender.list.getFirst().getFormattedMessage())
          .isEqualTo("Kakao search failed: upstreamStatus=503, sampledBodyBytes=1024");
      assertThat(appender.list.getFirst().getThrowableProxy()).isNull();
    } finally {
      server.stop(0);
      logger.detachAppender(appender);
      appender.stop();
    }
  }
}
