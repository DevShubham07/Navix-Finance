package com.navix.loan.repository;

import com.navix.loan.entity.DsaLeadRejection;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for entry-time PAN-rejection audit (V55). */
@Repository
public interface DsaLeadRejectionRepository extends JpaRepository<DsaLeadRejection, Long> {

    long countByDsaStaffIdAndCreatedAtAfter(Long dsaStaffId, Instant after);
}
