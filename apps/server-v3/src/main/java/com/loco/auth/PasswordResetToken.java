package com.loco.auth;

import java.time.LocalDateTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "password_reset_tokens")
@EntityListeners(AuditingEntityListener.class)
public class PasswordResetToken {
    @Id @UuidGenerator(style = UuidGenerator.Style.RANDOM)
    @Column(columnDefinition = "text")
    private String id;
    @Column(name = "\"userId\"", nullable = false, columnDefinition = "text")
    private String userId;
    @Column(name = "\"tokenHash\"", nullable = false, columnDefinition = "text")
    private String tokenHash;
    @Column(name = "\"expiresAt\"", nullable = false, columnDefinition = "timestamp(3)")
    private LocalDateTime expiresAt;
    @Column(name = "\"usedAt\"", columnDefinition = "timestamp(3)")
    private LocalDateTime usedAt;
    @CreatedDate @Column(name = "\"createdAt\"", nullable = false, updatable = false, columnDefinition = "timestamp(3)")
    private LocalDateTime createdAt;

    protected PasswordResetToken() { }
    public static PasswordResetToken issue(String userId, String hash, LocalDateTime expiresAt) {
        var token = new PasswordResetToken();
        token.userId = userId;
        token.tokenHash = hash;
        token.expiresAt = expiresAt;
        return token;
    }
    public String getId() { return id; }
    public String getUserId() { return userId; }
    public String getTokenHash() { return tokenHash; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public LocalDateTime getUsedAt() { return usedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
