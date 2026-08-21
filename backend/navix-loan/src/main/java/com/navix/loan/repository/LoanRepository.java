package com.navix.loan.repository;

import com.navix.loan.domain.LoanStatus;
import com.navix.loan.entity.Loan;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@link Loan}.
 */
@Repository
public interface LoanRepository extends JpaRepository<Loan, Long> {

    List<Loan> findByCustomerId(Long customerId);

    /** How many loans this customer has ever taken — the DSA-commission "first loan only" guard. */
    long countByCustomerId(Long customerId);

    /** Live loans in any of the given statuses (the daily payment-reminder sweep). */
    List<Loan> findByStatusIn(Collection<LoanStatus> statuses);

    /** Loans eligible for collections: a given set of statuses, due on or before {@code asOf}. */
    List<Loan> findByStatusInAndDueDateLessThanEqualOrderByDueDateAsc(
            Collection<LoanStatus> statuses, LocalDate asOf);

    /**
     * Every loan (any status), optionally windowed by {@code disbursed_on}. Backs the staff loan
     * register ({@code LoanRegisterService}), which does status/search filtering itself after
     * enrichment (name/PAN live on the customer profile, not the loan). Both bounds are optional and
     * independent (either, both, or neither may be null) — a single JPQL query with null-coalescing
     * predicates avoids a matrix of derived-query method names for from-only/to-only/both/neither.
     *
     * <p>The bounds are wrapped in {@code cast(... as date)} for Postgres, which cannot infer a bare
     * parameter's type in {@code :param is null} and fails the whole statement with
     * "could not determine data type of parameter". The cast is what makes the null-coalescing form
     * usable at all here; without it this query only works when both bounds are supplied.
     */
    @Query("select l from Loan l where (cast(:from as date) is null or l.disbursedOn >= :from) "
            + "and (cast(:to as date) is null or l.disbursedOn <= :to)")
    List<Loan> findAllForRegister(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
