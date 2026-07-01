package com.navix.income.repository;

import com.navix.income.entity.RiskAssessment;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@link RiskAssessment}.
 *
 * TODO: add finders for latest-assessment-per-customer once history is kept.
 */
@Repository
public interface RiskAssessmentRepository extends JpaRepository<RiskAssessment, Long> {

    Optional<RiskAssessment> findFirstByCustomerIdOrderByIdDesc(Long customerId);
}
