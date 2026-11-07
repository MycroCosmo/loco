package com.cms.core.menu;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;

public interface MenuTreeRepository extends JpaRepository<MenuTreeRevision, Integer> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from MenuTreeRevision t where t.id=1")
  MenuTreeRevision lock();
}
