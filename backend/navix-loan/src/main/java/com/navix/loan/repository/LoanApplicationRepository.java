package com.navix.loan.repository;

import com.navix.loan.entity.LoanApplication;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@link LoanApplication}.
 *
 * TODO: add status/queue finders for the maker-checker review screens.
 */
@Repository
public interface LoanApplicationRepository extends JpaRepository<LoanApplication, Long> {

    List<LoanApplication> findByApplicantId(Long applicantId);
}
