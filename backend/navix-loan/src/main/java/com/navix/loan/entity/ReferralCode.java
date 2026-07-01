package com.navix.loan.entity;

import com.navix.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One shareable referral code per customer (lazily minted the first time the borrower views their
 * referral panel). Keyed by the durable {@code customer_id} (mobile-derived), not a profile row.
 */
@Entity
@Table(name = "referral_code")
@Getter
@Setter
@NoArgsConstructor
public class ReferralCode extends BaseAuditEntity {

    /** The owning customer (unique — one code per person). */
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /** The shareable code (globally unique). */
    @Column(name = "code", nullable = false, length = 16)
    private String code;
}
