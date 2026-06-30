package com.navix.loan.repository;

import com.navix.loan.domain.ReferralPayoutStatus;
import com.navix.loan.entity.ReferralPayout;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data JPA repository for {@link ReferralPayout} (the ₹-reward rows). */
public interface ReferralPayoutRepository extends JpaRepository<ReferralPayout, Long> {

    /** Payouts in a given state, oldest first (the Disbursement-Head approval queue). */
    List<ReferralPayout> findByStatusOrderByIdAsc(ReferralPayoutStatus status);

    /** Every payout, newest first (the referral-expense view / unfiltered list). */
    List<ReferralPayout> findAllByOrderByIdDesc();

    /** A borrower's own reward payouts (for their earnings card). */
    List<ReferralPayout> findByBeneficiaryApplicantId(Long beneficiaryApplicantId);
}
