package com.loco.maps;

import java.util.Map;
import java.nio.file.Files;
import com.loco.compat.CompatibilityTestBase;
import com.loco.compat.DatabaseSnapshot;
import com.loco.compat.SharedHarness;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("v2-comparison") @Import(SqlQueryCounter.Configuration.class)
class MapDeletionComparisonTest extends CompatibilityTestBase {
    @ParameterizedTest @ValueSource(strings={"ordinary","others","force","force-uppercase","favorite","trigger","commit"})
    void databaseAndFilesFollowCommit(String mode) throws Exception {
        environment().reset("maps/seed.sql");
        String id=MapObservationTest.id(1),token=environment().token("valid",SharedHarness.Environment.USER_ID,SharedHarness.Environment.EMAIL);
        int status=switch(mode) { case "others","force-uppercase" -> 400; case "favorite","trigger","commit" -> 500; default -> 200; };
        for(var side:SharedHarness.Side.values()) try(var db=environment().connect(side)) {
            var sql=db.createStatement();
            sql.execute("UPDATE maps SET \"coverImageUrl\"='/uploads/maps/delete.webp' WHERE id='"+id+"'");
            sql.execute("UPDATE places SET \"imageUrls\"=ARRAY['/uploads/places/delete.webp'] WHERE \"mapId\"='"+id+"'");
            sql.execute("INSERT INTO place_reviews(id,\"placeId\",\"userId\",rating,content,\"updatedAt\") VALUES('eeeeeeee-eeee-4eee-8eee-000000000001','cccccccc-cccc-4ccc-8ccc-000000000001','22222222-2222-4222-8222-222222222222',5,'review','2026-01-01')");
            sql.execute("INSERT INTO place_marker_cells(\"mapId\",precision,\"cellLatKey\",\"cellLngKey\",latitude,longitude,\"minLat\",\"maxLat\",\"minLng\",\"maxLng\",count,\"exactKeyCount\",\"updatedAt\") VALUES('"+id+"',1,'1','1',37,127,37,37,127,127,1,1,'2026-01-01')");
            sql.execute("INSERT INTO reports(id,\"reporterId\",\"targetType\",\"targetId\",reason) VALUES('eeeeeeee-eeee-4eee-8eee-000000000002','22222222-2222-4222-8222-222222222222','MAP','"+id+"','keep')");
            if(mode.equals("others")||mode.startsWith("force")) sql.execute("UPDATE places SET \"createdById\"='22222222-2222-4222-8222-222222222222' WHERE \"mapId\"='"+id+"'");
            if(mode.equals("favorite")) sql.execute("INSERT INTO favorites(id,\"mapId\",\"userId\") VALUES('eeeeeeee-eeee-4eee-8eee-000000000003','"+id+"','11111111-1111-4111-8111-111111111111')");
            boolean injected=mode.equals("trigger")||mode.equals("commit");
            if(injected) {
                sql.execute("CREATE FUNCTION map_delete_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'map deletion rollback'; END; $$");
                sql.execute(mode.equals("commit")?"CREATE CONSTRAINT TRIGGER map_delete_failure AFTER DELETE ON maps DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION map_delete_failure()":"CREATE TRIGGER map_delete_failure BEFORE DELETE ON maps FOR EACH ROW EXECUTE FUNCTION map_delete_failure()");
            }
            prepareFiles(side);
            var before=DatabaseSnapshot.capture(db,DatabaseSnapshot.Rules.defaults());
            try {
                SqlQueryCounter.reset();
                var result=request(side==SharedHarness.Side.V2?v2Url():v3Url(),"/api/maps/"+id+(mode.equals("force")?"?force=true":mode.equals("force-uppercase")?"?force=TRUE":""),HttpMethod.DELETE,token,null);
                assertThat(result.getStatusCode().value()).isEqualTo(status);
                if(status==500) assertThat(result.getBody()).isEqualTo(mapper.readTree("{\"statusCode\":500,\"message\":\"Internal server error\"}"));
                if(status==400) assertThat(result.getBody().path("message").asText()).isEqualTo("다른 사용자의 장소 1개가 있어 삭제할 수 없습니다. 강제 삭제를 사용하세요.");
                if(side==SharedHarness.Side.V3) assertThat(SqlQueryCounter.statements()).hasSize(status==400?3:4);
                if(status>=400) before.assertUnchanged(DatabaseSnapshot.capture(db,DatabaseSnapshot.Rules.defaults()));
                else {
                    for(String table:new String[]{"place_reviews","place_marker_cells"}) try(var rows=sql.executeQuery("SELECT count(*) FROM "+table)) {rows.next();assertThat(rows.getInt(1)).isZero();}
                    try(var rows=sql.executeQuery("SELECT count(*) FROM reports")) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
                }
                var state=fileState(side);
                assertThat(state.path("cover").asBoolean()).isEqualTo(status!=200);
                assertThat(state.path("place").asBoolean()).isEqualTo(status!=200);
                assertThat(state.path("keep").asBoolean()).isTrue();
            } finally { if(injected) {sql.execute("DROP TRIGGER map_delete_failure ON maps");sql.execute("DROP FUNCTION map_delete_failure()");} }
        }
        try(var left=environment().connect(SharedHarness.Side.V2);var right=environment().connect(SharedHarness.Side.V3)) {
            var rules=MapWriteComparisonTest.rules();var snapshot=DatabaseSnapshot.capture(left,rules);
            DatabaseSnapshot.compare(snapshot,DatabaseSnapshot.capture(right,rules),snapshot.seedIds(),rules);
        }
    }
    private void prepareFiles(SharedHarness.Side side) throws Exception {
        if(side==SharedHarness.Side.V2) environment().nodeCheck("const fs=require('fs');for(const p of ['maps/delete.webp','places/delete.webp','maps/keep.webp']){fs.mkdirSync('/app/uploads/'+p.split('/')[0],{recursive:true});fs.writeFileSync('/app/uploads/'+p,'fixture')}console.log('{}')",Map.of());
        else for(String p:new String[]{"maps/delete.webp","places/delete.webp","maps/keep.webp"}) {var file=environment().uploads.resolve(p);Files.createDirectories(file.getParent());Files.writeString(file,"fixture");}
    }
    private com.fasterxml.jackson.databind.JsonNode fileState(SharedHarness.Side side) throws Exception {
        if(side==SharedHarness.Side.V2) return environment().nodeCheck("const fs=require('fs');console.log(JSON.stringify({cover:fs.existsSync('/app/uploads/maps/delete.webp'),place:fs.existsSync('/app/uploads/places/delete.webp'),keep:fs.existsSync('/app/uploads/maps/keep.webp')}))",Map.of());
        return mapper.valueToTree(Map.of("cover",Files.exists(environment().uploads.resolve("maps/delete.webp")),"place",Files.exists(environment().uploads.resolve("places/delete.webp")),"keep",Files.exists(environment().uploads.resolve("maps/keep.webp"))));
    }
}
