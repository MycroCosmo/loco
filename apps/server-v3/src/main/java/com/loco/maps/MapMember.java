package com.loco.maps;

import java.time.LocalDateTime;
import com.loco.users.User;
import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity @Table(name="map_members") @EntityListeners(AuditingEntityListener.class)
public class MapMember {
    @Id @UuidGenerator(style=UuidGenerator.Style.RANDOM) @Column(columnDefinition="text") private String id;
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="\"mapId\"") private MapRoom map;
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="\"userId\"") private User user;
    @CreatedDate @Column(name="\"createdAt\"",updatable=false,columnDefinition="timestamp(3)") private LocalDateTime createdAt;
    protected MapMember() { }
    public String getId() { return id; }
    public MapRoom getMap() { return map; }
    public User getUser() { return user; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
