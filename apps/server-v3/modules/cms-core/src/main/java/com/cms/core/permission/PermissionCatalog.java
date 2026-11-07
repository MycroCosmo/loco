package com.cms.core.permission;

import com.cms.core.account.AdminProblem;
import java.util.*;

public class PermissionCatalog {
  public record Definition(
      String key, String label, Set<Action> allowedActions, boolean systemOnly) {
    public Definition {
      allowedActions = Set.copyOf(allowedActions);
    }
  }

  public record Screen(String routeKey, String permissionKey) {}

  public interface Contribution {
    Collection<Definition> permissions();

    default Collection<Screen> screens() {
      return List.of();
    }
  }

  private final Map<String, Definition> definitions = new TreeMap<>();
  private final Map<String, Screen> screens = new TreeMap<>();

  public PermissionCatalog(List<Contribution> contributions) {
    add(new Definition("cms.accounts", "관리자 계정", Set.of(Action.READ, Action.MANAGE), true));
    add(new Definition("cms.roles", "역할·권한", Set.of(Action.READ, Action.MANAGE), true));
    add(new Definition("cms.menus", "메뉴 편집", Set.of(Action.READ, Action.MANAGE), false));
    for (var contribution : contributions) {
      for (var definition : contribution.permissions()) add(definition);
      for (var screen : contribution.screens())
        if (screen.routeKey() == null
            || screen.routeKey().isBlank()
            || screens.putIfAbsent(screen.routeKey(), screen) != null)
          throw new IllegalArgumentException("Invalid or duplicate CMS screen key");
    }
    for (var screen : screens.values()) {
      var definition = definition(screen.permissionKey());
      if (!definition.allowedActions().contains(Action.READ))
        throw new IllegalArgumentException("A screen requires READ");
    }
  }

  private void add(Definition definition) {
    if (definition.key() == null
        || definition.key().isBlank()
        || definition.label() == null
        || definition.label().isBlank()
        || definition.allowedActions().isEmpty()
        || (definition.allowedActions().contains(Action.MANAGE)
            && !definition.allowedActions().contains(Action.READ))
        || definitions.putIfAbsent(definition.key(), definition) != null)
      throw new IllegalArgumentException("Invalid or duplicate CMS permission key");
  }

  public Definition definition(String key) {
    var result = key == null ? null : definitions.get(key);
    if (result == null) throw new AdminProblem(400, "Unknown permission key");
    return result;
  }

  public void validate(String key, Action action) {
    if (action == null || !definition(key).allowedActions().contains(action))
      throw new AdminProblem(400, "Unsupported action");
  }

  public void screen(String routeKey, String permissionKey) {
    var screen = routeKey == null ? null : screens.get(routeKey);
    if (screen == null || !screen.permissionKey().equals(permissionKey))
      throw new AdminProblem(400, "Unknown screen or permission mismatch");
  }

  public List<Definition> definitions() {
    return List.copyOf(definitions.values());
  }
}
