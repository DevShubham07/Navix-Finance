package com.navix.loan.repository;

import com.navix.loan.entity.Payment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@link Payment}.
 *
 * TODO: add an aggregate sum-paid query to help compute the outstanding balance.
 */
@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByLoanId(Long loanId);
}
