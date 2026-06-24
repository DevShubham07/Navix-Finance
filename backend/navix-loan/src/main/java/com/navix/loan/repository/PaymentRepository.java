package com.navix.loan.repository;

import com.navix.loan.domain.PaymentStatus;
import com.navix.loan.entity.Payment;
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

    /** Idempotency guard: a transaction reference is unique per loan. */
    Optional<Payment> findFirstByLoanIdAndTxnRef(Long loanId, String txnRef);

    /** Total paid against a loan in a given status (paise); 0 when none. */
    @Query("select coalesce(sum(p.amount), 0) from Payment p "
            + "where p.loanId = :loanId and p.status = :status")
    long sumAmountByLoanIdAndStatus(@Param("loanId") Long loanId, @Param("status") PaymentStatus status);
}
