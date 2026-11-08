package com.cms.core.file;

import com.cms.core.account.*;
import com.cms.core.history.HistoryWriter;
import com.cms.core.permission.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

public class FileService {
  public static final int MAX_BYTES = 10 * 1024 * 1024;

  public record Download(StoredFile.View metadata, InputStream content) {}

  public record Reconciliation(
      long objects, long pages, long rows, long deleted, long failures, long elapsedMillis) {}

  private final StoredFileRepository files;
  private final PermissionService access;
  private final HistoryWriter history;
  private final ObjectProvider<ObjectStorage> storage;
  private final ObjectProvider<ImageTransformer> images;
  private final Clock clock;
  private final TransactionTemplate tx;
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FileService.class);

  public FileService(
      StoredFileRepository files,
      PermissionService access,
      HistoryWriter history,
      ObjectProvider<ObjectStorage> storage,
      ObjectProvider<ImageTransformer> images,
      Clock clock,
      PlatformTransactionManager manager) {
    this.files = files;
    this.access = access;
    this.history = history;
    this.storage = storage;
    this.images = images;
    this.clock = clock;
    tx = new TransactionTemplate(manager);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  private ObjectStorage store() {
    var result = storage.getIfAvailable();
    if (result == null) throw new AdminProblem(503, "File storage unavailable");
    return result;
  }

  public StoredFile.View upload(
      AdminIdentity actor, String name, String contentType, byte[] input) {
    access.require(actor, "cms.files", Action.MANAGE);
    validateName(name);
    if (input == null || input.length == 0) throw new AdminProblem(400, "Empty file");
    if (input.length > MAX_BYTES) throw new AdminProblem(413, "File too large");
    var store = store();
    String id = UUID.randomUUID().toString(), key = "cms/files/" + id;
    tx.executeWithoutResult(
        s -> {
          access.require(actor, "cms.files", Action.MANAGE);
          files.saveAndFlush(new StoredFile(id, name, actor.id(), clock.instant()));
        });
    try {
      var converted = convert(contentType, input);
      if (converted.bytes.length > MAX_BYTES)
        throw new AdminProblem(413, "Converted file too large");
      store.put(key, converted.bytes, converted.mime);
      String hash = hash(converted.bytes);
      return tx.execute(
          s -> {
            access.require(actor, "cms.files", Action.MANAGE);
            if (files.activate(id, converted.mime, converted.bytes.length, hash) != 1)
              throw new AdminProblem(409, "Upload no longer active");
            var file = files.findById(id).orElseThrow();
            history.record(actor.id(), "UPLOAD", "file", id, null, file.snapshot());
            return file.view();
          });
    } catch (RuntimeException failed) {
      compensate(id, key, store);
      throw failed;
    }
  }

  private void compensate(String id, String key, ObjectStorage store) {
    try {
      boolean removable =
          Boolean.TRUE.equals(
              tx.execute(
                  s -> {
                    files.fail(id);
                    return files
                        .findById(id)
                        .map(f -> f.status != StoredFile.Status.ACTIVE)
                        .orElse(true);
                  }));
      if (removable) store.delete(key);
    } catch (RuntimeException cleanupFailed) {
      log.warn(
          "CMS file compensation deferred: id={}, error={}",
          id,
          cleanupFailed.getClass().getSimpleName());
    }
  }

  private record Converted(byte[] bytes, String mime) {}

  private Converted convert(String mime, byte[] input) {
    if (mime == null) throw new AdminProblem(415, "Unsupported file type");
    mime = mime.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    if (Set.of("image/jpeg", "image/png", "image/webp", "image/avif").contains(mime)) {
      var transformer = images.getIfAvailable();
      if (transformer == null) throw new AdminProblem(503, "Image transformer unavailable");
      return new Converted(transformer.webp(input), "image/webp");
    }
    if (mime.equals("application/pdf")) {
      String start = new String(input, 0, Math.min(8, input.length), StandardCharsets.US_ASCII);
      String tail =
          new String(
              input,
              Math.max(0, input.length - 1024),
              Math.min(1024, input.length),
              StandardCharsets.US_ASCII);
      if (!start.startsWith("%PDF-") || !tail.contains("%%EOF"))
        throw new AdminProblem(415, "Invalid PDF");
    } else if (Set.of("text/plain", "text/csv").contains(mime)) {
      try {
        var decoded =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(input));
        if (decoded.chars().anyMatch(c -> c == 0 || (c < 32 && c != 9 && c != 10 && c != 13)))
          throw new CharacterCodingException();
      } catch (CharacterCodingException invalid) {
        throw new AdminProblem(415, "Invalid UTF-8 text");
      }
    } else throw new AdminProblem(415, "Unsupported file type");
    return new Converted(input, mime);
  }

  private void validateName(String name) {
    if (name == null
        || name.isBlank()
        || name.length() > 255
        || name.contains("/")
        || name.contains("\\")
        || name.equals(".")
        || name.equals("..")
        || name.chars().anyMatch(c -> Character.isISOControl(c)))
      throw new AdminProblem(400, "Invalid filename");
  }

  private String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public List<StoredFile.View> list(AdminIdentity actor, int page, int size) {
    access.require(actor, "cms.files", Action.READ);
    if (page < 0 || size < 1 || size > 100) throw new AdminProblem(400, "Invalid page");
    return tx.execute(
        s ->
            files
                .findByStatus(
                    StoredFile.Status.ACTIVE,
                    PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")))
                .stream()
                .map(StoredFile::view)
                .toList());
  }

  public StoredFile.View detail(AdminIdentity actor, String id) {
    access.require(actor, "cms.files", Action.READ);
    return tx.execute(s -> active(id).view());
  }

  private StoredFile active(String id) {
    return files
        .findById(id)
        .filter(f -> f.status == StoredFile.Status.ACTIVE)
        .orElseThrow(() -> new AdminProblem(404, "Not found"));
  }

  public Download download(AdminIdentity actor, String id) {
    var metadata = detail(actor, id);
    return new Download(metadata, store().open("cms/files/" + id));
  }

  public void delete(AdminIdentity actor, String id) {
    access.require(actor, "cms.files", Action.MANAGE);
    store();
    tx.executeWithoutResult(
        s -> {
          access.require(actor, "cms.files", Action.MANAGE);
          var current = files.findById(id);
          if (current.isEmpty()) return;
          var f = current.get();
          if (f.status == StoredFile.Status.DELETE_PENDING) return;
          if (f.status != StoredFile.Status.ACTIVE) throw new AdminProblem(404, "Not found");
          var before = f.snapshot();
          if (files.markDelete(id) == 1)
            history.record(actor.id(), "DELETE", "file", id, before, null);
        });
    cleanup(id);
  }

  private enum Cleanup {
    SKIPPED,
    DELETED,
    FAILED
  }

  private Cleanup cleanup(String id) {
    boolean attempt = Boolean.TRUE.equals(tx.execute(s -> files.attempted(id) == 1));
    if (!attempt) return Cleanup.SKIPPED;
    try {
      store().delete("cms/files/" + id);
      tx.executeWithoutResult(s -> files.removeFinished(id));
      return Cleanup.DELETED;
    } catch (RuntimeException failed) {
      log.warn(
          "CMS file deletion deferred: id={}, error={}", id, failed.getClass().getSimpleName());
      return Cleanup.FAILED;
    }
  }

  public Reconciliation reconcile() {
    var store = store();
    long started = System.nanoTime(), deleted = 0, failures = 0, objects = 0, pages = 0;
    var cutoff = clock.instant().minus(Duration.ofMinutes(10));
    var ids = tx.execute(s -> files.unfinished());
    for (String id : ids) {
      try {
        tx.executeWithoutResult(
            s -> files.abandon(id, LocalDateTime.ofInstant(cutoff, ZoneOffset.UTC)));
        var outcome = cleanup(id);
        if (outcome == Cleanup.DELETED) deleted++;
        if (outcome == Cleanup.FAILED) failures++;
      } catch (RuntimeException failed) {
        failures++;
        log.warn("CMS file reconciliation row failed: {}", failed.getClass().getSimpleName());
      }
    }
    String token = null;
    do {
      var page = store.list("cms/files/", token);
      pages++;
      for (var object : page.items()) {
        objects++;
        if (object.createdAt() == null || object.createdAt().isAfter(cutoff)) continue;
        String id = object.key().substring("cms/files/".length());
        try {
          if (!UUID.fromString(id).toString().equals(id)) continue;
        } catch (IllegalArgumentException invalid) {
          continue;
        }
        if (Boolean.TRUE.equals(tx.execute(s -> files.existsById(id)))) continue;
        try {
          store.delete(object.key());
          deleted++;
        } catch (RuntimeException failed) {
          failures++;
        }
      }
      token = page.nextToken();
    } while (token != null);
    var result =
        new Reconciliation(
            objects,
            pages,
            ids.size(),
            deleted,
            failures,
            (System.nanoTime() - started) / 1_000_000);
    log.info("CMS file reconciliation: {}", result);
    return result;
  }
}
