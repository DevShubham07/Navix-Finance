package com.navix.loan.repository;

import com.navix.loan.entity.ApplicationVerification;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Persistence for per-application external verification results (idempotent per check type). */
@Repository
public interface ApplicationVerificationRepository extends JpaRepository<ApplicationVerification, Long> {

    Optional<ApplicationVerification> findByApplicationIdAndCheckType(Long applicationId, String checkType);

    List<ApplicationVerification> findByApplicationIdOrderByIdAsc(Long applicationId);

    /**
     * Reverse lookup from a provider's own handle back to the application — used by provider callbacks,
     * which know their transaction and nothing else. A prefix match because a handle may pack more than
     * one provider id (the eSign row stores {@code contractId|signerId}).
     */
    /** Every row of one check type in one status — the bureau KBA outreach cohort. */
    List<ApplicationVerification> findByCheckTypeAndStatus(String checkType, String status);

    List<ApplicationVerification> findByCheckTypeAndProviderTxnIdStartingWith(
            String checkType, String providerTxnIdPrefix);

    /** Narrow projection for bulk state checks (e.g. {@code BureauStateService}) — avoids loading the
     *  (large) raw_response column just to read status/derived. */
    interface BureauStateRow {
        Long getApplicationId();
        String getStatus();
        String getDerived();
    }

    List<BureauStateRow> findByCheckTypeAndApplicationIdIn(String checkType, Collection<Long> applicationIds);

    /**
     * The customer's most recent PASSed row for a check type, across every application they have ever
     * filed — used to reuse a fresh bureau pull across a cancelled-and-restarted application instead of
     * re-pulling per application id. {@code Pageable} of size 1 stands in for a bare "top 1".
     */
    @Query("select v from ApplicationVerification v where v.checkType = :checkType and v.status = 'PASS' "
            + "and v.applicationId in :applicationIds order by v.updatedAt desc, v.createdAt desc")
    List<ApplicationVerification> findLatestPassed(@Param("checkType") String checkType,
                                                    @Param("applicationIds") Collection<Long> applicationIds,
                                                    Pageable pageable);
}
