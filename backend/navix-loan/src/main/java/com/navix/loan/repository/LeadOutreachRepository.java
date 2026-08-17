package com.navix.loan.repository;

import com.navix.loan.entity.LeadOutreach;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for the lead-outreach audit trail (V55). */
@Repository
public interface LeadOutreachRepository extends JpaRepository<LeadOutreach, Long> {

    List<LeadOutreach> findByLeadIdOrderByIdDesc(Long leadId);

    List<LeadOutreach> findByDsaStaffIdOrderByIdDesc(Long dsaStaffId);

    List<LeadOutreach> findByDsaStaffIdAndLeadIdOrderByIdDesc(Long dsaStaffId, Long leadId);

    /** Per-lead rate limit (3/day). */
    long countByLeadIdAndCreatedAtAfter(Long leadId, Instant after);

    /** Per-DSA rate limit (50/day). */
    long countByDsaStaffIdAndCreatedAtAfter(Long dsaStaffId, Instant after);
}
