package com.navix.loan.repository;

import com.navix.loan.entity.ReferralCode;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data JPA repository for {@link ReferralCode} (one shareable code per applicant). */
public interface ReferralCodeRepository extends JpaRepository<ReferralCode, Long> {

    Optional<ReferralCode> findByApplicantId(Long applicantId);

    Optional<ReferralCode> findByCode(String code);

    boolean existsByCode(String code);
}
