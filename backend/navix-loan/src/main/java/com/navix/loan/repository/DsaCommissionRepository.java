package com.navix.loan.repository;

import com.navix.loan.domain.DsaCommissionStatus;
import com.navix.loan.entity.DsaCommission;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

/** Persistence for the DSA commission ledger (V55). */
@Repository
public interface DsaCommissionRepository
        extends JpaRepository<DsaCommission, Long>, JpaSpecificationExecutor<DsaCommission> {

    /** At most one row per lead — the DB-level "only the first loan ever earns commission" backstop. */
    Optional<DsaCommission> findByLeadId(Long leadId);

    /** The commission for a given loan (there is exactly one, by construction). */
    Optional<DsaCommission> findByLoanId(Long loanId);

    List<DsaCommission> findByDsaStaffIdOrderByIdDesc(Long dsaStaffId);

    List<DsaCommission> findByDsaStaffIdAndStatusOrderByIdDesc(Long dsaStaffId, DsaCommissionStatus status);
}
