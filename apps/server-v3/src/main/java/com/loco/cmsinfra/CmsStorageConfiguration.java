package com.loco.cmsinfra;

import com.cms.core.file.*;
import com.loco.images.codec.ImageConverter;
import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.checksums.*;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.s3.*;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "cms.storage.enabled", havingValue = "true")
public class CmsStorageConfiguration {
  @Bean
  S3ObjectStorage cmsObjectStorage(
      @Value("${cms.storage.endpoint}") URI endpoint,
      @Value("${cms.storage.region}") String region,
      @Value("${cms.storage.bucket}") String bucket,
      @Value("${cms.storage.access-key}") String key,
      @Value("${cms.storage.secret-key}") String secret,
      @Value("${cms.storage.create-bucket:false}") boolean create) {
    if (endpoint.getHost() == null || key.isBlank() || secret.isBlank() || bucket.isBlank())
      throw new IllegalArgumentException("CMS storage configuration incomplete");
    var client =
        S3Client.builder()
            .endpointOverride(endpoint)
            .region(Region.of(region))
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(key, secret)))
            .serviceConfiguration(
                S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .chunkedEncodingEnabled(false)
                    .build())
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            .httpClientBuilder(
                Apache5HttpClient.builder()
                    .connectionTimeout(Duration.ofSeconds(2))
                    .socketTimeout(Duration.ofSeconds(5))
                    .connectionAcquisitionTimeout(Duration.ofSeconds(2)))
            .overrideConfiguration(
                c ->
                    c.apiCallTimeout(Duration.ofSeconds(10))
                        .apiCallAttemptTimeout(Duration.ofSeconds(10))
                        .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build()))
            .build();
    var storage = new S3ObjectStorage(client, bucket, Duration.ofSeconds(10));
    try {
      storage.initialize(create);
      return storage;
    } catch (RuntimeException failed) {
      storage.close();
      throw failed;
    }
  }

  @Bean
  ImageTransformer cmsImageTransformer(ImageConverter converter) {
    return new CmsImageTransformer(converter);
  }

  @Bean
  Cleanup cmsFileCleanup(FileService files) {
    return new Cleanup(files);
  }

  static class Cleanup {
    private final FileService files;

    Cleanup(FileService files) {
      this.files = files;
    }

    @Scheduled(
        fixedDelayString = "${cms.storage.cleanup-interval-ms:600000}",
        initialDelayString = "${cms.storage.cleanup-interval-ms:600000}")
    public void run() {
      try {
        files.reconcile();
      } catch (RuntimeException failed) {
        org.slf4j.LoggerFactory.getLogger(Cleanup.class)
            .warn("CMS storage reconciliation deferred: {}", failed.getClass().getSimpleName());
      }
    }
  }
}
