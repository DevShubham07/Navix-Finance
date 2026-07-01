package com.navix.loan.entity;

import com.navix.common.entity.BaseAuditEntity;
import com.navix.loan.domain.ReferralStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A captured referrer→referred relationship. Created {@code PENDING} when a new borrower redeems a
 * code at signup; flips to {@code QUALIFIED} (with the qualifying loan) when that borrower's first
 * loan is disbursed, at which point two {@link ReferralPayout} rows are created. Unique on
 * {@code referred_customer_id} — a person can be referred at most once.
 */
@Entity
@Table(name = "referral")
@Getter
@Setter
@NoArgsConstructor
public class Referral extends BaseAuditEntity {

    /** The customer who shared the code (the reward's first beneficiary). */
    @Column(name = "referrer_customer_id", nullable = false)
    private Long referrerCustomerId;

    /** The new borrower who redeemed the code (unique — referred only once). */
    @Column(name = "referred_customer_id", nullable = false)
    private Long referredCustomerId;

    /** The code that was redeemed (snapshot — the referrer's {@link ReferralCode#getCode()}). */
    @Column(name = "code_used", nullable = false, length = 16)
    private String codeUsed;

    /** Lifecycle state. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ReferralStatus status = ReferralStatus.PENDING;

    /** The referred borrower's first disbursed loan that qualified this referral (null until then). */
    @Column(name = "qualifying_loan_id")
    private Long qualifyingLoanId;

    /** When the referral qualified (the referred loan's disbursement), or null. */
    @Column(name = "qualified_at")
    private Instant qualifiedAt;
}
