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
@Table(name = "refresh_tokens")
@EntityListeners(AuditingEntityListener.class)
public class RefreshToken {
    @Id @UuidGenerator(style = UuidGenerator.Style.RANDOM)
    @Column(columnDefinition = "text")
    private String id;
    @Column(name = "\"userId\"", nullable = false, columnDefinition = "text")
    private String userId;
    @Column(name = "\"tokenHash\"", nullable = false, columnDefinition = "text")
    private String tokenHash;
    @Column(name = "\"expiresAt\"", nullable = false, columnDefinition = "timestamp(3)")
    private LocalDateTime expiresAt;
    @Column(name = "\"revokedAt\"", columnDefinition = "timestamp(3)")
    private LocalDateTime revokedAt;
    @CreatedDate @Column(name = "\"createdAt\"", nullable = false, updatable = false, columnDefinition = "timestamp(3)")
    private LocalDateTime createdAt;

    protected RefreshToken() { }
    public static RefreshToken issue(String userId, String hash, LocalDateTime expiresAt) {
        var token = new RefreshToken();
        token.userId = userId;
        token.tokenHash = hash;
        token.expiresAt = expiresAt;
        return token;
    }
    public String getId() { return id; }
    public String getUserId() { return userId; }
    public String getTokenHash() { return tokenHash; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public LocalDateTime getRevokedAt() { return revokedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
