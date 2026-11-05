package com.loco.users;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "users")
@EntityListeners(AuditingEntityListener.class)
public class User {
  @Id
  @UuidGenerator(style = UuidGenerator.Style.RANDOM)
  @Column(columnDefinition = "text")
  private String id;

  @Column(nullable = false, columnDefinition = "text")
  private String email;

  @Column(nullable = false, columnDefinition = "text")
  private String nickname;

  @Column(columnDefinition = "text")
  private String password;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(nullable = false, columnDefinition = "\"Provider\"")
  private Provider provider;

  @Column(name = "\"providerId\"", columnDefinition = "text")
  private String providerId;

  @Column(name = "\"profileImageUrl\"", columnDefinition = "text")
  private String profileImageUrl;

  @Column(name = "\"fcmToken\"", columnDefinition = "text")
  private String fcmToken;

  @Column(name = "\"deletedAt\"", columnDefinition = "timestamp(3)")
  private LocalDateTime deletedAt;

  @CreatedDate
  @Column(
      name = "\"createdAt\"",
      nullable = false,
      updatable = false,
      columnDefinition = "timestamp(3)")
  private LocalDateTime createdAt;

  @LastModifiedDate
  @Column(name = "\"updatedAt\"", nullable = false, columnDefinition = "timestamp(3)")
  private LocalDateTime updatedAt;

  protected User() {}

  public static User local(String email, String password, String nickname) {
    var user = new User();
    user.email = email;
    user.password = password;
    user.nickname = nickname;
    user.provider = Provider.LOCAL;
    return user;
  }

  public String getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public String getNickname() {
    return nickname;
  }

  public String getProfileImageUrl() {
    return profileImageUrl;
  }

  public String getPassword() {
    return password;
  }

  public Provider getProvider() {
    return provider;
  }

  public LocalDateTime getDeletedAt() {
    return deletedAt;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void changePassword(String password) {
    this.password = password;
  }

  public void updateFcmToken(String token, LocalDateTime now) {
    fcmToken = token;
    updatedAt = now;
  }

  public void withdraw(LocalDateTime now) {
    deletedAt = now;
    fcmToken = null;
    updatedAt = now;
  }

  public void updateProfile(UserInputs.Update input, LocalDateTime now) {
    if (input.hasNickname()) nickname = input.nickname();
    if (input.hasImage()) profileImageUrl = input.image();
    if (input.hasNickname() || input.hasImage()) updatedAt = now;
  }

  public static User oauth(
      Provider provider, String providerId, String email, String nickname, String image) {
    var user = new User();
    user.provider = provider;
    user.providerId = providerId;
    user.email = email;
    user.nickname = nickname;
    user.profileImageUrl = image;
    return user;
  }

  public void updateOAuth(String email, String nickname, String image, LocalDateTime now) {
    this.email = email;
    this.nickname = nickname;
    if (image != null) this.profileImageUrl = image;
    // 같은 프로필로 로그인해도 Prisma update처럼 UPDATE를 발생시킨다.
    this.updatedAt = now;
  }
}
