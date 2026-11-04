package com.loco.users;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.loco.compat.CompatibilityTestBase;
import com.loco.compat.DatabaseSnapshot;
import com.loco.compat.SharedHarness;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("v2-comparison")
class UserFcmComparisonTest extends CompatibilityTestBase {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"missing-token", "deleted-user", "missing-user"})
    void authenticationPrecedesFcmInput(String state) throws Exception {
        environment().reset("compat/seed.sql");
        for (var side : SharedHarness.Side.values()) try (var db = environment().connect(side)) {
            if (state.equals("deleted-user")) db.createStatement().execute("UPDATE users SET \"deletedAt\"=CURRENT_TIMESTAMP");
            if (state.equals("missing-user")) db.createStatement().execute("DELETE FROM users");
            var before = DatabaseSnapshot.capture(db, DatabaseSnapshot.Rules.defaults());
            var response = request(side == SharedHarness.Side.V2 ? v2Url() : v3Url(), "/api/users/me/fcm-token", HttpMethod.PUT,
                    state.equals("missing-token") ? null : environment().token("valid", SharedHarness.Environment.USER_ID, SharedHarness.Environment.EMAIL),
                    java.util.Map.of("fcmToken", 12));
            assertThat(response.getStatusCode().value()).isEqualTo(401);
            assertThat(response.getBody()).isEqualTo(mapper.readTree("{\"statusCode\":401,\"message\":\"Unauthorized\"}"));
            before.assertUnchanged(DatabaseSnapshot.capture(db, DatabaseSnapshot.Rules.defaults()));
        }
    }

    @Test void fcmContract() throws Exception {
        var observed = mapper.createArrayNode();
        for (String input : List.of("{}", "{\"fcmToken\":null}", "{\"fcmToken\":\"token\"}",
                "{\"fcmToken\":\"seed-token\"}", "{\"fcmToken\":\"\"}", "{\"fcmToken\":12}",
                "{\"fcmToken\":true}", "{\"fcmToken\":[]}", "{\"fcmToken\":{}}",
                "{\"fcmToken\":{\"set\":\"operation\"}}", "{\"fcmToken\":{\"set\":null}}",
                "{\"fcmToken\":{\"set\":12}}", "{\"fcmToken\":{\"other\":1}}",
                "{\"fcmToken\":\"token\",\"other\":1}", "[]", "[1]", "null-body")) {
            environment().reset("compat/seed.sql");
            var actual = mapper.createArrayNode();
            for (var side : SharedHarness.Side.values()) {
                if (Boolean.getBoolean("users.observe") && side == SharedHarness.Side.V3) continue;
                try (var db = environment().connect(side)) {
                    db.createStatement().execute("UPDATE users SET \"fcmToken\"='seed-token'");
                    var before = DatabaseSnapshot.capture(db, DatabaseSnapshot.Rules.defaults());
                    var response = request(side == SharedHarness.Side.V2 ? v2Url() : v3Url(), "/api/users/me/fcm-token",
                            HttpMethod.PUT, environment().token("valid", SharedHarness.Environment.USER_ID, SharedHarness.Environment.EMAIL),
                            input.equals("null-body") ? null : mapper.readTree(input));
                    var row = actual.addObject().put("input", input).put("status", response.getStatusCode().value());
                    row.set("body", response.getBody());
                    try (var query = db.createStatement().executeQuery("SELECT \"fcmToken\",\"updatedAt\" <> timestamp '2026-01-01' FROM users")) {
                        query.next(); row.put("fcmToken", query.getString(1)); row.put("updatedAtChanged", query.getBoolean(2));
                    }
                    if (response.getStatusCode().isError()) before.assertUnchanged(DatabaseSnapshot.capture(db, DatabaseSnapshot.Rules.defaults()));
                }
            }
            observed.add(actual.get(0));
            if (!Boolean.getBoolean("users.observe")) assertThat(actual.get(1)).as(input).isEqualTo(actual.get(0));
        }
        var path = Path.of("build/reports/users/fcm-v2.json");
        Files.createDirectories(path.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), observed);
        if (!Boolean.getBoolean("users.observe")) try (var fixture = getClass().getResourceAsStream("/users/fcm-v2.json")) {
            assertThat(observed).isEqualTo(mapper.readTree(fixture));
        }
    }
}
