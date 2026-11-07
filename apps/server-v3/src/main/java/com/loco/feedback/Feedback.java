package com.loco.feedback;

import com.loco.users.User;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "feedbacks")
@EntityListeners(AuditingEntityListener.class)
public class Feedback {
  @Id
  @UuidGenerator(style = UuidGenerator.Style.RANDOM)
  @Column(columnDefinition = "text")
  private String id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "\"userId\"")
  private User user;

  @Column(nullable = false, columnDefinition = "text")
  private String content;

  @CreatedDate
  @Column(name = "\"createdAt\"", updatable = false, columnDefinition = "timestamp(3)")
  private LocalDateTime createdAt;

  protected Feedback() {}

  static Feedback create(User user, String content) {
    var f = new Feedback();
    f.user = user;
    f.content = content;
    return f;
  }

  String id() {
    return id;
  }
}
