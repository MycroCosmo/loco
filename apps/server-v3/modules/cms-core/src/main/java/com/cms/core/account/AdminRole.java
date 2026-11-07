package com.cms.core.account;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "admin_role", schema = "cms")
public class AdminRole {
  @Id @UuidGenerator private String id;

  @Column(nullable = false, unique = true)
  private String code;

  @Column(nullable = false)
  private String name;

  protected AdminRole() {}

  public String getCode() {
    return code;
  }
}
