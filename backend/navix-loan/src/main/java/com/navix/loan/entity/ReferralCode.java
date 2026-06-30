package com.navix.loan.entity;

import com.navix.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One shareable referral code per applicant (lazily minted the first time the borrower views their
 * referral panel). Keyed by the durable {@code applicant_id} (mobile-derived), not a profile row.
 */
@Entity
@Table(name = "referral_code")
@Getter
@Setter
@NoArgsConstructor
public class ReferralCode extends BaseAuditEntity {

    /** The owning applicant (unique — one code per person). */
    @Column(name = "applicant_id", nullable = false)
    private Long applicantId;

    /** The shareable code (globally unique). */
    @Column(name = "code", nullable = false, length = 16)
    private String code;
}
