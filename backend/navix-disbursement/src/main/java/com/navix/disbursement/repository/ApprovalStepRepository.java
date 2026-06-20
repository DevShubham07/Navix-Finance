package com.navix.disbursement.repository;

import com.navix.disbursement.entity.ApprovalStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** Persistence for the maker-checker {@link ApprovalStep} audit trail. */
@Repository
public interface ApprovalStepRepository extends JpaRepository<ApprovalStep, UUID> {

    // TODO: implement query — all steps for a request, ordered by time, for the trail view.
    List<ApprovalStep> findByDisbursementRequestIdOrderByAtAsc(UUID disbursementRequestId);
}
