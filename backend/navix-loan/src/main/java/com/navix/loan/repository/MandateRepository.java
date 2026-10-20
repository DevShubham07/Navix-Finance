package com.navix.loan.repository;

import com.navix.loan.entity.Mandate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/** Persistence for eMandate attempts (V82). */
@Repository
public interface MandateRepository extends JpaRepository<Mandate, Long> {

    /** The newest ACTIVE mandate with a UMRN; a newer revoked row cannot hide it. */
    Optional<Mandate> findFirstByCustomerIdAndStatusAndUmrnIsNotNullOrderByIdDesc(Long customerId, Mandate.Status status);

    /** The latest attempt, whatever its outcome — its attemptNo seeds the next one. */
    Optional<Mandate> findFirstByCustomerIdOrderByAttemptNoDesc(Long customerId);

    /** The latest attempt made on one application (the override gate must not look at other advances). */
    Optional<Mandate> findFirstByLoanApplicationIdOrderByAttemptNoDesc(Long loanApplicationId);

    /** Every attempt this customer has ever made, newest first — the staff history. */
    java.util.List<Mandate> findByCustomerIdOrderByAttemptNoDesc(Long customerId);

    /** Every attempt on one application, newest first — what init re-checks before filing another. */
    java.util.List<Mandate> findByLoanApplicationIdOrderByAttemptNoDesc(Long loanApplicationId);

    /** Attempts filed against ONE application — the cap that stops a borrower-driven create loop. */
    long countByLoanApplicationId(Long loanApplicationId);

    Optional<Mandate> findByCustomerRefNumber(String customerRefNumber);

    Optional<Mandate> findByDigioMandateId(String digioMandateId);

    /**
     * Serialises mandate registration for one customer until this transaction commits. Held for the whole
     * transaction, so a concurrent {@code init} waits rather than filing a second real NPCI mandate under
     * the same CRN. Works on a customer with no attempt yet — a row lock would not.
     */
    @Query(value = "select pg_advisory_xact_lock(?1)", nativeQuery = true)
    void lockCustomer(long customerId);
}
