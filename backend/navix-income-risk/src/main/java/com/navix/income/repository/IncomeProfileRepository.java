package com.navix.income.repository;

import com.navix.income.entity.IncomeProfile;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@link IncomeProfile}.
 *
 * TODO: add custom finders as the verification flow needs them.
 */
@Repository
public interface IncomeProfileRepository extends JpaRepository<IncomeProfile, Long> {

    Optional<IncomeProfile> findByApplicantId(Long applicantId);
}
