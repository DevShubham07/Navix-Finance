package com.navix.loan.repository;

import com.navix.loan.entity.Loan;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@link Loan}.
 *
 * TODO: add finders for due/overdue loans to drive collections jobs.
 */
@Repository
public interface LoanRepository extends JpaRepository<Loan, Long> {

    List<Loan> findByApplicantId(Long applicantId);
}
