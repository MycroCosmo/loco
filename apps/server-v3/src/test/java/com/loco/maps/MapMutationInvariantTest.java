package com.loco.maps;

import java.util.Map;
import com.loco.compat.CompatibilityTestBase;
import com.loco.compat.DatabaseSnapshot;
import com.loco.compat.SharedHarness;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("v2-comparison")
class MapMutationInvariantTest extends CompatibilityTestBase {
    @ParameterizedTest @ValueSource(strings={"create","create-commit","update"})
    void failureRollsBackEveryBusinessRow(String operation) throws Exception {
        environment().reset("maps/seed.sql");
        String token=environment().token("valid",SharedHarness.Environment.USER_ID,SharedHarness.Environment.EMAIL);
        for(var side:SharedHarness.Side.values()) try(var db=environment().connect(side)) {
            var sql=db.createStatement();
            String table=operation.equals("update")?"maps":"map_members";
            sql.execute("CREATE FUNCTION map_write_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'map write rollback'; END; $$");
            sql.execute(operation.equals("create-commit")?"CREATE CONSTRAINT TRIGGER map_write_failure AFTER INSERT ON map_members DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION map_write_failure()":
                    "CREATE TRIGGER map_write_failure BEFORE "+(operation.equals("update")?"UPDATE":"INSERT")+" ON "+table+" FOR EACH ROW EXECUTE FUNCTION map_write_failure()");
            try {
                var before=DatabaseSnapshot.capture(db,DatabaseSnapshot.Rules.defaults());
                var response=request(side==SharedHarness.Side.V2?v2Url():v3Url(),"/api/maps"+(operation.equals("update")?"/"+MapObservationTest.id(1):""),
                        operation.equals("update")?HttpMethod.PUT:HttpMethod.POST,token,Map.of("title","failed write","visibility","PRIVATE"));
                assertThat(response.getStatusCode().value()).isEqualTo(500);
                assertThat(response.getBody()).isEqualTo(mapper.readTree("{\"statusCode\":500,\"message\":\"Internal server error\"}"));
                before.assertUnchanged(DatabaseSnapshot.capture(db,DatabaseSnapshot.Rules.defaults()));
            } finally { sql.execute("DROP TRIGGER map_write_failure ON "+table);sql.execute("DROP FUNCTION map_write_failure()"); }
        }
    }
    @Test void visibilityTransitionsAndStoredNullArrays() throws Exception {
        environment().reset("maps/seed.sql");
        String token=environment().token("valid",SharedHarness.Environment.USER_ID,SharedHarness.Environment.EMAIL);
        for(var side:SharedHarness.Side.values()) {
            String base=side==SharedHarness.Side.V2?v2Url():v3Url(),path="/api/maps/"+MapObservationTest.id(2);
            var first=request(base,path,HttpMethod.PUT,token,Map.of("visibility","PUBLIC"));
            assertThat(first.getStatusCode().value()).isEqualTo(200);assertThat(first.getBody().get("inviteCode").isNull()).isTrue();
            var second=request(base,path,HttpMethod.PUT,token,Map.of("visibility","PRIVATE"));
            assertThat(second.getStatusCode().value()).isEqualTo(200);assertThat(second.getBody().path("inviteCode").asText()).isNotEqualTo("INVITE02").matches("[A-Z0-9]{1,8}");
            try(var db=environment().connect(side)) {db.createStatement().execute("UPDATE maps SET tags=NULL WHERE id='"+MapObservationTest.id(2)+"'");}
            var read=request(base,path,HttpMethod.GET,token,null);
            assertThat(read.getStatusCode().value()).isEqualTo(200);assertThat(read.getBody().path("tags").isArray()).isTrue();assertThat(read.getBody().path("tags").size()).isZero();
        }
    }
    @Test void implementedRoutesRejectMissingDeletedAndAbsentMembers() throws Exception {
        environment().reset("maps/seed.sql");
        String[][] routes={{"GET","/api/maps"},{"GET","/api/maps/me"},{"GET","/api/maps/"+MapObservationTest.id(1)},
                {"POST","/api/maps"},{"PUT","/api/maps/"+MapObservationTest.id(1)},{"DELETE","/api/maps/"+MapObservationTest.id(1)}};
        String[] tokens={null,environment().token("valid","44444444-4444-4444-8444-444444444444","deleted@example.test"),
                environment().token("valid","99999999-9999-4999-8999-999999999999","absent@example.test")};
        for(var route:routes) for(String token:tokens) for(var side:SharedHarness.Side.values()) {
            var response=request(side==SharedHarness.Side.V2?v2Url():v3Url(),route[1],HttpMethod.valueOf(route[0]),token,Map.of("bad","input"));
            assertThat(response.getStatusCode().value()).as(route[0]+" "+route[1]).isEqualTo(401);
            assertThat(response.getBody()).isEqualTo(mapper.readTree("{\"message\":\"Unauthorized\",\"statusCode\":401}"));
        }
    }
}
