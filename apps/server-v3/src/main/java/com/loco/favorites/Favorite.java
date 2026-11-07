package com.loco.favorites;

import com.loco.maps.MapRoom;
import com.loco.users.User;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "favorites", uniqueConstraints = @UniqueConstraint(columnNames = {"userId", "mapId"}))
@EntityListeners(AuditingEntityListener.class)
public class Favorite {
  @Id
  @UuidGenerator(style = UuidGenerator.Style.RANDOM)
  @Column(columnDefinition = "text")
  private String id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "\"userId\"")
  private User user;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "\"mapId\"")
  private MapRoom map;

  @CreatedDate
  @Column(name = "\"createdAt\"", updatable = false, columnDefinition = "timestamp(3)")
  private LocalDateTime createdAt;

  protected Favorite() {}

  static Favorite create(User user, MapRoom map) {
    var f = new Favorite();
    f.user = user;
    f.map = map;
    return f;
  }

  String userId() {
    return user.getId();
  }

  FavoriteResponses.Created response() {
    return new FavoriteResponses.Created(
        id, user.getId(), map.getId(), com.loco.users.UserResponses.timestamp(createdAt));
  }
}
