package com.loco.auth;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {
  @Transactional
  @Modifying
  @Query("delete from RefreshToken t where t.revokedAt is not null or t.expiresAt < :cutoff")
  int cleanup(LocalDateTime cutoff);

  Optional<RefreshToken> findByTokenHash(String tokenHash);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update RefreshToken t set t.revokedAt = :now where t.id = :id and t.revokedAt is null")
  int consume(String id, LocalDateTime now);

  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update RefreshToken t set t.revokedAt = :now where t.tokenHash = :hash and t.revokedAt is null")
  int revokeByHash(String hash, LocalDateTime now);

  @Modifying
  @Query(
      "update RefreshToken t set t.revokedAt=:now where t.userId=:userId and t.revokedAt is null")
  int revokeForUser(String userId, LocalDateTime now);
}
