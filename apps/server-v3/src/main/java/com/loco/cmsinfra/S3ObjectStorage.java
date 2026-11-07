package com.loco.cmsinfra;

import com.cms.core.file.ObjectStorage;
import java.io.*;
import java.time.Duration;
import java.util.concurrent.*;
import software.amazon.awssdk.core.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

public final class S3ObjectStorage implements ObjectStorage, AutoCloseable {
  private final S3Client client;
  private final String bucket;
  private final Duration deadline;
  private final ScheduledExecutorService deadlines =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            var t = new Thread(r, "cms-storage-deadlines");
            t.setDaemon(true);
            return t;
          });

  public S3ObjectStorage(S3Client client, String bucket, Duration deadline) {
    this.client = client;
    this.bucket = bucket;
    this.deadline = deadline;
  }

  public void initialize(boolean create) {
    try {
      client.headBucket(b -> b.bucket(bucket));
    } catch (S3Exception missing) {
      if (!create || missing.statusCode() != 404) throw missing;
      client.createBucket(b -> b.bucket(bucket));
    }
  }

  @Override
  public void put(String key, byte[] bytes, String type) {
    client.putObject(
        b -> b.bucket(bucket).key(key).contentType(type), RequestBody.fromBytes(bytes));
  }

  @Override
  public InputStream open(String key) {
    long began = System.nanoTime();
    ResponseInputStream<GetObjectResponse> response =
        client.getObject(b -> b.bucket(bucket).key(key));
    long remaining = Math.max(0, deadline.toNanos() - (System.nanoTime() - began));
    var expired = new java.util.concurrent.atomic.AtomicBoolean();
    var expiry =
        deadlines.schedule(
            () -> {
              expired.set(true);
              response.abort();
            },
            remaining,
            TimeUnit.NANOSECONDS);
    return new FilterInputStream(response) {
      private void check() throws IOException {
        if (expired.get()) throw new IOException("Storage read deadline exceeded");
      }

      @Override
      public int read() throws IOException {
        check();
        int n = super.read();
        check();
        return n;
      }

      @Override
      public int read(byte[] b, int off, int len) throws IOException {
        check();
        int n = in.read(b, off, len);
        check();
        return n;
      }

      @Override
      public void close() {
        expiry.cancel(false);
        response.abort();
      }
    };
  }

  @Override
  public void delete(String key) {
    try {
      client.deleteObject(b -> b.bucket(bucket).key(key));
    } catch (S3Exception missing) {
      if (missing.statusCode() != 404 || !"NoSuchKey".equals(missing.awsErrorDetails().errorCode()))
        throw missing;
    }
  }

  @Override
  public Page list(String prefix, String token) {
    var response =
        client.listObjectsV2(
            b -> b.bucket(bucket).prefix(prefix).continuationToken(token).maxKeys(1000));
    return new Page(
        response.contents().stream().map(o -> new Item(o.key(), o.lastModified())).toList(),
        Boolean.TRUE.equals(response.isTruncated()) ? response.nextContinuationToken() : null);
  }

  @Override
  public void close() {
    deadlines.shutdownNow();
    client.close();
  }
}
