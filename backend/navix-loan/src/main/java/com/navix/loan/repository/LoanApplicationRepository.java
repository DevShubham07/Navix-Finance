package com.navix.loan.repository;

import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.entity.LoanApplication;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for {@link LoanApplication} — the application aggregate. */
@Repository
public interface LoanApplicationRepository extends JpaRepository<LoanApplication, Long> {

    List<LoanApplication> findByCustomerId(Long customerId);

    /** The application that minted a given loan (set at activation); for loan→borrower resolution. */
    Optional<LoanApplication> findByLoanId(Long loanId);

    /** Drives the staff stage queues (e.g. all KYC_PENDING, all CREDIT_HEAD_PENDING). */
    List<LoanApplication> findByStatusOrderByIdAsc(ApplicationStatus status);

    List<LoanApplication> findByAssignedExecutiveIdAndStatusOrderByIdAsc(Long assignedExecutiveId, ApplicationStatus status);
}
