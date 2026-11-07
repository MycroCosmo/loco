package com.cms.core.account;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "admin_account", schema = "cms")
@EntityListeners(AuditingEntityListener.class)
public class AdminAccount {
  @Id @UuidGenerator private String id;

  @Column(name = "login_id", nullable = false, unique = true)
  String loginId;

  @Column(name = "password_hash", nullable = false)
  String passwordHash;

  @Column(name = "display_name", nullable = false)
  String displayName;

  @Column(nullable = false)
  boolean active = true;

  @Column(name = "must_change_password", nullable = false)
  boolean mustChangePassword = true;

  @Column(name = "auth_version", nullable = false)
  long authVersion;

  @CreatedDate
  @Column(name = "created_at", nullable = false, updatable = false)
  LocalDateTime createdAt;

  @LastModifiedDate
  @Column(name = "updated_at", nullable = false)
  LocalDateTime updatedAt;

  @ManyToMany(fetch = FetchType.LAZY)
  @JoinTable(
      name = "admin_account_role",
      schema = "cms",
      joinColumns = @JoinColumn(name = "account_id"),
      inverseJoinColumns = @JoinColumn(name = "role_id"))
  Set<AdminRole> roles = new HashSet<>();

  protected AdminAccount() {}

  public String getId() {
    return id;
  }

  public boolean isSystemAdmin() {
    return roles.stream().anyMatch(r -> r.getCode().equals("SYSTEM_ADMIN"));
  }

  public AdminIdentity identity() {
    return new AdminIdentity(
        id, loginId, displayName, active, authVersion, mustChangePassword, isSystemAdmin());
  }
}
