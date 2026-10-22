package com.navix.loan.repository;

import com.navix.loan.entity.MandateDebit;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Persistence for NACH debit presentations (V84). */
@Repository
public interface MandateDebitRepository extends JpaRepository<MandateDebit, Long> {

    Optional<MandateDebit> findByUniqueKey(String uniqueKey);

    /** The latest attempt for a loan — its attemptNo seeds the next one. */
    Optional<MandateDebit> findFirstByLoanIdOrderByAttemptNoDesc(Long loanId);

    /**
     * Debits past their settlement date, least-recently-ATTEMPTED first, one bounded page for the reconcile
     * sweep. SEMANTIC SHIFT: for these rows {@code updated_at} means "last time we attempted this row", not
     * "last state change" (see {@link #touch}). A never-touched row falls back to created_at.
     */
    @Query("select d from MandateDebit d where d.status in ?1 and d.settlementDate <= ?2 "
            + "order by coalesce(d.updatedAt, d.createdAt), d.settlementDate, d.id")
    List<MandateDebit> findDueForReconcile(Collection<MandateDebit.Status> statuses, LocalDate asOf, Pageable page);

    /**
     * Moves {@code updated_at} without touching any other column (a bulk update, so a concurrent outcome
     * write is never overwritten by a stale entity). Called when a reconcile attempt leaves a row unresolved,
     * so it rotates to the back of the queue instead of camping at the front.
     */
    @Modifying
    @Transactional
    @Query("update MandateDebit d set d.updatedAt = ?2 where d.id = ?1")
    void touch(Long id, Instant at);

    /** Every presentation on one loan, newest attempt first — the staff history for that loan. */
    List<MandateDebit> findByLoanIdOrderByAttemptNoDesc(Long loanId);

    /**
     * The refund register: debits that collected money from a borrower who no longer owed it (the loan
     * settled while the debit was in flight). Until this existed an overpayment was a boolean column and
     * one WARN line, so nobody could find the money owed back.
     */
    List<MandateDebit> findByOverpaymentTrueOrderByIdDesc();

    boolean existsByMandateIdAndStatusIn(Long mandateId, Collection<MandateDebit.Status> statuses);

    boolean existsByLoanIdAndStatusIn(Long loanId, Collection<MandateDebit.Status> statuses);

    /** Attempts that ended without collecting money; the retry cap counts only these. */
    long countByLoanIdAndStatusIn(Long loanId, Collection<MandateDebit.Status> statuses);

    /**
     * Serialises debit presentation for one loan until the transaction commits (taken before the in-flight
     * check). The caller passes a key already offset away from the customer-keyed lock in MandateRepository.
     */
    @Query(value = "select pg_advisory_xact_lock(?1)", nativeQuery = true)
    void lockLoan(long lockKey);
}
