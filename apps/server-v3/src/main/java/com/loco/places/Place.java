package com.loco.places;

import com.loco.maps.MapRoom;
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
@Table(name = "places")
@EntityListeners(AuditingEntityListener.class)
public class Place {
  @Id
  @UuidGenerator(style = UuidGenerator.Style.RANDOM)
  @Column(columnDefinition = "text")
  private String id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "\"mapId\"")
  private MapRoom map;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "\"createdById\"")
  private User createdBy;

  @Column(nullable = false, columnDefinition = "text")
  private String name;

  @Column(nullable = false, columnDefinition = "text")
  private String address;

  @Column(name = "\"roadAddress\"", columnDefinition = "text")
  private String roadAddress;

  @Column(nullable = false)
  private Double latitude;

  @Column(nullable = false)
  private Double longitude;

  @Column(columnDefinition = "text")
  private String phone;

  @Column(columnDefinition = "text")
  private String category;

  @Column(columnDefinition = "text")
  private String memo;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(columnDefinition = "text[]")
  private String[] tags;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "\"imageUrls\"", columnDefinition = "text[]")
  private String[] imageUrls;

  @CreatedDate
  @Column(name = "\"createdAt\"", updatable = false, columnDefinition = "timestamp(3)")
  private LocalDateTime createdAt;

  @LastModifiedDate
  @Column(name = "\"updatedAt\"", columnDefinition = "timestamp(3)")
  private LocalDateTime updatedAt;

  protected Place() {}

  public static Place create(MapRoom map, User author, PlaceInputs input) {
    var place = new Place();
    place.map = map;
    place.createdBy = author;
    place.name = input.text("name");
    place.address = input.text("address");
    place.roadAddress = input.text("roadAddress");
    place.latitude = PrismaFloats.input(input.number("latitude"));
    place.longitude = PrismaFloats.input(input.number("longitude"));
    place.phone = input.text("phone");
    place.category = input.text("category");
    place.memo = input.text("memo");
    place.tags = input.tags() == null ? new String[0] : input.tags();
    place.imageUrls = new String[0];
    return place;
  }

  public String getId() {
    return id;
  }

  public void update(PlaceInputs input, LocalDateTime now) {
    if (input.body().isArray()) throw new IllegalArgumentException("Invalid Prisma update data");
    for (String key : new String[] {"name", "address", "latitude", "longitude", "tags"})
      if (input.has(key) && input.body().get(key).isNull())
        throw new IllegalArgumentException("Null Prisma update field");
    if (input.has("name")) name = input.text("name");
    if (input.has("address")) address = input.text("address");
    if (input.has("roadAddress")) roadAddress = input.text("roadAddress");
    if (input.has("latitude")) latitude = PrismaFloats.input(input.number("latitude"));
    if (input.has("longitude")) longitude = PrismaFloats.input(input.number("longitude"));
    if (input.has("phone")) phone = input.text("phone");
    if (input.has("category")) category = input.text("category");
    if (input.has("memo")) memo = input.text("memo");
    if (input.has("tags")) tags = input.tags();
    if (input.hasChanges()) updatedAt = now;
  }

  public MapRoom getMap() {
    return map;
  }

  public User getCreatedBy() {
    return createdBy;
  }

  public double getLatitude() {
    return latitude;
  }

  public double getLongitude() {
    return longitude;
  }

  public String[] getImageUrls() {
    return imageUrls;
  }
}
