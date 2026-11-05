package com.loco.maps;

import com.loco.users.User;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "maps")
@EntityListeners(AuditingEntityListener.class)
public class MapRoom {
  @Id
  @UuidGenerator(style = UuidGenerator.Style.RANDOM)
  @Column(columnDefinition = "text")
  private String id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "\"ownerId\"")
  private User owner;

  @Column(nullable = false, columnDefinition = "text")
  private String title;

  @Column(columnDefinition = "text")
  private String description;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(columnDefinition = "text[]")
  private String[] tags;

  @Column(name = "\"coverImageUrl\"", columnDefinition = "text")
  private String coverImageUrl;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(nullable = false, columnDefinition = "\"Visibility\"")
  private Visibility visibility;

  @Column(name = "\"inviteCode\"", columnDefinition = "text")
  private String inviteCode;

  @CreatedDate
  @Column(name = "\"createdAt\"", updatable = false, columnDefinition = "timestamp(3)")
  private LocalDateTime createdAt;

  @LastModifiedDate
  @Column(name = "\"updatedAt\"", columnDefinition = "timestamp(3)")
  private LocalDateTime updatedAt;

  protected MapRoom() {}

  public static MapRoom create(User owner, MapMutationInputs input, String code) {
    var room = new MapRoom();
    room.owner = owner;
    room.title = input.title();
    room.description = input.description();
    room.tags = input.tags() == null ? new String[0] : input.tags();
    room.visibility = input.visibility();
    room.inviteCode = code;
    return room;
  }

  public void update(MapMutationInputs input, String code, LocalDateTime now) {
    if (input.hasTitle()) title = input.title();
    if (input.hasDescription()) description = input.description();
    if (input.hasTags()) tags = input.tags();
    if (input.hasVisibility()) visibility = input.visibility();
    inviteCode = code;
    if (input.hasTitle() || input.hasDescription() || input.hasTags() || input.hasVisibility())
      updatedAt = now;
  }

  public String getId() {
    return id;
  }

  public void regenerateInviteCode(String code, LocalDateTime now) {
    inviteCode = code;
    updatedAt = now;
  }

  public User getOwner() {
    return owner;
  }

  public String getTitle() {
    return title;
  }

  public String getDescription() {
    return description;
  }

  public String[] getTags() {
    return tags;
  }

  public String getCoverImageUrl() {
    return coverImageUrl;
  }

  public Visibility getVisibility() {
    return visibility;
  }

  public String getInviteCode() {
    return inviteCode;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }
}
