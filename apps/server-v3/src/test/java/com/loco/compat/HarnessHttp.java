package com.loco.compat;

import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.support.HttpRequestWrapper;

final class HarnessHttp implements ClientHttpRequestInterceptor {
  static final String V2 = "http://v2.harness.invalid";
  private final SharedHarness.Environment environment;
  private final boolean probe;

  HarnessHttp(SharedHarness.Environment environment, boolean probe) {
    this.environment = environment;
    this.probe = probe;
  }

  @Override
  public ClientHttpResponse intercept(
      HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
    boolean v2 = request.getURI().getHost().equals("v2.harness.invalid");
    boolean quota = v2 && environment.quotaMode();
    V2RequestQuota.Reservation reservation = null;
    if (quota)
      try {
        reservation =
            environment.quota.before(
                request.getMethod().name(), request.getURI().getRawPath(), probe);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new IOException("요청 제한 대기 중 중단", error);
      }
    try (var ignored = HarnessTimings.measure("http")) {
      HttpRequest actual =
          v2
              ? new HttpRequestWrapper(request) {
                @Override
                public URI getURI() {
                  return URI.create(
                      environment.v2Url()
                          + request.getURI().getRawPath()
                          + (request.getURI().getRawQuery() == null
                              ? ""
                              : "?" + request.getURI().getRawQuery()));
                }
              }
              : request;
      var response = execution.execute(actual, body);
      String image = response.getHeaders().getFirst("X-Harness-Image-Nanos");
      if (image != null) HarnessTimings.remoteImage(Long.parseLong(image));
      HarnessTimings.observedRequest(
          request.getMethod().name(), response.getHeaders().getFirst("X-Harness-Route"));
      if (quota)
        environment.quota.observed(
            request.getMethod().name(),
            response.getHeaders(),
            response.getStatusCode().value(),
            reservation);
      if (v2 && !probe && response.getStatusCode().value() == 429) {
        response.close();
        throw new AssertionError(
            "예상하지 않은 429 (재시도 안 함): " + request.getMethod() + " " + request.getURI().getRawPath());
      }
      return response;
    } finally {
      if (quota) environment.quota.complete();
    }
  }
}
