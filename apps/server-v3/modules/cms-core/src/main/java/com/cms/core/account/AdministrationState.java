package com.cms.core.account;

import jakarta.persistence.*;

@Entity
@Table(name = "administration_state", schema = "cms")
public class AdministrationState {
  @Id int id;

  @Column(name = "bootstrap_completed", nullable = false)
  boolean bootstrapCompleted;

  protected AdministrationState() {}
}
