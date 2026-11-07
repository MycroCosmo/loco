package com.cms.core.history;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "change_history", schema = "cms")
public class ChangeHistory {
  @Id @UuidGenerator private String id;

  @Column(name = "actor_id")
  private String actorId;

  @Column(nullable = false)
  private String action;

  @Column(name = "resource_type", nullable = false)
  private String resourceType;

  @Column(name = "resource_id", nullable = false)
  private String resourceId;

  @Column(name = "occurred_at", nullable = false)
  private LocalDateTime occurredAt;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "before_data")
  private Map<String, Object> beforeData;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "after_data")
  private Map<String, Object> afterData;

  @Column(name = "payload_version", nullable = false)
  private int payloadVersion = 1;

  protected ChangeHistory() {}

  public ChangeHistory(
      String actorId,
      String action,
      String resourceType,
      String resourceId,
      LocalDateTime now,
      Map<String, Object> before,
      Map<String, Object> after) {
    this.actorId = actorId;
    this.action = action;
    this.resourceType = resourceType;
    this.resourceId = resourceId;
    this.occurredAt = now;
    this.beforeData = before;
    this.afterData = after;
  }
}
