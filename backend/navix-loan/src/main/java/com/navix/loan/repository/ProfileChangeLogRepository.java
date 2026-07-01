package com.navix.loan.repository;

import com.navix.loan.entity.ProfileChangeLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for the audited profile/salary change history (Phase 2.1). */
@Repository
public interface ProfileChangeLogRepository extends JpaRepository<ProfileChangeLog, Long> {

    /** One customer's change history, newest first. */
    List<ProfileChangeLog> findByCustomerIdOrderByIdDesc(Long customerId);
}
