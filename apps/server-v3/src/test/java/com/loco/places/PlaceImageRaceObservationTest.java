package com.loco.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.loco.compat.SharedHarness;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;

@Tag("v2-comparison")
class PlaceImageRaceObservationTest extends PlaceImageObservationSupport {
  record TimedResponse(ResponseEntity<JsonNode> response, double millis) {}

  @ParameterizedTest
  @ValueSource(
      strings = {
        "upload-2",
        "upload-4",
        "upload-8",
        "delete-two",
        "upload-then-delete",
        "delete-then-upload"
      })
  void observeRealRequestsAtTheSameDatabaseWriteBoundary(String scenario) throws Exception {
    int n = scenario.matches("upload-[248]") ? Integer.parseInt(scenario.substring(7)) : 2;
    environment().reserveV2Requests("POST", uploadPath(), n * 3);
    environment().reserveV2Requests("DELETE", uploadPath(), 6);
    var outcomes = mapper.createArrayNode();
    var measurements = mapper.createArrayNode();
    for (int repetition = 1; repetition <= 3; repetition++) {
      seed(2);
      String auth = token(1);
      byte[] png = fixture("png");
      var methods = new ArrayList<String>();
      if (scenario.matches("upload-[248]")) for (int i = 0; i < n; i++) methods.add("POST");
      else if (scenario.equals("delete-two")) methods.addAll(List.of("DELETE", "DELETE"));
      else
        methods.addAll(
            scenario.equals("upload-then-delete")
                ? List.of("POST", "DELETE")
                : List.of("DELETE", "POST"));
      var pending = new ArrayList<Future<TimedResponse>>();
      try (var gate = environment().connect(SharedHarness.Side.V2);
          var observer = environment().connect(SharedHarness.Side.V2);
          var pool = Executors.newFixedThreadPool(n)) {
        gate.setAutoCommit(false);
        try (var q = gate.prepareStatement("SELECT id FROM places WHERE id=? FOR UPDATE")) {
          q.setString(1, placeId);
          try (var row = q.executeQuery()) {
            assertThat(row.next()).isTrue();
          }
        }
        try {
          for (int i = 0; i < n; i++) {
            String method = methods.get(i), url = scenario.equals("delete-two") && i == 1 ? B : A;
            pending.add(
                pool.submit(
                    () -> {
                      long start = System.nanoTime();
                      var response =
                          method.equals("POST")
                              ? upload(
                                  v2Url(),
                                  List.of(new Part("file", "race.png", "image/png", png)),
                                  auth,
                                  "normal")
                              : request(
                                  v2Url(),
                                  uploadPath(),
                                  HttpMethod.DELETE,
                                  auth,
                                  Map.of("imageUrl", url));
                      return new TimedResponse(response, (System.nanoTime() - start) / 1_000_000.0);
                    }));
            // 먼저 보낸 요청의 UPDATE 대기를 확인한 뒤 다음 요청을 보낸다. v2 코드에는 계측/지연을 추가하지 않는다.
            awaitWriters(observer, pending, i + 1);
          }
          var beforeRelease = state();
          var waits = mapper.createArrayNode();
          try (var q = observer.createStatement();
              var rows =
                  q.executeQuery(
                      "SELECT extract(epoch FROM (clock_timestamp()-query_start))*1000 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query ILIKE '%UPDATE%places%' ORDER BY query_start")) {
            while (rows.next()) waits.add(rows.getDouble(1));
          }
          gate.commit();
          var statuses = mapper.createArrayNode();
          var latencies = mapper.createArrayNode();
          var uploadedUrls = new HashSet<String>();
          for (int i = 0; i < n; i++) {
            var response = pending.get(i).get(15, TimeUnit.SECONDS);
            statuses.add(response.response().getStatusCode().value());
            latencies.add(response.millis());
            assertThat(response.response().getStatusCode().value())
                .isEqualTo(methods.get(i).equals("POST") ? 201 : 200);
            if (methods.get(i).equals("POST"))
              for (var url : response.response().getBody().path("imageUrls"))
                if (!url.asText().equals(A) && !url.asText().equals(B))
                  uploadedUrls.add(url.asText());
          }
          var finalState = state();
          var finalUrls = new HashSet<String>();
          finalState.path("urls").forEach(u -> finalUrls.add(u.asText()));
          uploadedUrls.removeAll(finalUrls);
          var row =
              outcomes
                  .addObject()
                  .put("repetition", repetition)
                  .put("requests", n)
                  .put("initialImages", 2);
          row.set("statuses", statuses);
          row.set("state", normalized(finalState));
          row.put("finalImages", finalState.path("urls").size())
              .put("brokenUrls", finalState.path("brokenUrls").size())
              .put("orphanFiles", finalState.path("orphanFiles").size())
              .put("lostUploadedUrls", uploadedUrls.size())
              .put("filesBeforeDatabaseRelease", beforeRelease.path("files").size());
          var brokenHttp = row.putArray("brokenUrlHttpStatuses");
          for (var url : finalState.path("brokenUrls")) {
            int status =
                client
                    .exchange(v2Url() + url.asText(), HttpMethod.HEAD, HttpEntity.EMPTY, Void.class)
                    .getStatusCode()
                    .value();
            assertThat(status).isEqualTo(404);
            brokenHttp.add(status);
          }
          var orphanHttp = row.putArray("orphanFileHttpStatuses");
          for (var url : finalState.path("orphanFiles")) {
            int status =
                client
                    .exchange(v2Url() + url.asText(), HttpMethod.HEAD, HttpEntity.EMPTY, Void.class)
                    .getStatusCode()
                    .value();
            assertThat(status).isEqualTo(200);
            orphanHttp.add(status);
          }
          var measured = measurements.addObject().put("repetition", repetition);
          measured.set("requestMillis", latencies);
          measured.set("blockedQueryAgeMillisAtRelease", waits);
        } finally {
          gate.rollback();
          pending.forEach(f -> f.cancel(true));
          pool.shutdownNow();
        }
      }
    }
    record("race-" + scenario + ".json", outcomes);
    var path = Path.of("build/reports/place-images/timing-" + scenario + ".json");
    mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), measurements);
  }

  private static void awaitWriters(
      java.sql.Connection observer, List<Future<TimedResponse>> pending, int count)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    try (var q = observer.createStatement()) {
      while (true) {
        try (var rows =
            q.executeQuery(
                "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query ILIKE '%UPDATE%places%'")) {
          rows.next();
          if (rows.getInt(1) >= count) return;
        }
        if (pending.stream().anyMatch(Future::isDone))
          throw new AssertionError("UPDATE 경쟁 도달 전 요청 종료");
        if (System.nanoTime() > deadline) throw new AssertionError("UPDATE 잠금 대기 관측 시간 초과");
        Thread.sleep(20);
      }
    }
  }
}
