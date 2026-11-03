package com.loco.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;

public final class DatabaseRequestRace {
    private DatabaseRequestRace() { }

    public static List<ResponseEntity<JsonNode>> run(SharedHarness.Environment environment, SharedHarness.Side side,
            String table, String id, Callable<ResponseEntity<JsonNode>> request) throws Exception {
        if (!table.matches("[a-z_]+")) throw new IllegalArgumentException("테스트 테이블 이름 오류");
        var futures = new ArrayList<Future<ResponseEntity<JsonNode>>>();
        try (var gate = environment.connect(side); var observer = environment.connect(side);
             var pool = Executors.newFixedThreadPool(2)) {
            gate.setAutoCommit(false);
            try (var row = gate.prepareStatement("SELECT id FROM " + table + " WHERE id=? FOR UPDATE")) {
                row.setString(1, id);
                try (var result = row.executeQuery()) {
                    if (!result.next()) throw new AssertionError("경쟁 재현용 행 없음");
                }
            }
            try {
                futures.add(pool.submit(request));
                futures.add(pool.submit(request));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                try (var waiting = observer.prepareStatement("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE datname=current_database() AND wait_event_type='Lock'
                        AND query LIKE '%' || ? || '%'
                        """)) {
                    waiting.setString(1, table);
                    while (true) {
                        try (var result = waiting.executeQuery()) {
                            result.next();
                            if (result.getInt(1) >= 2) break;
                        }
                        if (futures.stream().anyMatch(Future::isDone)) throw new AssertionError("두 요청이 DB 행 경쟁에 도달하기 전에 끝남");
                        if (System.nanoTime() >= deadline) throw new AssertionError("동시 DB 잠금 대기 확인 시간 초과");
                        Thread.sleep(25);
                    }
                }
                gate.commit();
                var responses = new ArrayList<ResponseEntity<JsonNode>>();
                for (var future : futures) responses.add(future.get(15, TimeUnit.SECONDS));
                return List.copyOf(responses);
            } finally {
                gate.rollback();
                futures.forEach(future -> future.cancel(true));
                pool.shutdownNow();
            }
        }
    }
}
