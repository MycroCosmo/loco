package com.loco.maps;

import java.util.List;
import java.util.Map;
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
class MapMembershipComparisonTest extends CompatibilityTestBase {
    record Case(String method,String path,String user,String input,int status,int queries) { }
    static String owner="22222222-2222-4222-8222-222222222222";
    static String me=SharedHarness.Environment.USER_ID;
    static List<Case> cases() {
        return List.of(
                new Case("GET","/"+MapObservationTest.id(1)+"/membership",me,null,200,2),
                new Case("GET","/"+MapObservationTest.id(3)+"/membership",me,null,200,3),
                new Case("GET","/"+MapObservationTest.id(4)+"/membership",me,null,200,3),
                new Case("GET","/"+MapObservationTest.id(5)+"/membership",me,null,403,3),
                new Case("GET","/missing/membership",me,null,404,2),
                new Case("POST","/"+MapObservationTest.id(3)+"/join",me,null,201,4),
                new Case("POST","/"+MapObservationTest.id(1)+"/join",me,null,201,4),
                new Case("POST","/"+MapObservationTest.id(4)+"/join",me,null,403,2),
                new Case("POST","/missing/join",me,null,404,2),
                new Case("POST","/join-by-code",me,"{\"inviteCode\":\" invite05 \"}",201,5),
                new Case("POST","/join-by-code",me,"{\"inviteCode\":\"INVITE04\"}",201,5),
                new Case("POST","/join-by-code",me,"{\"inviteCode\":\"missing\"}",404,2),
                new Case("POST","/join-by-code",me,"{\"inviteCode\":\" \"}",400,1),
                new Case("POST","/join-by-code",me,"{\"inviteCode\":null}",400,1),
                new Case("POST","/join-by-code",me,"{\"inviteCode\":3,\"unknown\":1}",400,1),
                new Case("POST","/join-by-code",me,"{}",400,1),
                new Case("POST","/"+MapObservationTest.id(4)+"/leave",me,null,201,3),
                new Case("POST","/"+MapObservationTest.id(5)+"/leave",me,null,201,3),
                new Case("POST","/"+MapObservationTest.id(1)+"/leave",me,null,403,2),
                new Case("POST","/missing/leave",me,null,404,2),
                new Case("GET","/"+MapObservationTest.id(6)+"/members",owner,null,200,3),
                new Case("GET","/"+MapObservationTest.id(6)+"/members","33333333-3333-4333-8333-333333333333",null,200,4),
                new Case("GET","/"+MapObservationTest.id(6)+"/members",me,null,403,3),
                new Case("GET","/missing/members",me,null,404,2),
                new Case("DELETE","/"+MapObservationTest.id(4)+"/members/"+me,owner,null,200,3),
                new Case("DELETE","/"+MapObservationTest.id(4)+"/members/missing",owner,null,200,3),
                new Case("DELETE","/"+MapObservationTest.id(4)+"/members/"+owner,owner,null,400,2),
                new Case("DELETE","/"+MapObservationTest.id(4)+"/members/"+owner,me,null,403,2),
                new Case("DELETE","/missing/members/"+owner,me,null,404,2));
    }
    @ParameterizedTest @MethodSource("cases")
    void membershipMatchesV2AndQueryBudget(Case test) throws Exception {
        environment().reset("maps/seed.sql");
        String token=environment().token("valid",test.user(),"ignored@example.test");
        var rules=MapWriteComparisonTest.rules();DatabaseSnapshot before;
        try(var db=environment().connect(SharedHarness.Side.V2)) {before=DatabaseSnapshot.capture(db,rules);}
        com.fasterxml.jackson.databind.JsonNode expected=null;
        for(var side:SharedHarness.Side.values()) {
            SqlQueryCounter.reset();
            var result=request(side==SharedHarness.Side.V2?v2Url():v3Url(),"/api/maps"+test.path(),HttpMethod.valueOf(test.method()),token,test.input()==null?null:mapper.readTree(test.input()));
            assertThat(result.getStatusCode().value()).as(test.toString()).isEqualTo(test.status());
            if(expected==null) expected=result.getBody();else {
                assertThat(result.getBody()).isEqualTo(expected);
                assertThat(SqlQueryCounter.statements()).as(test.toString()).hasSize(test.queries());
            }
        }
        try(var left=environment().connect(SharedHarness.Side.V2);var right=environment().connect(SharedHarness.Side.V3)) {
            DatabaseSnapshot.compare(DatabaseSnapshot.capture(left,rules),DatabaseSnapshot.capture(right,rules),before.seedIds(),rules);
            if(test.method().equals("GET")||test.status()>=400) {before.assertUnchanged(DatabaseSnapshot.capture(left,rules));before.assertUnchanged(DatabaseSnapshot.capture(right,rules));}
        }
    }
    @Test void repeatedJoinPreservesMemberIdentityAndCreationTime() throws Exception {
        environment().reset("maps/seed.sql");
        String token=environment().token("valid",me,SharedHarness.Environment.EMAIL);
        for(var side:SharedHarness.Side.values()) {
            String base=side==SharedHarness.Side.V2?v2Url():v3Url(),path="/api/maps/"+MapObservationTest.id(3)+"/join";
            assertThat(request(base,path,HttpMethod.POST,token,null).getStatusCode().value()).isEqualTo(201);
            try(var db=environment().connect(side)) {
                var before=DatabaseSnapshot.capture(db,DatabaseSnapshot.Rules.defaults());
                assertThat(request(base,path,HttpMethod.POST,token,null).getStatusCode().value()).isEqualTo(201);
                before.assertUnchanged(DatabaseSnapshot.capture(db,DatabaseSnapshot.Rules.defaults()));
            }
        }
    }
    @Test void firebaseDeliveryRemainsAnExplicitKnownDifference() throws Exception {
        environment().reset("maps/seed.sql");
        var result=request(v3Url(),"/api/maps/"+MapObservationTest.id(3)+"/join",HttpMethod.POST,
                environment().token("valid",me,SharedHarness.Environment.EMAIL),null);
        assertThat(result.getStatusCode().value()).isEqualTo(201);
        var difference=Map.<String,Object>of("case","maps.firebase-delivery","kind","deferred",
                "v2","sends when FIREBASE_SERVICE_ACCOUNT and recipient FCM token are configured",
                "v3","notification interface and mock contract only; real delivery and invalid-token cleanup deferred",
                "until","all notification-producing APIs, including place creation, are migrated");
        if(!environment().knownDifferences.contains(difference)) environment().knownDifferences.add(difference);
    }
    @Test void allImplementedMapHandlersRejectInvalidAuthenticationBeforeInput() throws Exception {
        environment().reset("maps/seed.sql");
        String path="/api/maps/"+MapObservationTest.id(1);
        String[][] routes={{"GET","/api/maps"},{"GET","/api/maps/me"},{"GET",path},{"POST","/api/maps"},{"PUT",path},{"DELETE",path},
                {"GET",path+"/membership"},{"POST",path+"/join"},{"POST","/api/maps/join-by-code"},{"POST",path+"/leave"},{"GET",path+"/members"},{"DELETE",path+"/members/"+owner}};
        String[] tokens={null,environment().token("expired",me,SharedHarness.Environment.EMAIL),environment().token("wrong-signature",me,SharedHarness.Environment.EMAIL),"not-a-jwt",
                environment().token("valid","44444444-4444-4444-8444-444444444444","deleted@example.test"),
                environment().token("valid","99999999-9999-4999-8999-999999999999","absent@example.test")};
        for(var route:routes) for(int i=0;i<tokens.length;i++) for(var side:SharedHarness.Side.values()) {
            SqlQueryCounter.reset();
            var result=request(side==SharedHarness.Side.V2?v2Url():v3Url(),route[1],HttpMethod.valueOf(route[0]),tokens[i],Map.of("unknown","invalid input"));
            assertThat(result.getStatusCode().value()).as(route[0]+" "+route[1]).isEqualTo(401);
            assertThat(result.getBody()).isEqualTo(mapper.readTree("{\"message\":\"Unauthorized\",\"statusCode\":401}"));
            if(side==SharedHarness.Side.V3) assertThat(SqlQueryCounter.statements()).hasSize(i>=4?1:0);
        }
    }
}
