package com.loco.compat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class V2RequestQuotaTest {
  private final AtomicLong time = new AtomicLong(1);
  private final ArrayList<String> restarts = new ArrayList<>();
  private final V2RequestQuota quota =
      new V2RequestQuota(restarts::add, time::get, time::addAndGet);

  private void response(String method, String route, String handler, int limit, int remaining) {
    var headers = new HttpHeaders();
    headers.set("X-Harness-Handler", handler);
    headers.set("X-Harness-Route", route);
    headers.set("X-RateLimit-Limit", "" + limit);
    headers.set("X-RateLimit-Remaining", "" + remaining);
    quota.observed(method, headers, 200);
  }

  @Test
  void unrelatedExhaustedApiDoesNotRestartTheServer() throws Exception {
    response("POST", "/api/auth/login", "login", 10, 0);
    response("GET", "/api/users/me", "me", 120, 119);
    quota.beginCase();
    quota.before("GET", "/api/users/me", false);
    quota.complete();
    assertThat(restarts).isEmpty();
    quota.beginCase();
    quota.before("POST", "/api/auth/login", false);
    quota.complete();
    assertThat(restarts).containsExactly("exhausted POST /api/auth/login");
  }

  @Test
  void providerAndIdValuesShareHandlerQuotaButHttpMethodsDoNot() throws Exception {
    response("POST", "/api/auth/oauth/:provider", "oauth", 10, 0);
    response("GET", "/api/maps/:mapId", "detail", 120, 0);
    response("PUT", "/api/maps/:mapId", "update", 120, 119);
    quota.beginCase();
    quota.before("PUT", "/api/maps/second?x=1", false);
    quota.complete();
    assertThat(restarts).isEmpty();
    quota.beginCase();
    quota.before("POST", "/api/auth/oauth/naver/", false);
    quota.complete();
    assertThat(restarts).hasSize(1);
  }

  @Test
  void depletedQuotaInsideScenarioWaitsWithoutRestartOrRetry() throws Exception {
    response("POST", "/api/auth/login", "login", 10, 1);
    quota.beginCase();
    quota.before("POST", "/api/auth/login", false);
    response("POST", "/api/auth/login", "login", 10, 0);
    quota.complete();
    quota.before("POST", "/api/auth/login", false);
    quota.complete();
    assertThat(restarts).isEmpty();
    assertThat(quota.waitedMillis()).isEqualTo(61000);
  }

  @Test
  void outOfOrderResponsesCannotRestoreReservedConcurrentSlots() throws Exception {
    response("POST", "/api/auth/reset-password", "reset", 5, 2);
    quota.beginCase();
    quota.before("POST", "/api/auth/reset-password", false);
    quota.before("POST", "/api/auth/reset-password", false);
    response("POST", "/api/auth/reset-password", "reset", 5, 0);
    quota.complete();
    response("POST", "/api/auth/reset-password", "reset", 5, 1);
    quota.complete();
    quota.before("POST", "/api/auth/reset-password", false);
    quota.complete();
    assertThat(quota.waitedMillis()).isEqualTo(61000);
    assertThat(restarts).isEmpty();
  }

  @Test
  void boundaryProbeResetsOnlyUsedQuotaAndThenCanExceedLimit() throws Exception {
    response("POST", "/api/auth/login", "login", 10, 9);
    quota.beginCase();
    for (int i = 0; i < 11; i++) {
      quota.before("POST", "/api/auth/login", true);
      quota.complete();
    }
    assertThat(restarts).containsExactly("probe POST /api/auth/login");
    assertThat(quota.waitedMillis()).isZero();
  }

  @Test
  void reserveRaceSlotsBeforeStartingAndReuseNaturallyExpiredQuota() throws Exception {
    response("POST", "/api/auth/reset-password", "reset", 5, 1);
    quota.beginCase();
    quota.reserveBeforeScenario("POST", "/api/auth/reset-password", 2);
    assertThat(restarts).hasSize(1);
    response("POST", "/api/auth/reset-password", "reset", 5, 0);
    time.addAndGet(Duration.ofSeconds(61).toNanos());
    quota.beginCase();
    quota.before("POST", "/api/auth/reset-password", false);
    quota.complete();
    assertThat(restarts).hasSize(1);
  }

  @Test
  void parserRejectionsDoNotConsumeReservedQuota() throws Exception {
    response("POST", "/api/auth/login", "login", 10, 1);
    quota.beginCase();
    var reservation = quota.before("POST", "/api/auth/login", false);
    quota.observed("POST", new HttpHeaders(), 400, reservation);
    quota.complete();
    quota.beginCase();
    quota.before("POST", "/api/auth/login", false);
    quota.complete();
    assertThat(restarts).isEmpty();
    assertThat(quota.waitedMillis()).isZero();
  }
}
