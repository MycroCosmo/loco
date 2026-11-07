package com.cms.core.account;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminRoleRepository extends JpaRepository<AdminRole, String> {
  Optional<AdminRole> findByCode(String code);
}
