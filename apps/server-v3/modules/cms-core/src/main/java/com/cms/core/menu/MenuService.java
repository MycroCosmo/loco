package com.cms.core.menu;

import com.cms.core.account.*;
import com.cms.core.history.HistoryWriter;
import com.cms.core.permission.*;
import java.util.*;
import org.springframework.transaction.annotation.*;

public class MenuService {
  public record Node(
      String id,
      String parentId,
      int sortOrder,
      String title,
      MenuNode.Kind type,
      String routeKey,
      String permissionKey,
      boolean visible) {}

  public record Tree(long revision, List<Node> nodes) {}

  public record Draft(
      String title,
      MenuNode.Kind type,
      String parentId,
      String beforeSiblingId,
      String routeKey,
      String permissionKey,
      Boolean visible,
      Long revision) {}

  public record Details(
      String title, String routeKey, String permissionKey, Boolean visible, Long revision) {}

  public record Position(String parentId, String beforeSiblingId, Long revision) {}

  public record Preview(
      String id,
      String oldParentId,
      String newParentId,
      String beforeSiblingId,
      long revision,
      boolean permissionsChanged,
      String message) {}

  private final MenuRepository menus;
  private final MenuTreeRepository tree;
  private final PermissionService access;
  private final PermissionCatalog catalog;
  private final HistoryWriter history;

  public MenuService(
      MenuRepository menus,
      MenuTreeRepository tree,
      PermissionService access,
      PermissionCatalog catalog,
      HistoryWriter history) {
    this.menus = menus;
    this.tree = tree;
    this.access = access;
    this.catalog = catalog;
    this.history = history;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Tree list(AdminIdentity actor) {
    access.require(actor, "cms.menus", Action.READ);
    return result(meta(), all().values());
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Tree navigation(AdminIdentity actor) {
    // Also validates active account/password state when the tree is empty.
    access.explain(actor, actor.id(), "cms.menus", Action.READ);
    var meta = meta();
    var nodes = all();
    var allowed = new HashSet<String>();
    for (var node : nodes.values()) {
      if (node.type != MenuNode.Kind.PAGE || hidden(node, nodes)) continue;
      if (access.explain(actor, actor.id(), node.permissionKey, Action.READ).allowed())
        for (var current = node; current != null; current = nodes.get(current.parentId))
          allowed.add(current.id);
    }
    return result(meta, nodes.values().stream().filter(n -> allowed.contains(n.id)).toList());
  }

  @Transactional
  public Tree create(AdminIdentity actor, Draft draft) {
    var meta = locked(actor, draft.revision());
    var nodes = all();
    validateDetails(
        draft.title(), draft.type(), draft.routeKey(), draft.permissionKey(), draft.visible());
    parent(draft.parentId(), nodes);
    var node = new MenuNode();
    node.title = draft.title().strip();
    node.type = draft.type();
    node.routeKey = draft.routeKey();
    node.permissionKey = draft.permissionKey();
    node.visible = draft.visible();
    node.parentId = draft.parentId();
    menus.saveAndFlush(node);
    nodes.put(node.id, node);
    reorder(nodes, node, draft.beforeSiblingId());
    meta.revision++;
    menus.flush();
    history.record(actor.id(), "CREATE", "menu", node.id, null, snapshot(meta, nodes, node));
    return result(meta, nodes.values());
  }

  @Transactional
  public Tree update(AdminIdentity actor, String id, Details details) {
    var meta = locked(actor, details.revision());
    var nodes = all();
    var node = node(id, nodes);
    validateDetails(
        details.title(), node.type, details.routeKey(), details.permissionKey(), details.visible());
    var before = snapshot(meta, nodes, node);
    node.title = details.title().strip();
    node.visible = details.visible();
    node.routeKey = details.routeKey();
    node.permissionKey = details.permissionKey();
    meta.revision++;
    menus.flush();
    history.record(actor.id(), "UPDATE", "menu", id, before, snapshot(meta, nodes, node));
    return result(meta, nodes.values());
  }

  @Transactional
  public Tree move(AdminIdentity actor, String id, Position position) {
    var meta = locked(actor, position.revision());
    var nodes = all();
    var node = node(id, nodes);
    validatePosition(node, position, nodes);
    var before = snapshot(meta, nodes, node);
    String oldParent = node.parentId;
    node.parentId = position.parentId();
    normalize(siblings(nodes, oldParent).stream().filter(n -> n != node).toList());
    reorder(nodes, node, position.beforeSiblingId());
    meta.revision++;
    menus.flush();
    history.record(actor.id(), "MOVE", "menu", id, before, snapshot(meta, nodes, node));
    return result(meta, nodes.values());
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Preview preview(AdminIdentity actor, String id, Position position) {
    access.require(actor, "cms.menus", Action.MANAGE);
    var meta = meta();
    revision(meta, position.revision());
    var nodes = all();
    var node = node(id, nodes);
    validatePosition(node, position, nodes);
    return new Preview(
        id,
        node.parentId,
        position.parentId(),
        position.beforeSiblingId(),
        meta.revision,
        false,
        "권한 변화 없음");
  }

  @Transactional
  public Tree delete(AdminIdentity actor, String id, Long revision) {
    var meta = locked(actor, revision);
    var nodes = all();
    var node = node(id, nodes);
    if (nodes.values().stream().anyMatch(n -> id.equals(n.parentId)))
      throw new AdminProblem(409, "Menu has children");
    var before = snapshot(meta, nodes, node);
    menus.delete(node);
    nodes.remove(id);
    normalize(siblings(nodes, node.parentId));
    meta.revision++;
    menus.flush();
    history.record(actor.id(), "DELETE", "menu", id, before, null);
    return result(meta, nodes.values());
  }

  private MenuTreeRevision locked(AdminIdentity actor, Long expected) {
    var meta = tree.lock();
    access.require(actor, "cms.menus", Action.MANAGE);
    revision(meta, expected);
    return meta;
  }

  private MenuTreeRevision meta() {
    return tree.findById(1).orElseThrow();
  }

  private void revision(MenuTreeRevision meta, Long expected) {
    if (expected == null) throw new AdminProblem(400, "Tree revision required");
    if (meta.revision != expected)
      throw new AdminProblem(409, "Stale tree revision; reload the tree", "STALE_REVISION");
  }

  private Map<String, MenuNode> all() {
    var result = new LinkedHashMap<String, MenuNode>();
    for (var node : menus.findAll()) result.put(node.id, node);
    return result;
  }

  private MenuNode node(String id, Map<String, MenuNode> nodes) {
    var node = nodes.get(id);
    if (node == null) throw new AdminProblem(404, "Menu not found");
    return node;
  }

  private void parent(String id, Map<String, MenuNode> nodes) {
    if (id != null && node(id, nodes).type != MenuNode.Kind.GROUP)
      throw new AdminProblem(400, "Parent must be a group");
  }

  private void validatePosition(MenuNode node, Position position, Map<String, MenuNode> nodes) {
    parent(position.parentId(), nodes);
    var visited = new HashSet<String>();
    for (String id = position.parentId(); id != null; id = node(id, nodes).parentId) {
      if (id.equals(node.id) || !visited.add(id)) throw new AdminProblem(409, "Menu cycle");
    }
    if (position.beforeSiblingId() != null) {
      var sibling = node(position.beforeSiblingId(), nodes);
      if (sibling == node || !Objects.equals(sibling.parentId, position.parentId()))
        throw new AdminProblem(400, "Invalid destination sibling");
    }
  }

  private void validateDetails(
      String title, MenuNode.Kind type, String route, String key, Boolean visible) {
    if (title == null || title.isBlank() || type == null || visible == null)
      throw new AdminProblem(400, "Invalid menu");
    if (type == MenuNode.Kind.GROUP) {
      if (route != null || key != null)
        throw new AdminProblem(400, "A group has no screen permission");
    } else catalog.screen(route, key);
  }

  private List<MenuNode> siblings(Map<String, MenuNode> nodes, String parent) {
    return nodes.values().stream()
        .filter(n -> Objects.equals(n.parentId, parent))
        .sorted(Comparator.comparingInt((MenuNode n) -> n.sortOrder).thenComparing(n -> n.id))
        .toList();
  }

  private void reorder(Map<String, MenuNode> nodes, MenuNode node, String beforeId) {
    var siblings = new ArrayList<>(siblings(nodes, node.parentId));
    siblings.remove(node);
    int index =
        beforeId == null
            ? siblings.size()
            : java.util.stream.IntStream.range(0, siblings.size())
                .filter(i -> siblings.get(i).id.equals(beforeId))
                .findFirst()
                .orElseThrow(() -> new AdminProblem(400, "Invalid destination sibling"));
    siblings.add(index, node);
    normalize(siblings);
  }

  private void normalize(List<MenuNode> siblings) {
    for (int i = 0; i < siblings.size(); i++) siblings.get(i).sortOrder = i;
  }

  private boolean hidden(MenuNode node, Map<String, MenuNode> nodes) {
    var visited = new HashSet<String>();
    for (var current = node; current != null; current = nodes.get(current.parentId)) {
      if (!current.visible || !visited.add(current.id)) return true;
    }
    return false;
  }

  private Node view(MenuNode n) {
    return new Node(
        n.id, n.parentId, n.sortOrder, n.title, n.type, n.routeKey, n.permissionKey, n.visible);
  }

  private Tree result(MenuTreeRevision meta, Collection<MenuNode> nodes) {
    return new Tree(
        meta.revision,
        nodes.stream()
            .sorted(
                Comparator.comparing(
                        (MenuNode n) -> n.parentId,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparingInt(n -> n.sortOrder)
                    .thenComparing(n -> n.id))
            .map(this::view)
            .toList());
  }

  private Map<String, Object> snapshot(
      MenuTreeRevision meta, Map<String, MenuNode> nodes, MenuNode node) {
    var order = new TreeMap<String, Integer>();
    for (var n : nodes.values()) order.put(n.id, n.sortOrder);
    var fields = new LinkedHashMap<String, Object>();
    fields.put("id", node.id);
    fields.put("parentId", node.parentId);
    fields.put("sortOrder", node.sortOrder);
    fields.put("title", node.title);
    fields.put("type", node.type.name());
    fields.put("routeKey", node.routeKey);
    fields.put("permissionKey", node.permissionKey);
    fields.put("visible", node.visible);
    return Map.of("node", fields, "sortOrders", order, "treeRevision", meta.revision);
  }
}
