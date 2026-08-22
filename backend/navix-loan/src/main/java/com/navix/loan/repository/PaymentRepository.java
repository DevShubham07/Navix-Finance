package com.navix.loan.repository;

import com.navix.loan.domain.PaymentStatus;
import com.navix.loan.entity.Payment;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Persistence for {@link Payment}. */
@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByLoanId(Long loanId);

    /** Accountant queue: repayments awaiting proof verification, oldest first. */
    List<Payment> findByStatusOrderByIdAsc(PaymentStatus status);

    /** Idempotency guard: a transaction reference is unique per loan. */
    Optional<Payment> findFirstByLoanIdAndTxnRef(Long loanId, String txnRef);

    /** Total paid against a loan in a given status (paise); 0 when none. */
    @Query("select coalesce(sum(p.amount), 0) from Payment p "
            + "where p.loanId = :loanId and p.status = :status")
    long sumAmountByLoanIdAndStatus(@Param("loanId") Long loanId, @Param("status") PaymentStatus status);

    /**
     * The batched form of {@link #sumAmountByLoanIdAndStatus} — one grouped query for a whole set of
     * loan ids instead of one query per loan. Backs {@code RepaymentService#outstandingForAll}, which
     * a 50-row loan register would otherwise hit with 50 separate sum queries. A loan with no payments
     * in {@code status} is simply absent from the result (no zero row) — callers default-to-zero on lookup.
     */
    @Query("select p.loanId as loanId, sum(p.amount) as total from Payment p "
            + "where p.loanId in :loanIds and p.status = :status group by p.loanId")
    List<LoanAmount> sumAmountByLoanIdInAndStatus(
            @Param("loanIds") Collection<Long> loanIds, @Param("status") PaymentStatus status);

    /** Projection for {@link #sumAmountByLoanIdInAndStatus} — one loan id and its summed amount (paise). */
    interface LoanAmount {
        Long getLoanId();

        Long getTotal();
    }

    /**
     * Every payment decided (verified or rejected) by any of {@code staffIds} in the half-open
     * window {@code [from, to)} — backs the accountant-attribution columns on the staff-performance
     * summary (V59's {@code decided_by}/{@code decided_at}). Callers MUST short-circuit on an empty
     * collection — {@code in ()} is not valid SQL.
     */
    @Query("select p from Payment p "
            + "where p.decidedBy in :staffIds and p.decidedAt >= :from and p.decidedAt < :to")
    List<Payment> findDecidedByInWindow(@Param("staffIds") Collection<Long> staffIds,
                                        @Param("from") Instant from, @Param("to") Instant to);
}
