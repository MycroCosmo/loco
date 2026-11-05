package com.loco.maps;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MapMemberRepository extends JpaRepository<MapMember,String> {
    boolean existsByMap_IdAndUser_Id(String mapId, String userId);
}
