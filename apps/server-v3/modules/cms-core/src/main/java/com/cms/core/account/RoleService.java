package com.cms.core.account;

import com.cms.core.history.HistoryWriter;
import com.cms.core.permission.*;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;

public class RoleService {
  public record RoleView(
      String id,
      String code,
      String name,
      long version,
      boolean systemProtected,
      Map<String, Action> permissions) {}

  public record Template(String code, Action action) {}

  private final AdminRoleRepository roles;
  private final AdminAccountRepository accounts;
  private final AdministrationStateRepository state;
  private final PermissionService access;
  private final PermissionCatalog catalog;
  private final HistoryWriter history;

  public RoleService(
      AdminRoleRepository roles,
      AdminAccountRepository accounts,
      AdministrationStateRepository state,
      PermissionService access,
      PermissionCatalog catalog,
      HistoryWriter history) {
    this.roles = roles;
    this.accounts = accounts;
    this.state = state;
    this.access = access;
    this.catalog = catalog;
    this.history = history;
  }

  public List<Template> templates() {
    return List.of(new Template("READ_ONLY", Action.READ), new Template("MANAGER", Action.MANAGE));
  }

  @Transactional(readOnly = true)
  public List<RoleView> list(AdminIdentity actor) {
    access.systemAdmin(actor);
    return roles.findAll().stream()
        .sorted(Comparator.comparing(AdminRole::getCode))
        .map(this::view)
        .toList();
  }

  @Transactional(readOnly = true)
  public RoleView get(AdminIdentity actor, String id) {
    access.systemAdmin(actor);
    return view(role(id));
  }

  @Transactional
  public RoleView create(
      AdminIdentity actor, String code, String name, String template, Set<String> keys) {
    state.lock();
    access.systemAdmin(actor);
    required(code);
    required(name);
    String normalized = code.strip().toUpperCase(Locale.ROOT);
    if (roles.findByCode(normalized).isPresent())
      throw new AdminProblem(409, "Role already exists");
    var grants = new TreeMap<String, Action>();
    if (template != null) {
      var action =
          templates().stream()
              .filter(t -> t.code().equals(template))
              .findFirst()
              .orElseThrow(() -> new AdminProblem(400, "Unknown role template"))
              .action();
      if (keys == null || keys.stream().anyMatch(Objects::isNull))
        throw new AdminProblem(400, "Permission keys required");
      for (var key : keys) grants.put(key, action);
    } else if (keys != null && !keys.isEmpty()) throw new AdminProblem(400, "Template required");
    validate(grants);
    var role = new AdminRole(normalized, name.strip());
    role.replacePermissions(grants);
    roles.saveAndFlush(role);
    history.record(actor.id(), "CREATE", "role", role.getId(), null, snapshot(role));
    return view(role);
  }

  @Transactional
  public RoleView rename(AdminIdentity actor, String id, String name, Long version) {
    state.lock();
    access.systemAdmin(actor);
    var role = editable(id, version);
    required(name);
    var before = snapshot(role);
    role.rename(name.strip());
    roles.flush();
    history.record(actor.id(), "RENAME", "role", id, before, snapshot(role));
    return view(role);
  }

  @Transactional
  public RoleView permissions(
      AdminIdentity actor, String id, Map<String, Action> grants, Long version) {
    state.lock();
    access.systemAdmin(actor);
    var role = editable(id, version);
    validate(grants);
    var before = snapshot(role);
    role.replacePermissions(grants);
    roles.flush();
    history.record(actor.id(), "PERMISSIONS_CHANGE", "role", id, before, snapshot(role));
    return view(role);
  }

  @Transactional
  public void delete(AdminIdentity actor, String id, Long version) {
    state.lock();
    access.systemAdmin(actor);
    var role = editable(id, version);
    if (accounts.assignedToRole(id) > 0) throw new AdminProblem(409, "Role is assigned");
    var before = snapshot(role);
    roles.delete(role);
    roles.flush();
    history.record(actor.id(), "DELETE", "role", id, before, null);
  }

  @Transactional
  public AdminIdentity assign(
      AdminIdentity actor, String id, Set<String> ids, Long expectedVersion) {
    state.lock();
    access.systemAdmin(actor);
    var account = accounts.findById(id).orElseThrow(() -> new AdminProblem(404, "Not found"));
    if (ids == null || ids.stream().anyMatch(Objects::isNull) || expectedVersion == null)
      throw new AdminProblem(400, "Roles and authVersion required");
    if (account.identity().authVersion() != expectedVersion)
      throw new AdminProblem(409, "Stale account version");
    var assigned = new HashSet<AdminRole>();
    for (var roleId : ids) assigned.add(role(roleId));
    boolean staysSystem = assigned.stream().anyMatch(r -> r.getCode().equals("SYSTEM_ADMIN"));
    if (account.active
        && account.isSystemAdmin()
        && !staysSystem
        && accounts.activeSystemAdmins() <= 1)
      throw new AdminProblem(409, "Last active system administrator");
    var before =
        Map.<String, Object>of(
            "roles", account.roles.stream().map(AdminRole::getCode).sorted().toList());
    account.roles.clear();
    account.roles.addAll(assigned);
    account.authVersion++;
    accounts.flush();
    history.record(
        actor.id(),
        "ROLES_CHANGE",
        "admin-account",
        id,
        before,
        Map.of("roles", assigned.stream().map(AdminRole::getCode).sorted().toList()));
    return account.identity();
  }

  private void validate(Map<String, Action> grants) {
    if (grants == null) throw new AdminProblem(400, "Permissions required");
    grants.forEach(
        (key, action) -> {
          catalog.validate(key, action);
          if (catalog.definition(key).systemOnly())
            throw new AdminProblem(400, "System-only permission");
        });
  }

  private AdminRole editable(String id, Long version) {
    var role = role(id);
    if (role.getCode().equals("SYSTEM_ADMIN")) throw new AdminProblem(409, "Protected role");
    if (version == null) throw new AdminProblem(400, "Role version required");
    if (role.getVersion() != version) throw new AdminProblem(409, "Stale role version");
    return role;
  }

  private AdminRole role(String id) {
    return roles.findById(id).orElseThrow(() -> new AdminProblem(404, "Not found"));
  }

  private void required(String value) {
    if (value == null || value.isBlank()) throw new AdminProblem(400, "Invalid input");
  }

  private RoleView view(AdminRole role) {
    return new RoleView(
        role.getId(),
        role.getCode(),
        role.getName(),
        role.getVersion(),
        role.getCode().equals("SYSTEM_ADMIN"),
        new TreeMap<>(role.getPermissions()));
  }

  private Map<String, Object> snapshot(AdminRole role) {
    return Map.of(
        "code",
        role.getCode(),
        "name",
        role.getName(),
        "permissions",
        new TreeMap<>(role.getPermissions()));
  }
}
