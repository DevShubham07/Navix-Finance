package com.navix.app.provider;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface ProviderApiExecutionRepository extends JpaRepository<ProviderApiExecution, Long> {
    List<ProviderApiExecution> findTop100ByOrderByCreatedAtDesc();
    long deleteByExpiresAtBefore(Instant instant);
}
