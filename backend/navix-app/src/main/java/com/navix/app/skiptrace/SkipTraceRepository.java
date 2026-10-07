package com.navix.app.skiptrace;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkipTraceRepository extends JpaRepository<SkipTrace, Long> {
    List<SkipTrace> findByCustomerIdOrderByCreatedAtDesc(Long customerId);
}
