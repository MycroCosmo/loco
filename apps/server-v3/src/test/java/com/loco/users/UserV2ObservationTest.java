package com.loco.users;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loco.compat.CompatibilityTestBase;
import com.loco.compat.SharedHarness;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("v2-comparison")
class UserV2ObservationTest extends CompatibilityTestBase {
    @Test void profileContract() throws Exception {
        var rows = mapper.createArrayNode();
        for (String input : List.of("GET", "{}", "{\"nickname\":\"compat\"}", "{\"nickname\":\"new\"}",
                "{\"nickname\":\"\"}", "{\"nickname\":null}", "{\"nickname\":12}",
                "{\"nickname\":\"" + "x".repeat(31) + "\"}", "{\"nickname\":\"" + "😀".repeat(30) + "\"}",
                "{\"profileImageUrl\":null}", "{\"profileImageUrl\":\"https://example.com/a.png\"}",
                "{\"profileImageUrl\":\"http://localhost/a\"}", "{\"profileImageUrl\":\"http://127.0.0.1/a\"}",
                "{\"profileImageUrl\":\"/uploads/profiles/a.webp\"}", "{\"profileImageUrl\":\"\"}",
                "{\"extra\":true,\"nickname\":12,\"profileImageUrl\":3}", "[]", "[1]", "null-body")) {
            environment().reset("compat/seed.sql");
            var response = request(v2Url(), "/api/users/me", input.equals("GET") ? HttpMethod.GET : HttpMethod.PUT,
                    environment().token("valid", SharedHarness.Environment.USER_ID, SharedHarness.Environment.EMAIL),
                    input.equals("GET") || input.equals("null-body") ? null : mapper.readTree(input));
            var row = rows.addObject().put("input", input).put("status", response.getStatusCode().value());
            var body = response.getBody().deepCopy();
            for (String field : List.of("createdAt", "updatedAt")) if (body.has(field)) {
                assertThat(body.path(field).asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
                ((ObjectNode) body).put(field, "<timestamp>");
            }
            row.set("body", body);
            try (var db = environment().connect(SharedHarness.Side.V2); var query = db.createStatement().executeQuery(
                    "SELECT nickname,\"profileImageUrl\",\"updatedAt\" <> timestamp '2026-01-01' FROM users")) {
                query.next(); row.put("nickname", query.getString(1)); row.put("profileImageUrl", query.getString(2));
                row.put("updatedAtChanged", query.getBoolean(3));
            }
        }
        var path = Path.of("build/reports/users/profile-v2.json");
        Files.createDirectories(path.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), rows);
        if (!Boolean.getBoolean("users.observe")) {
            try (var expected = getClass().getResourceAsStream("/users/profile-v2.json")) {
                assertThat(expected).as("승인된 v2 관측 fixture").isNotNull();
                assertThat(rows).isEqualTo(mapper.readTree(expected));
            }
        }
    }
}
