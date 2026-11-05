package com.loco.maps;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import com.loco.images.LocalImageFiles;
import com.loco.users.User;
import com.loco.web.ApiProblem;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MapMutationService {
    private final MapRepository maps;
    private final MapMemberRepository members;
    private final InviteCodes codes;
    private final EntityManager em;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final LocalImageFiles files;
    public MapMutationService(MapRepository maps,MapMemberRepository members,InviteCodes codes,EntityManager em,
            Clock clock,TransactionTemplate transaction,LocalImageFiles files) {
        this.maps=maps;this.members=members;this.codes=codes;this.em=em;this.clock=clock;this.transaction=transaction;this.files=files;
    }
    @Transactional
    public MapResponses.Core create(String userId,MapMutationInputs input) {
        String code=input.visibility()==Visibility.PRIVATE?codes.generate(null):null;
        var room=maps.save(MapRoom.create(em.getReference(User.class,userId),input,code));
        members.save(MapMember.create(room,em.getReference(User.class,userId)));
        maps.flush();
        return MapResponses.core(room);
    }
    @Transactional
    public MapResponses.Core update(String id,String userId,MapMutationInputs input) {
        var room=owned(id,userId,"수정 권한이 없습니다.");
        if(input.arrayData()||(input.hasTags()&&input.tags()==null)) throw new IllegalArgumentException("Invalid Prisma update data");
        String code=room.getInviteCode();
        if(input.visibility()==Visibility.PRIVATE&&code==null) code=codes.generate(null);
        if(input.visibility()==Visibility.PUBLIC) code=null;
        room.update(input,code,LocalDateTime.now(clock));
        maps.flush(); return MapResponses.core(room);
    }
    public void delete(String id,String userId,boolean force) {
        List<String> paths=transaction.execute(status->{
            var room=owned(id,userId,"삭제 권한이 없습니다.");
            @SuppressWarnings("unchecked") List<Tuple> places=em.createNativeQuery("SELECT \"createdById\",\"imageUrls\" FROM places WHERE \"mapId\"=:id",Tuple.class)
                    .setParameter("id",id).getResultList();
            long others=places.stream().filter(p->!userId.equals(p.get("createdById"))).count();
            if(others>0&&!force) throw ApiProblem.badRequest("다른 사용자의 장소 "+others+"개가 있어 삭제할 수 없습니다. 강제 삭제를 사용하세요.");
            var result=new ArrayList<String>();
            if(room.getCoverImageUrl()!=null) result.add(room.getCoverImageUrl());
            for(var place:places) if(place.get("imageUrls") instanceof String[] urls) result.addAll(List.of(urls));
            maps.delete(room); maps.flush(); return result;
        });
        paths.forEach(files::delete);
    }
    private MapRoom owned(String id,String userId,String message) {
        var room=maps.findById(id).orElseThrow(()->ApiProblem.notFound("지도방을 찾을 수 없습니다."));
        if(!room.getOwner().getId().equals(userId)) throw ApiProblem.forbidden(message);
        return room;
    }
}
