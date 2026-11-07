package com.loco.notifications;

import com.loco.users.User;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.transaction.annotation.Transactional;

public interface NotificationRecipients extends JpaRepository<User, String> {
  interface Recipient {
    String getUserId();

    String getToken();
  }

  @Query(
      value = "SELECT id AS \"userId\", \"fcmToken\" AS token FROM users WHERE id=:id",
      nativeQuery = true)
  Optional<Recipient> one(String id);

  @Query(
      value =
          "SELECT u.id AS \"userId\", u.\"fcmToken\" AS token FROM map_members m JOIN users u ON u.id=m.\"userId\" WHERE m.\"mapId\"=:mapId AND m.\"userId\"<>:excludeUserId",
      nativeQuery = true)
  List<Recipient> members(String mapId, String excludeUserId);

  @Transactional
  @Modifying
  @Query(
      value =
          "UPDATE users SET \"fcmToken\"=NULL, \"updatedAt\"=:now WHERE id=:userId AND \"fcmToken\"=:token",
      nativeQuery = true)
  int clearIfUnchanged(String userId, String token, LocalDateTime now);
}
