package com.navix.loan.repository;

import com.navix.loan.entity.Mandate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for eMandate attempts (V82). */
@Repository
public interface MandateRepository extends JpaRepository<Mandate, Long> {

    /** The customer's current mandate: newest attempt that the bank registered. */
    Optional<Mandate> findFirstByCustomerIdAndUmrnIsNotNullOrderByIdDesc(Long customerId);

    /** The newest ACTIVE mandate with a UMRN; unlike the finder above, a newer revoked row cannot hide it. */
    Optional<Mandate> findFirstByCustomerIdAndStatusAndUmrnIsNotNullOrderByIdDesc(Long customerId, Mandate.Status status);

    /** The latest attempt, whatever its outcome — its attemptNo seeds the next one. */
    Optional<Mandate> findFirstByCustomerIdOrderByAttemptNoDesc(Long customerId);

    /** The latest attempt made on one application (the override gate must not look at other advances). */
    Optional<Mandate> findFirstByLoanApplicationIdOrderByAttemptNoDesc(Long loanApplicationId);

    Optional<Mandate> findByCustomerRefNumber(String customerRefNumber);

    Optional<Mandate> findByDigioMandateId(String digioMandateId);
}
