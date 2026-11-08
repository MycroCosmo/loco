package com.cms.core.menu;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "menu_node", schema = "cms")
public class MenuNode {
  public enum Kind {
    GROUP,
    PAGE
  }

  @Id @UuidGenerator String id;

  @Column(name = "parent_id")
  String parentId;

  @Column(name = "sort_order", nullable = false)
  int sortOrder;

  @Column(nullable = false)
  String title;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  Kind type;

  @Column(name = "route_key")
  String routeKey;

  @Column(name = "permission_key")
  String permissionKey;

  @Column(nullable = false)
  boolean visible;

  protected MenuNode() {}
}
