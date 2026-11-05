package com.loco.maps;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.loco.compat.CompatibilityTestBase;
import com.loco.compat.SharedHarness;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("v2-comparison")
class MapObservationTest extends CompatibilityTestBase {
    static String id(int n) { return "aaaaaaaa-aaaa-4aaa-8aaa-%012d".formatted(n); }
    @Test void readBoundaries() throws Exception {
        environment().reset("maps/seed.sql");
        String token = environment().token("valid", SharedHarness.Environment.USER_ID, SharedHarness.Environment.EMAIL);
        var rows = mapper.createArrayNode();
        var paths = new java.util.ArrayList<>(List.of("", "?visibility=PUBLIC", "?visibility=PRIVATE", "?visibility=bad",
                "?sort=popular", "?sort=places", "?sort=bad", "?limit=0", "?limit=-1", "?limit=NaN", "?limit=12abc", "?limit=1.5",
                "?limit=1&limit=2", "?limit=100", "?limit=1", "?limit=2", "?search=SeOuL", "?search=coffee%20shop", "?search=%25", "?search=notfound",
                "?search=room-1", "?search=%20%20", "?search=Seoul%20coffee", "?search=%5C", "?visibility=PRIVATE&search=Seoul",
                "/me", "/me?type=joined", "/me?type=bad", "/not-a-uuid"));
        for (int n=1;n<=6;n++) { paths.add("/"+id(n)); paths.add("?limit=1&cursor="+id(n)); paths.add("?sort=popular&limit=1&cursor="+id(n)); }
        paths.add("?cursor=missing"); paths.add("?visibility=PUBLIC&cursor="+id(4));
        for (var suffix : paths) {
            var result = request(v2Url(), "/api/maps"+suffix,HttpMethod.GET,token,null);
            rows.addObject().put("path","/api/maps"+suffix).put("status",result.getStatusCode().value()).set("body",result.getBody());
        }
        if (Boolean.getBoolean("maps.observe")) {
            var output = Path.of("build/reports/maps/read-v2.json"); Files.createDirectories(output.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), rows);
        } else try (var input=getClass().getResourceAsStream("/maps/read-v2.json")) {
            assertThat(rows).isEqualTo(mapper.readTree(input));
        }
    }
}
