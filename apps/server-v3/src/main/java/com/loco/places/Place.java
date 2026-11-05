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

  public String getId() {
    return id;
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
