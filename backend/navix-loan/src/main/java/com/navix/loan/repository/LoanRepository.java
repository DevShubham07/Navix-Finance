package com.navix.loan.repository;

import com.navix.loan.domain.LoanStatus;
import com.navix.loan.entity.Loan;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@link Loan}.
 */
@Repository
public interface LoanRepository extends JpaRepository<Loan, Long> {

    List<Loan> findByApplicantId(Long applicantId);

    /** Loans eligible for collections: a given set of statuses, due on or before {@code asOf}. */
    List<Loan> findByStatusInAndDueDateLessThanEqualOrderByDueDateAsc(
            Collection<LoanStatus> statuses, LocalDate asOf);
}
