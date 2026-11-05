package com.loco.maps;

import java.util.Map;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.loco.compat.CompatibilityTestBase;
import com.loco.compat.DatabaseSnapshot;
import com.loco.compat.SharedHarness;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("v2-comparison") @Import(SqlQueryCounter.Configuration.class)
class MapWriteComparisonTest extends CompatibilityTestBase {
    static java.util.stream.Stream<JsonNode> observations() throws Exception {
        try(var input=MapWriteComparisonTest.class.getResourceAsStream("/maps/write-v2.json")) {
            var rows=new com.fasterxml.jackson.databind.ObjectMapper().readTree(input);
            return java.util.stream.StreamSupport.stream(rows.spliterator(),false);
        }
    }
    static DatabaseSnapshot.Rules rules() {
        return new DatabaseSnapshot.Rules(Map.of("map_members",List.of("__sort_map","__sort_user"),"favorites",List.of("__sort_map","__sort_user")),Map.of(
                "map_members","SELECT mm.*,m.title AS __sort_map,u.email AS __sort_user FROM map_members mm JOIN maps m ON mm.\"mapId\"=m.id JOIN users u ON mm.\"userId\"=u.id",
                "favorites","SELECT f.*,m.title AS __sort_map,u.email AS __sort_user FROM favorites f JOIN maps m ON f.\"mapId\"=m.id JOIN users u ON f.\"userId\"=u.id",
                "maps","SELECT id,\"ownerId\",title,description,tags,\"coverImageUrl\",visibility,CASE WHEN \"inviteCode\" LIKE 'INVITE%' THEN \"inviteCode\" WHEN \"inviteCode\" IS NOT NULL THEN '$generated-code' END AS \"inviteCode\",\"createdAt\",\"updatedAt\" FROM maps"));
    }
    @ParameterizedTest @MethodSource("observations")
    void writeMatchesV2AndDatabase(JsonNode expected) throws Exception {
        environment().reset("maps/seed.sql");
        String token=environment().token("valid",SharedHarness.Environment.USER_ID,SharedHarness.Environment.EMAIL);
        var rules=rules();
        DatabaseSnapshot before;
        try(var db=environment().connect(SharedHarness.Side.V2)) { before=DatabaseSnapshot.capture(db,rules); }
        for(var side:SharedHarness.Side.values()) {
            SqlQueryCounter.reset();
            var method=HttpMethod.valueOf(expected.path("method").asText());
            var body=mapper.readTree(expected.path("input").asText());
            var result=request(side==SharedHarness.Side.V2?v2Url():v3Url(),expected.path("path").asText(),method,token,
                    body.isNull()?null:body);
            assertThat(result.getStatusCode().value()).as(expected.toString()).isEqualTo(expected.path("status").asInt());
            assertThat(MapWriteObservationTest.normalize(result.getBody())).isEqualTo(expected.get("body"));
            if(result.getBody().has("id")) try(var db=environment().connect(side);
                    var query=db.prepareStatement("SELECT \"inviteCode\",\"createdAt\",\"updatedAt\" FROM maps WHERE id=?")) {
                query.setString(1,result.getBody().path("id").asText());
                try(var row=query.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    var code=result.getBody().get("inviteCode");
                    assertThat(row.getString(1)).isEqualTo(code.isNull()?null:code.asText());
                    if(!code.isNull()&&!code.asText().startsWith("INVITE")&&side==SharedHarness.Side.V3) assertThat(code.asText()).matches("[A-Z0-9]{8}");
                    assertThat(result.getBody().path("createdAt").asText()).isEqualTo(com.loco.users.UserResponses.timestamp(row.getTimestamp(2).toLocalDateTime()));
                    assertThat(result.getBody().path("updatedAt").asText()).isEqualTo(com.loco.users.UserResponses.timestamp(row.getTimestamp(3).toLocalDateTime()));
                }
            }
            if(side==SharedHarness.Side.V3) {
                int status=result.getStatusCode().value();
                JsonNode input=mapper.readTree(expected.path("input").asText());
                int budget=status==400?1:method==HttpMethod.POST?3+(input.path("visibility").asText().equals("PRIVATE")?1:0):
                        method==HttpMethod.DELETE?(status==200?4:2):status==500?(input.has("visibility")&&input.get("visibility").isNull()?3:2):input.isEmpty()?2:3+(input.path("visibility").asText().equals("PRIVATE")?1:0);
                assertThat(SqlQueryCounter.statements()).as("%s %s SQL",method,input).hasSize(budget);
            }
        }
        try(var left=environment().connect(SharedHarness.Side.V2);var right=environment().connect(SharedHarness.Side.V3)) {
            DatabaseSnapshot.compare(DatabaseSnapshot.capture(left,rules),DatabaseSnapshot.capture(right,rules),before.seedIds(),rules);
            if(expected.path("status").asInt()>=400) {
                before.assertUnchanged(DatabaseSnapshot.capture(left,rules)); before.assertUnchanged(DatabaseSnapshot.capture(right,rules));
            }
        }
    }
    @Test void recordsIntentionalCodeGenerationDifference() throws Exception {
        environment().reset("maps/seed.sql");
        var response=request(v3Url(),"/api/maps",HttpMethod.POST,environment().token("valid",SharedHarness.Environment.USER_ID,SharedHarness.Environment.EMAIL),
                Map.of("title","secure code","visibility","PRIVATE"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().path("inviteCode").asText()).matches("[A-Z0-9]{8}");
        var row=Map.<String,Object>of("case","maps.invite-code-generator","kind","intentional",
                "v2","Math.random base36, up to 8 characters","v3","SecureRandom, exactly 8 uppercase alphanumeric characters");
        if(!environment().knownDifferences.contains(row)) environment().knownDifferences.add(row);
    }
}
