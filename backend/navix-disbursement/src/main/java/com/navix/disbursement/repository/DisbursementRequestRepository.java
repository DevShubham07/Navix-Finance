package com.navix.disbursement.repository;

import com.navix.disbursement.entity.DisbursementRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Persistence for {@link DisbursementRequest}. */
@Repository
public interface DisbursementRequestRepository extends JpaRepository<DisbursementRequest, UUID> {

    // TODO: implement query — one disbursement request per loan.
    Optional<DisbursementRequest> findByLoanId(UUID loanId);
}
