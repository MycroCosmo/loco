package com.loco.users;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class UserResponses {
    private UserResponses() { }
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
    public record Me(String id, String email, String nickname, String profileImageUrl, Provider provider,
                     String createdAt, String updatedAt) { }
    public record Updated(String id, String email, String nickname, String profileImageUrl, String updatedAt) { }
    public record Image(String id, String email, String nickname, String profileImageUrl, Provider provider, String updatedAt) { }
    public static Image image(User user) { return new Image(user.getId(), user.getEmail(), user.getNickname(), user.getProfileImageUrl(), user.getProvider(), timestamp(user.getUpdatedAt())); }
    public static Me me(User user) {
        return new Me(user.getId(), user.getEmail(), user.getNickname(), user.getProfileImageUrl(), user.getProvider(),
                timestamp(user.getCreatedAt()), timestamp(user.getUpdatedAt()));
    }
    public static Updated updated(User user) {
        return new Updated(user.getId(), user.getEmail(), user.getNickname(), user.getProfileImageUrl(), timestamp(user.getUpdatedAt()));
    }
    public static String timestamp(LocalDateTime value) { return TIME.format(value); }
}
