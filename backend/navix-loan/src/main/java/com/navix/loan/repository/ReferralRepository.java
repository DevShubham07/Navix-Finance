package com.navix.loan.repository;

import com.navix.loan.domain.ReferralStatus;
import com.navix.loan.entity.Referral;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data JPA repository for {@link Referral} (the referrer→referred relationship). */
public interface ReferralRepository extends JpaRepository<Referral, Long> {

    /** The referral that names this borrower as the referred party (unique — referred once). */
    Optional<Referral> findByReferredApplicantId(Long referredApplicantId);

    /** All referrals where this borrower is the referrer (for their earnings roll-up). */
    List<Referral> findByReferrerApplicantId(Long referrerApplicantId);

    /** Count this referrer's referrals in a given state (e.g. QUALIFIED for the earnings card). */
    long countByReferrerApplicantIdAndStatus(Long referrerApplicantId, ReferralStatus status);
}
