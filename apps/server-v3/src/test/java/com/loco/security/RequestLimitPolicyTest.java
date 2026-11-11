package com.loco.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loco.web.ApiController;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

class RequestLimitPolicyTest {
  @ApiController
  static class Endpoint {
    public void read() {}
  }

  private ResourceLoader artifact(boolean exists) {
    var loader = mock(ResourceLoader.class);
    var marker = mock(Resource.class);
    when(marker.exists()).thenReturn(exists);
    when(loader.getResource("classpath:META-INF/loco-perf-local-only")).thenReturn(marker);
    return loader;
  }

  private MockEnvironment perf() {
    var environment = new MockEnvironment();
    environment.setActiveProfiles("perf");
    return environment
        .withProperty("loco.perf.isolated", "true")
        .withProperty("spring.datasource.url", "jdbc:postgresql://perf-db:5432/loco_perf")
        .withProperty("loco.cleanup.enabled", "false");
  }

  @Test
  void normalArtifactCannotActivatePerf() {
    assertThatThrownBy(() -> new RequestLimitPolicy(perf(), artifact(false)))
        .isInstanceOf(IllegalStateException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"prod", "production", "default"})
  void perfArtifactCannotActivateOtherProfiles(String profile) {
    var environment = perf();
    environment.setActiveProfiles("perf", profile);
    assertThatThrownBy(() -> new RequestLimitPolicy(environment, artifact(true)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void perfArtifactCannotStartAsNormalApplication() {
    assertThatThrownBy(() -> new RequestLimitPolicy(new MockEnvironment(), artifact(true)))
        .isInstanceOf(IllegalStateException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "spring.datasource.url",
        "loco.perf.isolated",
        "loco.cleanup.enabled",
        "cms.storage.enabled",
        "loco.notifications.service-account"
      })
  void isolationViolationsFailBeforeServing(String property) {
    var environment =
        perf().withProperty(property, property.equals("loco.perf.isolated") ? "false" : "true");
    assertThatThrownBy(() -> new RequestLimitPolicy(environment, artifact(true)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void normalHttpPathStillRejectsRequest121() throws Exception {
    verifyHttpQuota(new RequestLimitPolicy(new MockEnvironment(), artifact(false)), false, "120");
  }

  @Test
  void perfHttpPathAcceptsRequest121AndReturnsRealRemainingQuota() throws Exception {
    verifyHttpQuota(new RequestLimitPolicy(perf(), artifact(true)), true, "600000");
  }

  private void verifyHttpQuota(RequestLimitPolicy policy, boolean allowed121, String limit)
      throws Exception {
    var interceptor =
        new RateLimitInterceptor(
            new RequestRateLimiter(Clock.systemUTC()), new ObjectMapper(), policy);
    var method = new HandlerMethod(new Endpoint(), Endpoint.class.getMethod("read"));
    var request = new MockHttpServletRequest();
    for (int i = 0; i < 120; i++)
      assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), method)).isTrue();
    var response = new MockHttpServletResponse();
    assertThat(interceptor.preHandle(request, response, method)).isEqualTo(allowed121);
    if (allowed121) {
      assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo(limit);
      assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("599879");
    } else {
      assertThat(response.getStatus()).isEqualTo(429);
      assertThat(response.getContentAsString()).contains("ThrottlerException: Too Many Requests");
    }
  }
}
