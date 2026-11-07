package com.loco.reports;

import com.loco.users.User;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "reports")
@EntityListeners(AuditingEntityListener.class)
public class Report {
  @Id
  @UuidGenerator(style = UuidGenerator.Style.RANDOM)
  @Column(columnDefinition = "text")
  private String id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "\"reporterId\"")
  private User reporter;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(name = "\"targetType\"", columnDefinition = "\"ReportTargetType\"", nullable = false)
  private ReportTargetType targetType;

  @Column(name = "\"targetId\"", nullable = false, columnDefinition = "text")
  private String targetId;

  @Column(nullable = false, columnDefinition = "text")
  private String reason;

  @Column(columnDefinition = "text")
  private String detail;

  @CreatedDate
  @Column(name = "\"createdAt\"", updatable = false, columnDefinition = "timestamp(3)")
  private LocalDateTime createdAt;

  protected Report() {}

  static Report create(User reporter, ReportInputs input) {
    var r = new Report();
    r.reporter = reporter;
    r.targetType = input.targetType();
    r.targetId = input.targetId();
    r.reason = input.reason();
    r.detail = input.detail();
    return r;
  }
}
