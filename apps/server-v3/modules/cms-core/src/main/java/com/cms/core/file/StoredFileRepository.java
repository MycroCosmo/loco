package com.cms.core.file;

import java.time.LocalDateTime;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface StoredFileRepository extends JpaRepository<StoredFile, String> {
  List<StoredFile> findByStatus(StoredFile.Status status, Pageable page);

  @Query(
      "select f.id from StoredFile f where f.status <> com.cms.core.file.StoredFile.Status.ACTIVE")
  List<String> unfinished();

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          "UPDATE cms.stored_file SET status='ACTIVE',media_type=:mime,byte_size=:size,sha256=:hash WHERE id=:id AND status='UPLOADING'",
      nativeQuery = true)
  int activate(String id, String mime, long size, String hash);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value = "UPDATE cms.stored_file SET status='FAILED' WHERE id=:id AND status='UPLOADING'",
      nativeQuery = true)
  int fail(String id);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          "UPDATE cms.stored_file SET status='ABANDONED' WHERE id=:id AND status IN ('UPLOADING','FAILED') AND created_at <= :cutoff",
      nativeQuery = true)
  int abandon(String id, LocalDateTime cutoff);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value = "UPDATE cms.stored_file SET status='DELETE_PENDING' WHERE id=:id AND status='ACTIVE'",
      nativeQuery = true)
  int markDelete(String id);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          "UPDATE cms.stored_file SET delete_attempts=delete_attempts+1 WHERE id=:id AND status IN ('ABANDONED','DELETE_PENDING')",
      nativeQuery = true)
  int attempted(String id);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          "DELETE FROM cms.stored_file WHERE id=:id AND status IN ('ABANDONED','DELETE_PENDING')",
      nativeQuery = true)
  int removeFinished(String id);
}
