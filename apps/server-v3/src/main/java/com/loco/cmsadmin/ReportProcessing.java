package com.loco.cmsadmin;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "loco_report_processing", schema = "cms")
public class ReportProcessing {
  public enum Status {
    RECEIVED,
    IN_REVIEW,
    RESOLVED,
    DISMISSED
  }

  @Id
  @Column(name = "report_id", columnDefinition = "text")
  private String reportId;

  @Enumerated(EnumType.STRING)
  @Column(columnDefinition = "text", nullable = false)
  private Status status;

  @Column(columnDefinition = "text")
  private String note;

  @Column(name = "handled_by", columnDefinition = "text")
  private String handledBy;

  @Column(name = "handled_at", columnDefinition = "timestamp(3)")
  private LocalDateTime handledAt;

  protected ReportProcessing() {}
}
