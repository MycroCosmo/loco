package com.cms.core.file;

import jakarta.persistence.*;
import java.time.*;
import java.util.*;

@Entity
@Table(name = "stored_file", schema = "cms")
public class StoredFile {
  public enum Status {
    UPLOADING,
    ACTIVE,
    FAILED,
    ABANDONED,
    DELETE_PENDING
  }

  public record View(
      String id,
      String originalName,
      String mediaType,
      Long byteSize,
      String sha256,
      String createdBy,
      Instant createdAt) {}

  @Id String id;

  @Column(name = "object_key", nullable = false, unique = true)
  String objectKey;

  @Column(name = "original_name", nullable = false)
  String originalName;

  @Column(name = "media_type")
  String mediaType;

  @Column(name = "byte_size")
  Long byteSize;

  String sha256;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  Status status;

  @Column(name = "created_by", nullable = false)
  String createdBy;

  @Column(name = "created_at", nullable = false)
  LocalDateTime createdAt;

  @Column(name = "delete_attempts", nullable = false)
  int deleteAttempts;

  protected StoredFile() {}

  StoredFile(String id, String name, String actor, Instant now) {
    this.id = id;
    this.objectKey = "cms/files/" + id;
    this.originalName = name;
    this.createdBy = actor;
    this.createdAt =
        LocalDateTime.ofInstant(now, ZoneOffset.UTC)
            .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    status = Status.UPLOADING;
  }

  public View view() {
    return new View(
        id,
        originalName,
        mediaType,
        byteSize,
        sha256,
        createdBy,
        createdAt.toInstant(ZoneOffset.UTC));
  }

  Map<String, Object> snapshot() {
    return Map.of(
        "originalName",
        originalName,
        "mediaType",
        mediaType,
        "byteSize",
        byteSize,
        "sha256",
        sha256,
        "createdBy",
        createdBy,
        "status",
        status.name());
  }
}
