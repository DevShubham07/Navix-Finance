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

    /** The latest attempt, whatever its outcome — its attemptNo seeds the next one. */
    Optional<Mandate> findFirstByCustomerIdOrderByAttemptNoDesc(Long customerId);

    Optional<Mandate> findByCustomerRefNumber(String customerRefNumber);

    Optional<Mandate> findByDigioMandateId(String digioMandateId);
}
