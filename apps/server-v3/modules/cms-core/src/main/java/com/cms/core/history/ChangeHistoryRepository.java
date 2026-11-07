package com.cms.core.history;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChangeHistoryRepository extends JpaRepository<ChangeHistory, String> {}
