package com.cms.core.permission;

import com.cms.core.account.*;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public class PermissionService {
  public record Grant(String roleId, String roleCode, Action grantedAction, boolean impliesRead) {}

  public record Explanation(
      String accountId,
      String permissionKey,
      Action action,
      boolean allowed,
      String reason,
      List<Grant> grants) {}

  private final AdminService admins;
  private final AdminAccountRepository accounts;
  private final PermissionCatalog catalog;

  public PermissionService(
      AdminService admins, AdminAccountRepository accounts, PermissionCatalog catalog) {
    this.admins = admins;
    this.accounts = accounts;
    this.catalog = catalog;
  }

  public AdminIdentity systemAdmin(AdminIdentity identity) {
    var current = admins.current(identity);
    if (current.mustChangePassword()) throw AdminProblem.passwordRequired();
    if (!current.systemAdmin()) throw AdminProblem.forbidden();
    return current;
  }

  public Explanation explain(AdminIdentity caller, String accountId, String key, Action action) {
    var current = admins.current(caller);
    if (current.mustChangePassword()) throw AdminProblem.passwordRequired();
    if (!current.id().equals(accountId)) systemAdmin(current);
    return evaluate(accountId, key, action);
  }

  public void require(AdminIdentity caller, String key, Action action) {
    if (!explain(caller, caller.id(), key, action).allowed()) throw AdminProblem.forbidden();
  }

  private Explanation evaluate(String id, String key, Action action) {
    catalog.validate(key, action);
    var account = accounts.findById(id).orElseThrow(() -> new AdminProblem(404, "Not found"));
    if (!account.identity().active())
      return new Explanation(id, key, action, false, "ACCOUNT_INACTIVE", List.of());
    if (account.identity().mustChangePassword())
      return new Explanation(id, key, action, false, "PASSWORD_CHANGE_REQUIRED", List.of());
    if (account.isSystemAdmin())
      return new Explanation(id, key, action, true, "SYSTEM_ADMIN", List.of());
    if (catalog.definition(key).systemOnly())
      return new Explanation(id, key, action, false, "SYSTEM_ADMIN_REQUIRED", List.of());
    var grants = new ArrayList<Grant>();
    for (var role : account.getRoles()) {
      var granted = role.getPermissions().get(key);
      if (granted != null && granted.includes(action))
        grants.add(
            new Grant(
                role.getId(),
                role.getCode(),
                granted,
                granted == Action.MANAGE && action == Action.READ));
    }
    grants.sort(Comparator.comparing(Grant::roleCode).thenComparing(Grant::roleId));
    return new Explanation(
        id,
        key,
        action,
        !grants.isEmpty(),
        grants.isEmpty() ? "NO_ROLE_GRANT" : "ROLE_GRANT",
        List.copyOf(grants));
  }
}
