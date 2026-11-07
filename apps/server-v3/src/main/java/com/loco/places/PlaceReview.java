package com.loco.places;

import com.loco.users.User;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(
    name = "place_reviews",
    uniqueConstraints = @UniqueConstraint(columnNames = {"placeId", "userId"}))
@EntityListeners(AuditingEntityListener.class)
public class PlaceReview {
  @Id
  @UuidGenerator(style = UuidGenerator.Style.RANDOM)
  @Column(columnDefinition = "text")
  private String id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "\"placeId\"")
  private Place place;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "\"userId\"")
  private User user;

  @Column(nullable = false)
  private int rating;

  @Column(nullable = false, columnDefinition = "text")
  private String content;

  @CreatedDate
  @Column(name = "\"createdAt\"", updatable = false, columnDefinition = "timestamp(3)")
  private LocalDateTime createdAt;

  @LastModifiedDate
  @Column(name = "\"updatedAt\"", columnDefinition = "timestamp(3)")
  private LocalDateTime updatedAt;

  protected PlaceReview() {}
}
