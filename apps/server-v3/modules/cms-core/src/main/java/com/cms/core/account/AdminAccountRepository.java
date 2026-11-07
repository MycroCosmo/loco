package com.cms.core.account;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;

public interface AdminAccountRepository extends JpaRepository<AdminAccount, String> {
  @EntityGraph(attributePaths = "roles")
  Optional<AdminAccount> findByLoginId(String loginId);

  @Override
  @EntityGraph(attributePaths = "roles")
  Optional<AdminAccount> findById(String id);

  @Override
  @EntityGraph(attributePaths = "roles")
  List<AdminAccount> findAll();

  @Query(
      "select count(a) from AdminAccount a join a.roles r where a.active=true and r.code='SYSTEM_ADMIN'")
  long activeSystemAdmins();

  @Query("select count(a) from AdminAccount a join a.roles r where r.id=:roleId")
  long assignedToRole(String roleId);
}
