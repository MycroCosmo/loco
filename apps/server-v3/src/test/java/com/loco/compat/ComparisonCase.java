package com.loco.compat;

import java.util.Map;
import org.springframework.http.HttpMethod;

public record ComparisonCase(String name, HttpMethod method, String path, String bodyResource,
                             int expectedStatus, boolean authenticated, String seedResource,
                             Map<String, String> valueExceptions, DatabaseSnapshot.Rules databaseRules,
                             boolean databaseUnchanged) {
    public ComparisonCase { valueExceptions = Map.copyOf(valueExceptions); }
    @Override public String toString() { return name; }
}
