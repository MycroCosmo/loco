package com.loco.compat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AuthRequestBudgetTest {
    @Test void onlyAuthenticationApiCasesWaitAndDifferentRoutesHaveSeparateBudgets() throws Exception {
        var time = new AtomicLong();
        var budget = new AuthRequestBudget(time::get, time::addAndGet);
        for (int i = 0; i < 200; i++) budget.before("GET", "/api/users/me");
        for (int i = 0; i < 10; i++) budget.before("POST", "/api/auth/login");
        budget.before("POST", "/api/auth/forgot-password");
        assertThat(budget.waitedMillis()).isZero();
        budget.before("POST", "/api/auth/login");
        assertThat(budget.waitedMillis()).isEqualTo(61000);
    }
    @Test void known429BlockIsRespectedByLaterAuthenticationCases() throws Exception {
        var time = new AtomicLong();
        var budget = new AuthRequestBudget(time::get, time::addAndGet);
        budget.recordUnchecked("/api/auth/login");
        budget.blocked("/api/auth/login");
        budget.before("POST", "/api/auth/login");
        assertThat(budget.waitedMillis()).isEqualTo(Duration.ofSeconds(61).toMillis());
    }

    @Test void oauthProvidersShareTheSameHandlerBudget() throws Exception {
        var time = new AtomicLong(-1000000000L);
        var budget = new AuthRequestBudget(time::get, time::addAndGet);
        for (int i = 0; i < 10; i++) budget.before("POST", "/api/auth/oauth/kakao");
        budget.before("POST", "/api/auth/oauth/naver?test=true");
        assertThat(budget.waitedMillis()).isEqualTo(61000);
    }
}
