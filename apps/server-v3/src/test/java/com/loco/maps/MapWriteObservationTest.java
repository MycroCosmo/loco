package com.loco.maps;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loco.compat.CompatibilityTestBase;
import com.loco.compat.SharedHarness;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("v2-comparison")
class MapWriteObservationTest extends CompatibilityTestBase {
    static JsonNode normalize(JsonNode source) {
        var body=source.deepCopy();
        if(body instanceof ObjectNode object && object.has("id")) {
            if(!object.path("id").asText().startsWith("aaaaaaaa-")) {
                assertThat(object.path("id").asText()).matches("[0-9a-f-]{36}"); object.put("id","$id");
            }
            for(String key:List.of("createdAt","updatedAt")) {
                java.time.Instant.parse(object.path(key).asText()); object.put(key,"$time");
            }
            if(!object.path("inviteCode").isNull()) {
                assertThat(object.path("inviteCode").asText()).matches("[0-9A-Z]{1,8}"); object.put("inviteCode","$code");
            }
        }
        return body;
    }
    @Test void writeBoundaries() throws Exception {
        String token=environment().token("valid",SharedHarness.Environment.USER_ID,SharedHarness.Environment.EMAIL);
        var rows=mapper.createArrayNode();
        for(var method:List.of(HttpMethod.POST,HttpMethod.PUT)) {
            var inputs=new java.util.ArrayList<>(List.of("{}","[]","[1]","{\"unknown\":1}",
                    "{\"title\":\"new-room\",\"visibility\":\"PUBLIC\"}","{\"title\":\"\",\"visibility\":\"PRIVATE\"}",
                    "{\"title\":null,\"visibility\":null}","{\"title\":1,\"description\":1,\"tags\":[1],\"visibility\":\"bad\"}",
                    "{\"title\":\"new-room\",\"visibility\":\"PUBLIC\",\"description\":null,\"tags\":null}",
                    "{\"title\":\"new-room\",\"visibility\":\"PUBLIC\",\"tags\":[]}","{\"title\":\"room-1\"}",
                    "{\"description\":null}","{\"tags\":null}","{\"visibility\":null}","{\"tags\":\"wrong\"}",
                    "{\"title\":\"new-room\",\"visibility\":\"PRIVATE\",\"tags\":[\"a\",\"a\"]}"));
            inputs.add(mapper.createObjectNode().put("title","x".repeat(101)).put("description","y".repeat(501)).put("visibility","PUBLIC").toString());
            for(String input:inputs) {
                environment().reset("maps/seed.sql");
                String path="/api/maps"+(method==HttpMethod.PUT?"/"+MapObservationTest.id(1):"");
                var result=request(v2Url(),path,method,token,mapper.readTree(input));
                rows.addObject().put("method",method.name()).put("path",path).put("input",input)
                        .put("status",result.getStatusCode().value()).set("body",normalize(result.getBody()));
            }
        }
        for(String suffix:List.of("/"+MapObservationTest.id(1),"/"+MapObservationTest.id(2),"/"+MapObservationTest.id(3),"/missing")) {
            environment().reset("maps/seed.sql");
            var result=request(v2Url(),"/api/maps"+suffix,HttpMethod.DELETE,token,null);
            rows.addObject().put("method","DELETE").put("path","/api/maps"+suffix).put("input","null")
                    .put("status",result.getStatusCode().value()).set("body",result.getBody());
        }
        if(Boolean.getBoolean("maps.observe")) {
            var output=Path.of("build/reports/maps/write-v2.json"); Files.createDirectories(output.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),rows);
        } else try(var input=getClass().getResourceAsStream("/maps/write-v2.json")) { assertThat(rows).isEqualTo(mapper.readTree(input)); }
    }
}
