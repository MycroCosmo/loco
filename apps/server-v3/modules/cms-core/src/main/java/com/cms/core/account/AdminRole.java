package com.cms.core.account;

import com.cms.core.permission.Action;
import jakarta.persistence.*;
import java.util.*;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "admin_role", schema = "cms")
public class AdminRole {
  @Id @UuidGenerator private String id;

  @Column(nullable = false, unique = true)
  private String code;

  @Column(nullable = false)
  private String name;

  protected AdminRole() {}

  @Version private long version;

  @ElementCollection
  @CollectionTable(
      name = "role_permission",
      schema = "cms",
      joinColumns = @JoinColumn(name = "role_id"))
  @MapKeyColumn(name = "permission_key")
  @Column(name = "action")
  @Enumerated(EnumType.STRING)
  private Map<String, Action> permissions = new HashMap<>();

  public AdminRole(String code, String name) {
    this.code = code;
    this.name = name;
  }

  public String getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public long getVersion() {
    return version;
  }

  public Map<String, Action> getPermissions() {
    return Collections.unmodifiableMap(permissions);
  }

  public void rename(String name) {
    this.name = name;
  }

  public void replacePermissions(Map<String, Action> grants) {
    permissions.clear();
    permissions.putAll(grants);
  }

  public String getCode() {
    return code;
  }
}
