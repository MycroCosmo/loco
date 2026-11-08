package com.loco.cmsinfra;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** Loopback transport faults only; no AWS endpoint or credential provider. */
class StorageTransportTest {
  @Test
  void serverErrorIsNotRetried() throws Exception {
    var calls = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(200, -1);
          } else {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] error =
                "<Error><Code>InternalError</Code><Message>local fault</Message></Error>"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, error.length);
            exchange.getResponseBody().write(error);
          }
          exchange.close();
        });
    server.start();
    try (var storage = connect(server)) {
      assertThatThrownBy(() -> storage.put("cms/files/test", new byte[] {1}, "text/plain"))
          .isInstanceOf(S3Exception.class);
      assertThat(calls).hasValue(1);
    } finally {
      server.stop(0);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void streamedBodyHasSocketAndOverallDeadline(boolean trickle) throws Exception {
    var calls = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var executor = Executors.newCachedThreadPool();
    server.setExecutor(executor);
    server.createContext(
        "/",
        exchange -> {
          try {
            if (exchange.getRequestMethod().equals("HEAD")) exchange.sendResponseHeaders(200, -1);
            else {
              calls.incrementAndGet();
              exchange.sendResponseHeaders(200, 100);
              for (int i = 0; i < 100; i++) {
                exchange.getResponseBody().write('x');
                exchange.getResponseBody().flush();
                Thread.sleep(trickle ? 300 : 12000);
              }
            }
          } catch (Exception disconnected) {
            /* Expected when the client deadline aborts. */
          } finally {
            exchange.close();
          }
        });
    server.start();
    try (var storage = connect(server)) {
      long start = System.nanoTime();
      assertThatThrownBy(
              () -> {
                try (var in = storage.open("cms/files/test")) {
                  in.readAllBytes();
                }
              })
          .isInstanceOf(java.io.IOException.class);
      long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertThat(elapsed).isBetween(trickle ? 9000L : 4000L, trickle ? 15000L : 9000L);
      assertThat(calls).hasValue(1);
      System.out.println("CMS_STORAGE_STREAM_DEADLINE millis=" + elapsed + " requests=" + calls);
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  private S3ObjectStorage connect(HttpServer server) {
    return new CmsStorageConfiguration()
        .cmsObjectStorage(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
            "us-east-1",
            "local-test",
            "local-only-key",
            "local-only-secret",
            false);
  }
}
