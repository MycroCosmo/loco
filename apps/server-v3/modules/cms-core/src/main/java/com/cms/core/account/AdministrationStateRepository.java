package com.cms.core.account;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;

public interface AdministrationStateRepository extends JpaRepository<AdministrationState, Integer> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from AdministrationState s where s.id=1")
  AdministrationState lock();
}
