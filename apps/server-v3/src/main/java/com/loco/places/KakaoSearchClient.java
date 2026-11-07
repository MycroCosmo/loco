package com.loco.places;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loco.web.ApiProblem;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class KakaoSearchClient implements AutoCloseable {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(KakaoSearchClient.class);
  private final URI endpoint;
  private final String key;
  private final ObjectMapper json;
  private final CloseableHttpClient http;

  public KakaoSearchClient(
      @Value("${loco.kakao.base-url}") URI endpoint,
      @Value("${loco.kakao.api-key:}") String key,
      @Value("${loco.kakao.connect-timeout-ms}") int connectMillis,
      @Value("${loco.kakao.response-timeout-ms}") int responseMillis,
      ObjectMapper json) {
    if (connectMillis <= 0 || responseMillis <= 0)
      throw new IllegalArgumentException("Kakao timeouts must be positive");
    this.endpoint = endpoint;
    this.key = key;
    this.json = json;
    var manager =
        PoolingHttpClientConnectionManagerBuilder.create()
            .setDefaultTlsConfig(
                org.apache.hc.client5.http.config.TlsConfig.custom()
                    .setHandshakeTimeout(Timeout.ofMilliseconds(connectMillis))
                    .build())
            .setDefaultConnectionConfig(
                ConnectionConfig.custom()
                    .setConnectTimeout(Timeout.ofMilliseconds(connectMillis))
                    .setSocketTimeout(Timeout.ofMilliseconds(responseMillis))
                    .build())
            .build();
    http =
        HttpClients.custom()
            .setConnectionManager(manager)
            .disableAutomaticRetries()
            .disableRedirectHandling()
            .disableCookieManagement()
            .setDefaultRequestConfig(
                RequestConfig.custom()
                    .setResponseTimeout(Timeout.ofMilliseconds(responseMillis))
                    .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectMillis))
                    .build())
            .build();
  }

  public JsonNode search(String query) {
    try {
      var request = new HttpGet(URI.create(endpoint.toString() + "?" + query));
      if (!key.isEmpty()) request.setHeader("Authorization", "KakaoAK " + key);
      return http.execute(
          request,
          response -> {
            if (response.getCode() < 200 || response.getCode() >= 300) {
              // 외부 본문/헤더에는 키가 반사될 수 있다. 본문은 최대 1024바이트만 읽고 내용은 로그에 넣지 않는다.
              int sampled =
                  response.getEntity() == null
                      ? 0
                      : response.getEntity().getContent().readNBytes(1024).length;
              log.warn(
                  "Kakao search failed: upstreamStatus={}, sampledBodyBytes={}",
                  response.getCode(),
                  sampled);
              throw ApiProblem.internalServer("카카오 검색 API 호출 실패");
            }
            return KakaoSearchResponse.map(
                json.readTree(EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8)));
          });
    } catch (ApiProblem problem) {
      throw problem;
    } catch (Exception failure) {
      // 예외 message/stack에도 URL·헤더·본문이 들어갈 수 있어 종류만 기록한다.
      log.warn("Kakao search failed: cause={}", failure.getClass().getSimpleName());
      throw ApiProblem.internalServer("카카오 검색 API 호출 실패");
    }
  }

  @PreDestroy
  public void close() throws java.io.IOException {
    http.close();
  }
}
