package com.loco.places;

import com.fasterxml.jackson.databind.JsonNode;
import com.loco.compat.SharedHarness;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.ResponseEntity;

/** 정합성 재현 전용: 성능 시간을 수집하지 않는다. */
final class ImageRequestRaces {
  interface AtGate {
    void run(Connection connection) throws Exception;
  }

  static List<ResponseEntity<JsonNode>> run(
      SharedHarness.Environment env,
      SharedHarness.Side side,
      String id,
      List<Callable<ResponseEntity<JsonNode>>> actions,
      int waiters,
      boolean ordered,
      AtGate beforeRelease)
      throws Exception {
    var futures = new ArrayList<Future<ResponseEntity<JsonNode>>>();
    try (var gate = env.connect(side);
        var observer = env.connect(side);
        var pool = Executors.newFixedThreadPool(actions.size())) {
      gate.setAutoCommit(false);
      try (var q = gate.prepareStatement("SELECT id FROM places WHERE id=? FOR UPDATE")) {
        q.setString(1, id);
        try (var r = q.executeQuery()) {
          if (!r.next()) throw new AssertionError("gate row missing");
        }
      }
      var start = new CountDownLatch(ordered ? 0 : 1);
      var ready = new CountDownLatch(actions.size());
      try {
        for (var action : actions) {
          futures.add(
              pool.submit(
                  () -> {
                    ready.countDown();
                    start.await();
                    return action.call();
                  }));
          if (ordered) awaitWriters(observer, futures, futures.size());
        }
        if (!ordered) {
          if (!ready.await(5, TimeUnit.SECONDS))
            throw new AssertionError("client barrier timed out");
          start.countDown();
          if (waiters > 0) awaitWriters(observer, futures, waiters);
        }
        beforeRelease.run(gate);
        gate.commit();
        var results = new ArrayList<ResponseEntity<JsonNode>>();
        for (var future : futures) results.add(future.get(30, TimeUnit.SECONDS));
        return results;
      } finally {
        start.countDown();
        gate.rollback();
        futures.forEach(f -> f.cancel(true));
        pool.shutdownNow();
      }
    }
  }

  private static void awaitWriters(
      Connection connection, List<? extends Future<?>> futures, int count) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    try (var query = connection.createStatement()) {
      while (true) {
        try (var row =
            query.executeQuery(
                "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query ILIKE '%UPDATE%places%'")) {
          row.next();
          if (row.getInt(1) >= count) return;
        }
        if (futures.stream().anyMatch(Future::isDone))
          throw new AssertionError("request ended before write contention");
        if (System.nanoTime() > deadline)
          throw new AssertionError("write contention was not observed");
        Thread.sleep(20);
      }
    }
  }
}
