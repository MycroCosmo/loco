package com.loco.maps;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MapRepository extends JpaRepository<MapRoom,String> {
    boolean existsByInviteCode(String inviteCode);
}
