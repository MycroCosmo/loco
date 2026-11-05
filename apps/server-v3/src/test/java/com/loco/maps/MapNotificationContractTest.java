package com.loco.maps;

import java.util.Map;
import com.loco.compat.CompatibilityTestBase;
import com.loco.compat.SharedHarness;
import com.loco.notifications.NotificationSender;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.HttpMethod;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MapNotificationContractTest extends CompatibilityTestBase {
    @MockitoBean NotificationSender sender;
    @Override protected boolean usesV2Http() {return false;}
    @Test void repeatedJoinPublishesTheV2MessageAfterCommit() throws Exception {
        environment().reset("maps/seed.sql");
        String id=MapObservationTest.id(3),token=environment().token("valid",SharedHarness.Environment.USER_ID,SharedHarness.Environment.EMAIL);
        doAnswer(invocation->{
            try(var db=environment().connect(SharedHarness.Side.V3);var row=db.createStatement().executeQuery("SELECT count(*) FROM map_members WHERE \"mapId\"='"+id+"' AND \"userId\"='"+SharedHarness.Environment.USER_ID+"'")) {
                row.next();assertThat(row.getInt(1)).isEqualTo(1);
            }
            return null;
        }).when(sender).sendToUser(anyString(),any());
        for(int i=0;i<2;i++) assertThat(request(v3Url(),"/api/maps/"+id+"/join",HttpMethod.POST,token,null).getStatusCode().value()).isEqualTo(201);
        verify(sender,times(2)).sendToUser("22222222-2222-4222-8222-222222222222",new NotificationSender.Message("새 멤버 가입","compat님이 \"room-3\"에 가입했습니다.",Map.of("type","member_joined","mapId",id)));
    }
    @Test void rollbackDoesNotSendAndSenderFailureDoesNotUndoJoin() throws Exception {
        environment().reset("maps/seed.sql");
        String id=MapObservationTest.id(3),token=environment().token("valid",SharedHarness.Environment.USER_ID,SharedHarness.Environment.EMAIL);
        try(var db=environment().connect(SharedHarness.Side.V3)) {
            db.createStatement().execute("CREATE FUNCTION join_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'join failure'; END; $$");
            db.createStatement().execute("CREATE CONSTRAINT TRIGGER join_fail AFTER INSERT ON map_members DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION join_fail()");
            try {
                assertThat(request(v3Url(),"/api/maps/"+id+"/join",HttpMethod.POST,token,null).getStatusCode().value()).isEqualTo(500);
                verifyNoInteractions(sender);
            } finally {db.createStatement().execute("DROP TRIGGER join_fail ON map_members");db.createStatement().execute("DROP FUNCTION join_fail()");}
        }
        doThrow(new IllegalStateException("sender failed")).when(sender).sendToUser(anyString(),any());
        assertThat(request(v3Url(),"/api/maps/"+id+"/join",HttpMethod.POST,token,null).getStatusCode().value()).isEqualTo(201);
        verify(sender).sendToUser(anyString(),any());
        try(var db=environment().connect(SharedHarness.Side.V3);var row=db.createStatement().executeQuery("SELECT count(*) FROM map_members WHERE \"mapId\"='"+id+"'")) {row.next();assertThat(row.getInt(1)).isEqualTo(2);}
    }
}
