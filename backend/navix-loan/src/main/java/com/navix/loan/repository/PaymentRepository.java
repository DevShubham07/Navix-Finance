package com.navix.loan.repository;

import com.navix.loan.domain.PaymentStatus;
import com.navix.loan.entity.Payment;
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
}
