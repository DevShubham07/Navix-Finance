package com.navix.loan.repository;

import com.navix.loan.entity.MandateDebit;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/** Persistence for NACH debit presentations (V84). */
@Repository
public interface MandateDebitRepository extends JpaRepository<MandateDebit, Long> {

    Optional<MandateDebit> findByUniqueKey(String uniqueKey);

    /** The latest attempt for a loan — its attemptNo seeds the next one. */
    Optional<MandateDebit> findFirstByLoanIdOrderByAttemptNoDesc(Long loanId);

    List<MandateDebit> findByStatusIn(Collection<MandateDebit.Status> statuses);

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
