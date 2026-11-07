package com.loco.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.loco.compat.CompatibilityTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(
    properties = {
      "HOST=127.0.0.1",
      "ALLOWED_ORIGINS= https://one.example.test, , https://two.example.test "
    })
class GlobalHttpSettingsTest extends CompatibilityTestBase {
  @Autowired ServletWebServerApplicationContext context;

  @Override
  protected boolean usesV2Http() {
    return false;
  }

  @Test
  void bindsHostAndConfiguredOriginWithoutAddingCredentials() {
    var server = (TomcatWebServer) context.getWebServer();
    Object address = server.getTomcat().getConnector().getProperty("address");
    assertThat(address).isInstanceOf(java.net.InetAddress.class);
    assertThat(((java.net.InetAddress) address).getHostAddress()).isEqualTo("127.0.0.1");
    var headers = new HttpHeaders();
    headers.setOrigin("https://two.example.test");
    headers.setAccessControlRequestMethod(HttpMethod.PUT);
    var response =
        client.exchange(
            v3Url() + "/api/users/me", HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);
    assertThat(response.getStatusCode().value()).isEqualTo(204);
    assertThat(response.getHeaders().getAccessControlAllowOrigin())
        .isEqualTo("https://two.example.test");
    assertThat(response.getHeaders()).doesNotContainKey("Access-Control-Allow-Credentials");
  }

  @Test
  void unlistedOriginDoesNotBlockActualRequest() {
    var headers = new HttpHeaders();
    headers.setOrigin("http://localhost:8080");
    var response =
        client.exchange(
            v3Url() + "/api/users/me", HttpMethod.GET, new HttpEntity<>(headers), String.class);
    assertThat(response.getStatusCode().value()).isEqualTo(401);
    assertThat(response.getHeaders()).doesNotContainKey("Access-Control-Allow-Origin");
    var preflight =
        client.exchange(
            v3Url() + "/api/users/me", HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);
    assertThat(preflight.getStatusCode().value()).isEqualTo(204);
    assertThat(preflight.getHeaders()).doesNotContainKey("Access-Control-Allow-Origin");
  }

  @Test
  void commaAndWhitespaceOnlyOriginsUseV2Defaults() throws Exception {
    var request =
        new org.springframework.mock.web.MockHttpServletRequest("OPTIONS", "/api/users/me");
    request.addHeader("Origin", "http://localhost:8080");
    var response = new org.springframework.mock.web.MockHttpServletResponse();
    new GlobalHttpFilter(mapper, " , ")
        .doFilter(
            request,
            response,
            (req, res) -> {
              throw new AssertionError("Preflight reached MVC");
            });
    assertThat(response.getStatus()).isEqualTo(204);
    assertThat(response.getHeader("Access-Control-Allow-Origin"))
        .isEqualTo("http://localhost:8080");
  }
}
