package com.loco.maps;

import com.loco.web.ApiProblem;
import org.springframework.stereotype.Component;

@Component
public class MapPermissions {
    private final MapMemberRepository members;
    public MapPermissions(MapMemberRepository members) { this.members=members; }
    public void requireReadable(String mapId,String ownerId,String visibility,String userId) {
        if(!visibility.equals("PUBLIC") && !ownerId.equals(userId) && !members.existsByMap_IdAndUser_Id(mapId,userId))
            throw ApiProblem.forbidden("접근 권한이 없습니다.");
    }
}
