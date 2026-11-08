package com.cms.core.history;

import com.cms.core.account.AdminProblem;
import com.cms.core.permission.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

public final class HistoryContractRegistry {
  public static final Set<String> CORE = Set.of("admin-account", "role", "menu", "file");
  private final Map<String, Map<Integer, HistoryContract>> contracts = new TreeMap<>();
  private final Map<String, HistoryContract> current = new TreeMap<>();
  private final ObjectMapper json = new ObjectMapper();

  public HistoryContractRegistry(
      List<HistoryContract.Contribution> contributions, PermissionCatalog permissions) {
    var owners = new HashSet<String>();
    for (var contribution : contributions) {
      var owned = new HashSet<String>();
      for (var c : contribution.contracts()) {
        String name = c.resourceType();
        if (name == null
            || !name.matches("[a-z][a-z0-9-]*\\.[a-z][a-z0-9.-]*")
            || name.startsWith("cms.")
            || CORE.contains(name)
            || c.version() < 1
            || c.actions().isEmpty()
            || c.actions().stream().anyMatch(a -> !a.matches("[A-Z][A-Z_]{0,63}"))
            || c.fields().isEmpty()
            || c.fields().size() > 32) throw invalid();
        if (!owned.contains(name) && !owners.add(name)) throw invalid();
        owned.add(name);
        if (c.readPermission() != null) permissions.validate(c.readPermission(), Action.READ);
        for (String key : c.fields().keySet()) {
          String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
          if (!key.matches("[a-zA-Z][a-zA-Z0-9_]{0,63}")
              || List.of(
                      "password",
                      "hash",
                      "session",
                      "csrf",
                      "jwt",
                      "fcm",
                      "token",
                      "providerid",
                      "filebody",
                      "filecontent",
                      "signedurl",
                      "presigned",
                      "secret",
                      "credential")
                  .stream()
                  .anyMatch(normalized::contains)) throw invalid();
        }
        if (contracts.computeIfAbsent(name, k -> new TreeMap<>()).putIfAbsent(c.version(), c)
            != null) throw invalid();
        if (c.current() && current.putIfAbsent(name, c) != null) throw invalid();
      }
    }
    if (!current.keySet().equals(contracts.keySet())) throw invalid();
    for (var entry : contracts.entrySet())
      if (entry.getValue().values().stream()
          .anyMatch(
              c ->
                  !Objects.equals(
                      c.readPermission(), current.get(entry.getKey()).readPermission())))
        throw invalid();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid history contract");
  }

  public Set<String> resources() {
    var names = new TreeSet<>(CORE);
    names.addAll(contracts.keySet());
    return Collections.unmodifiableSet(names);
  }

  public String readPermission(String resource) {
    return CORE.contains(resource)
        ? null
        : require(resource, currentVersion(resource)).readPermission();
  }

  public int currentVersion(String resource) {
    if (CORE.contains(resource)) return 1;
    var c = current.get(resource);
    if (c == null) throw invalid();
    return c.version();
  }

  public HistoryContract require(String resource, int version) {
    var versions = contracts.get(resource);
    if (versions == null || !versions.containsKey(version)) throw invalid();
    return versions.get(version);
  }

  public record Payload(Map<String, Object> before, Map<String, Object> after, int version) {}

  public Payload writing(
      String resource, String action, Map<String, Object> before, Map<String, Object> after) {
    int version = currentVersion(resource);
    return checked(resource, version, action, before, after, false);
  }

  public ChangeHistory.View reading(ChangeHistory.View view) {
    try {
      var p =
          checked(
              view.resourceType(),
              view.payloadVersion(),
              view.action(),
              view.before(),
              view.after(),
              true);
      return new ChangeHistory.View(
          view.id(),
          view.actorId(),
          view.action(),
          view.resourceType(),
          view.resourceId(),
          view.occurredAt(),
          p.before(),
          p.after(),
          p.version());
    } catch (RuntimeException invalid) {
      org.slf4j.LoggerFactory.getLogger(getClass())
          .error("History contract unavailable; payload omitted");
      throw new AdminProblem(503, "History contract unavailable", "HISTORY_CONTRACT_UNAVAILABLE");
    }
  }

  private Payload checked(
      String resource,
      int version,
      String action,
      Map<String, Object> before,
      Map<String, Object> after,
      boolean reading) {
    if (CORE.contains(resource)) {
      if (version != 1) throw invalid();
      return new Payload(
          reading
              ? HistoryPayload.read(resource, before)
              : HistoryPayload.checked(resource, before),
          reading ? HistoryPayload.read(resource, after) : HistoryPayload.checked(resource, after),
          1);
    }
    var c = require(resource, version);
    if (!c.actions().contains(action)) throw invalid();
    var b = flat(c, before);
    var a = flat(c, after);
    try {
      if (json.writeValueAsBytes(List.of(b, a)).length > 65536) throw invalid();
    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
      throw invalid();
    }
    return new Payload(b, a, version);
  }

  private Map<String, Object> flat(HistoryContract c, Map<String, Object> input) {
    if (input == null || !input.keySet().equals(c.fields().keySet())) throw invalid();
    var copy = new LinkedHashMap<String, Object>();
    input.forEach(
        (key, value) -> {
          if (!c.fields().get(key).accepts(value)) throw invalid();
          copy.put(key, value);
        });
    return Collections.unmodifiableMap(copy);
  }
}
