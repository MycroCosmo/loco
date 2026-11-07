package com.loco.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

public record ComparisonCase(
    String name,
    String seedResource,
    List<ComparisonStep> steps,
    Function<ScenarioValues, DatabaseSnapshot.Rules> databaseRules,
    boolean databaseUnchanged) {
  public ComparisonCase {
    if (name == null || name.isBlank()) throw new IllegalArgumentException("케이스 이름 필요");
    Objects.requireNonNull(seedResource);
    Objects.requireNonNull(databaseRules);
    steps = List.copyOf(steps);
    if (steps.isEmpty()) throw new IllegalArgumentException("요청 단계 필요");
    if (steps.stream().map(ComparisonStep::name).distinct().count() != steps.size()) {
      throw new IllegalArgumentException("단계 이름 중복");
    }
  }

  public static Builder named(String name) {
    return new Builder(name);
  }

  @Override
  public String toString() {
    return name;
  }

  public static final class Builder {
    private final String name;
    private String seedResource = "compat/seed.sql";
    private final List<ComparisonStep> steps = new ArrayList<>();
    private Function<ScenarioValues, DatabaseSnapshot.Rules> databaseRules =
        ignored -> DatabaseSnapshot.Rules.defaults();
    private boolean databaseUnchanged;

    private Builder(String name) {
      this.name = name;
    }

    public Builder seedResource(String value) {
      seedResource = value;
      return this;
    }

    public Builder step(ComparisonStep value) {
      steps.add(value);
      return this;
    }

    public Builder databaseRules(DatabaseSnapshot.Rules value) {
      databaseRules = ignored -> value;
      return this;
    }

    public Builder databaseRules(Function<ScenarioValues, DatabaseSnapshot.Rules> value) {
      databaseRules = value;
      return this;
    }

    public Builder databaseUnchanged() {
      databaseUnchanged = true;
      return this;
    }

    public ComparisonCase build() {
      return new ComparisonCase(name, seedResource, steps, databaseRules, databaseUnchanged);
    }
  }
}
