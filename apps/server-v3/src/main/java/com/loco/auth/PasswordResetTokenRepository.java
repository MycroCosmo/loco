package com.loco.auth;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, String> {
  @Transactional
  @Modifying
  @Query("delete from PasswordResetToken t where t.usedAt is not null or t.expiresAt < :now")
  int cleanup(LocalDateTime now);

  Optional<PasswordResetToken> findByTokenHash(String tokenHash);

  Optional<PasswordResetToken> findFirstByUserIdAndTokenHashAndUsedAtIsNullAndExpiresAtGreaterThan(
      String userId, String tokenHash, LocalDateTime now);

  @Transactional
  @Modifying
  @Query(
      "update PasswordResetToken t set t.usedAt=:now where t.userId=:userId and t.usedAt is null")
  int invalidate(String userId, LocalDateTime now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update PasswordResetToken t set t.usedAt=:now where t.id=:id and t.usedAt is null and t.expiresAt>:now")
  int consume(String id, LocalDateTime now);
}
