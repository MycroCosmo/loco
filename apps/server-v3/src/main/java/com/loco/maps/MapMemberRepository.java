package com.loco.maps;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MapMemberRepository extends JpaRepository<MapMember,String> {
    boolean existsByMap_IdAndUser_Id(String mapId, String userId);
    @org.springframework.data.jpa.repository.Query("select m from MapMember m join fetch m.user where m.map.id=:mapId order by m.createdAt asc")
    java.util.List<MapMember> findByMap_IdOrderByCreatedAtAsc(String mapId);
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value="""
        INSERT INTO map_members(id,"mapId","userId","createdAt") VALUES (:id,:mapId,:userId,:createdAt)
        ON CONFLICT ("mapId","userId") DO NOTHING
        """,nativeQuery=true)
    int join(String id,String mapId,String userId,java.time.LocalDateTime createdAt);
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from MapMember m where m.map.id=:mapId and m.user.id=:userId")
    int leave(String mapId,String userId);
}
