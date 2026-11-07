package com.loco.notifications;

import com.google.auth.oauth2.ServiceAccountCredentials;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** ADC/metadata fallback 없이 명시한 서비스 계정만 사용한다. */
@Component
public class FcmCredentials {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(FcmCredentials.class);
  private final ServiceAccountCredentials credentials;

  public FcmCredentials(@Value("${loco.notifications.service-account:}") String raw) {
    ServiceAccountCredentials parsed = null;
    if (raw.isEmpty()) log.warn("FCM disabled: service account is not configured");
    else {
      try {
        parsed =
            (ServiceAccountCredentials)
                ServiceAccountCredentials.fromStream(
                        new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)))
                    .createScoped(List.of("https://www.googleapis.com/auth/firebase.messaging"));
        if (parsed.getProjectId() == null || parsed.getProjectId().isBlank())
          throw new IllegalArgumentException("Missing project id");
        log.info("FCM enabled: explicit service account configured");
      } catch (Exception failure) {
        parsed = null;
        log.warn("FCM disabled: invalid service account ({})", failure.getClass().getSimpleName());
      }
    }
    credentials = parsed;
  }

  public boolean enabled() {
    return credentials != null;
  }

  public String projectId() {
    return credentials.getProjectId();
  }

  public String accessToken() throws IOException {
    credentials.refreshIfExpired();
    return credentials.getAccessToken().getTokenValue();
  }
}
