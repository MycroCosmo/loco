package com.loco.maps;

import com.fasterxml.jackson.databind.JsonNode;
import com.loco.compat.CompatibilityTestBase;
import com.loco.compat.SharedHarness;
import com.loco.compat.DatabaseSnapshot;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import static org.assertj.core.api.Assertions.assertThat;

@Import(SqlQueryCounter.Configuration.class)
class MapReadIntegrationTest extends CompatibilityTestBase {
    @Override protected boolean usesV2Http() { return false; }
    static java.util.stream.Stream<JsonNode> observations() throws Exception {
        try(var input=MapReadIntegrationTest.class.getResourceAsStream("/maps/read-v2.json")) {
            var rows=new com.fasterxml.jackson.databind.ObjectMapper().readTree(input);
            return java.util.stream.StreamSupport.stream(rows.spliterator(),false);
        }
    }
    @ParameterizedTest @MethodSource("observations")
    void matchesV2AndQueryBudget(JsonNode expected) throws Exception {
        environment().reset("maps/seed.sql");
        String token=environment().token("valid",SharedHarness.Environment.USER_ID,SharedHarness.Environment.EMAIL);
        try(var db=environment().connect(SharedHarness.Side.V3)) {
            var before=DatabaseSnapshot.capture(db,DatabaseSnapshot.Rules.defaults());
            SqlQueryCounter.reset();
            String path=expected.path("path").asText();
            var result=request(v3Url(),path,HttpMethod.GET,token,null);
            assertThat(result.getStatusCode().value()).as(path).isEqualTo(expected.path("status").asInt());
            assertThat(result.getBody()).as(path).isEqualTo(expected.get("body"));
            int budget=expected.path("status").asInt()==400?1:
                    path.endsWith(MapObservationTest.id(4))&&!path.contains("?")||path.endsWith(MapObservationTest.id(5))&&!path.contains("?")?3:2;
            assertThat(SqlQueryCounter.statements()).as("%s SQL budget=%s",path,budget).hasSize(budget);
            before.assertUnchanged(DatabaseSnapshot.capture(db,DatabaseSnapshot.Rules.defaults()));
        }
    }
}
