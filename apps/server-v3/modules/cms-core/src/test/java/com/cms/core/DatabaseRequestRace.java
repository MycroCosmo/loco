package com.cms.core;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;

/** Holds a real PostgreSQL row until both HTTP requests reach its lock queue. */
final class DatabaseRequestRace {
  static <T> List<T> run(DataSource source, String table, Callable<T> first, Callable<T> second)
      throws Exception {
    if (!Set.of("cms.menu_tree_revision", "cms.administration_state").contains(table))
      throw new IllegalArgumentException();
    try (var gate = source.getConnection();
        var observer = source.getConnection();
        var pool = Executors.newFixedThreadPool(2)) {
      gate.setAutoCommit(false);
      try (var lock = gate.createStatement()) {
        lock.execute("SELECT id FROM " + table + " WHERE id=1 FOR UPDATE");
      }
      var futures = List.of(pool.submit(first), pool.submit(second));
      try {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        try (var query =
            observer.prepareStatement(
                "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE ?")) {
          query.setString(1, "%" + table + "%");
          while (true) {
            try (var result = query.executeQuery()) {
              result.next();
              if (result.getInt(1) >= 2) break;
            }
            if (futures.stream().anyMatch(Future::isDone))
              throw new AssertionError("Request finished before both reached the DB gate");
            if (System.nanoTime() > deadline)
              throw new AssertionError("Two DB lock waiters were not observed");
            Thread.sleep(20);
          }
        }
        gate.commit();
        return List.of(
            futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
      } finally {
        gate.rollback();
        futures.forEach(f -> f.cancel(true));
        pool.shutdownNow();
      }
    }
  }
}
