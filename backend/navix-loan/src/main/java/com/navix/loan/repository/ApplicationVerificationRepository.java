package com.navix.loan.repository;

import com.navix.loan.entity.ApplicationVerification;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
