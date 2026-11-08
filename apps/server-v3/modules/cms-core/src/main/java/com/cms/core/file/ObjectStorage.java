package com.cms.core.file;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;

public interface ObjectStorage {
  record Item(String key, Instant createdAt) {}

  record Page(List<Item> items, String nextToken) {}

  void put(String key, byte[] bytes, String contentType);

  InputStream open(String key);

  void delete(String key);

  Page list(String prefix, String token);
}
