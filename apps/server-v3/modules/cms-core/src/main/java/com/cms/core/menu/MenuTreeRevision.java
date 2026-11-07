package com.cms.core.menu;

import jakarta.persistence.*;

@Entity
@Table(name = "menu_tree_revision", schema = "cms")
public class MenuTreeRevision {
  @Id int id;
  long revision;

  protected MenuTreeRevision() {}
}
