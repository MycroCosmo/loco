package com.cms.core.web;

import com.cms.core.account.*;
import com.cms.core.menu.MenuService;
import com.cms.core.permission.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/api/core")
public class GovernanceController {
  private final RoleService roles;
  private final PermissionService access;
  private final PermissionCatalog catalog;
  private final MenuService menus;

  public GovernanceController(
      RoleService roles, PermissionService access, PermissionCatalog catalog, MenuService menus) {
    this.roles = roles;
    this.access = access;
    this.catalog = catalog;
    this.menus = menus;
  }

  public record CreateRole(String code, String name, String template, Set<String> permissionKeys) {}

  public record RenameRole(String name, Long version) {}

  public record Permissions(Map<String, Action> permissions, Long version) {}

  public record Assignment(Set<String> roleIds, Long authVersion) {}

  @GetMapping("/roles")
  public List<RoleService.RoleView> roles(@AuthenticationPrincipal AdminIdentity actor) {
    return roles.list(actor);
  }

  @GetMapping("/roles/{id}")
  public RoleService.RoleView role(
      @AuthenticationPrincipal AdminIdentity actor, @PathVariable String id) {
    return roles.get(actor, id);
  }

  @PostMapping("/roles")
  @ResponseStatus(HttpStatus.CREATED)
  public RoleService.RoleView create(
      @AuthenticationPrincipal AdminIdentity actor, @RequestBody CreateRole input) {
    return roles.create(
        actor, input.code(), input.name(), input.template(), input.permissionKeys());
  }

  @PutMapping("/roles/{id}")
  public RoleService.RoleView rename(
      @AuthenticationPrincipal AdminIdentity actor,
      @PathVariable String id,
      @RequestBody RenameRole input) {
    return roles.rename(actor, id, input.name(), input.version());
  }

  @PutMapping("/roles/{id}/permissions")
  public RoleService.RoleView permissions(
      @AuthenticationPrincipal AdminIdentity actor,
      @PathVariable String id,
      @RequestBody Permissions input) {
    return roles.permissions(actor, id, input.permissions(), input.version());
  }

  @DeleteMapping("/roles/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @AuthenticationPrincipal AdminIdentity actor,
      @PathVariable String id,
      @RequestParam Long version) {
    roles.delete(actor, id, version);
  }

  @PutMapping("/accounts/{id}/roles")
  public AdminIdentity assign(
      @AuthenticationPrincipal AdminIdentity actor,
      @PathVariable String id,
      @RequestBody Assignment input) {
    return roles.assign(actor, id, input.roleIds(), input.authVersion());
  }

  @GetMapping("/role-templates")
  public List<RoleService.Template> templates(@AuthenticationPrincipal AdminIdentity actor) {
    access.systemAdmin(actor);
    return roles.templates();
  }

  @GetMapping("/permissions")
  public List<PermissionCatalog.Definition> permissions(
      @AuthenticationPrincipal AdminIdentity actor) {
    access.systemAdmin(actor);
    return catalog.definitions();
  }

  @GetMapping("/permissions/explain")
  public PermissionService.Explanation explain(
      @AuthenticationPrincipal AdminIdentity actor,
      @RequestParam String permissionKey,
      @RequestParam Action action,
      @RequestParam(required = false) String accountId) {
    return access.explain(actor, accountId == null ? actor.id() : accountId, permissionKey, action);
  }

  @GetMapping("/menus")
  public MenuService.Tree menus(@AuthenticationPrincipal AdminIdentity actor) {
    return menus.list(actor);
  }

  @GetMapping("/navigation")
  public MenuService.Tree navigation(@AuthenticationPrincipal AdminIdentity actor) {
    return menus.navigation(actor);
  }

  @PostMapping("/menus")
  @ResponseStatus(HttpStatus.CREATED)
  public MenuService.Tree createMenu(
      @AuthenticationPrincipal AdminIdentity actor, @RequestBody MenuService.Draft input) {
    return menus.create(actor, input);
  }

  @PutMapping("/menus/{id}")
  public MenuService.Tree editMenu(
      @AuthenticationPrincipal AdminIdentity actor,
      @PathVariable String id,
      @RequestBody MenuService.Details input) {
    return menus.update(actor, id, input);
  }

  @PostMapping("/menus/{id}/move-preview")
  public MenuService.Preview preview(
      @AuthenticationPrincipal AdminIdentity actor,
      @PathVariable String id,
      @RequestBody MenuService.Position input) {
    return menus.preview(actor, id, input);
  }

  @PutMapping("/menus/{id}/position")
  public MenuService.Tree move(
      @AuthenticationPrincipal AdminIdentity actor,
      @PathVariable String id,
      @RequestBody MenuService.Position input) {
    return menus.move(actor, id, input);
  }

  @DeleteMapping("/menus/{id}")
  public MenuService.Tree deleteMenu(
      @AuthenticationPrincipal AdminIdentity actor,
      @PathVariable String id,
      @RequestParam Long revision) {
    return menus.delete(actor, id, revision);
  }
}
