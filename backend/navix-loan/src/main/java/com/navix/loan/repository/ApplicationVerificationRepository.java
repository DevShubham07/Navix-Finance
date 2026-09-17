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

    /** Batch-load verification rows for a set of applications — the pending-API dashboard's
     *  cross-application overview, scoped to the (small) undecided queue rather than every row
     *  ever recorded. Callers MUST short-circuit on an empty collection; {@code in ()} is not
     *  valid SQL. */
    List<ApplicationVerification> findByApplicationIdIn(Collection<Long> applicationIds);

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
     * Everything {@code VerificationFailureService} needs to classify why an application has no
     * usable credit decision, for many applications at once.
     *
     * <p>A projection, so {@code raw_response} — a full credit report on bureau rows — never leaves
     * Postgres for a list view. {@code providerTxnId} is here for one specific reason: on a Fintrix
     * CRIF no-hit it is the report id, and the pre-fix rule that discarded a real report for having
     * an out-of-band score still recorded that id while a genuine thin file has none. It is what
     * separates the 47 recoverable reports from the 84 true no-hits without reading the blob.
     */
    interface CaseFailureRow {
        Long getApplicationId();
        String getCheckType();
        String getStatus();
        String getDerived();
        String getMessage();
        Long getScore();
        String getProvider();
        String getProviderTxnId();
    }

    List<CaseFailureRow> findByApplicationIdInAndCheckTypeIn(Collection<Long> applicationIds,
                                                             Collection<String> checkTypes);

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

    /**
     * EMPLOYMENT checks a VENDOR OUTAGE parked, on applications that still need a decision — the
     * scheduled re-run's work list.
     *
     * <p>A 27-hour Digitap balance outage left 173 of these in the Sep-2026 window and nothing ever
     * looked at them again; another 145 sit behind an intermittent EPFO "source is busy". None of
     * those outcomes is billed, and the outcome we want from a re-run (a resolved record) is the one
     * that is — so retrying is both cheap and the only way the borrower's file ever gets the data.
     *
     * <p>Native, because the filter is on {@code jsonb} keys. The predicates are deliberately narrow:
     * <ul>
     *   <li>only {@code providerErrorCode}s that describe the VENDOR failing — never {@code HTTP_400},
     *       which means our own request was wrong and would fail identically forever;</li>
     *   <li>exponential spacing (1h, 2h, 4h, 8h, 16h) so five attempts span a day and a half rather
     *       than hammering a provider that is still down;</li>
     *   <li>undecided applications only — a sanctioned or closed file does not need the answer, and
     *       a successful re-run is billable.</li>
     * </ul>
     */
    @Query(value = """
            select v.* from application_verification v
              join loan_application a on a.id = v.application_id
             where v.check_type = 'EMPLOYMENT'
               and v.status = 'REVIEW'
               and v.derived ->> 'providerError' = 'true'
               and v.derived ->> 'providerErrorCode' in (:codes)
               and coalesce((v.derived ->> 'retryCount')::int, 0) < :maxRetries
               and v.updated_at < now() - (interval '1 hour'
                     * power(2, coalesce((v.derived ->> 'retryCount')::int, 0)))
               and a.status in (:appStatuses)
             order by v.updated_at asc
             limit :limit
            """, nativeQuery = true)
    List<ApplicationVerification> findEmploymentRetryCandidates(@Param("codes") Collection<String> codes,
                                                                @Param("maxRetries") int maxRetries,
                                                                @Param("appStatuses") Collection<String> appStatuses,
                                                                @Param("limit") int limit);
}
