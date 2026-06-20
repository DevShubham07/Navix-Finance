package com.navix.onboarding.entity;

import com.navix.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A secondary applicant (co-applicant) who shares repayment responsibility.
 * Typically required for higher-risk categories (C/D). Verified through the
 * same KYC / income path as the primary borrower.
 *
 * TODO: link to a reusable KYC/income verification flow; enforce the
 * co-applicant requirement based on RiskCategory.
 */
@Entity
@Table(name = "co_applicant")
@Getter
@Setter
@NoArgsConstructor
public class CoApplicant extends BaseAuditEntity {

    /** FK to the primary borrower this co-applicant supports. */
    @Column(name = "borrower_id", nullable = false)
    private Long borrowerId;

    @Column(length = 10)
    private String pan;

    @Column(length = 15)
    private String mobile;

    private String name;

    /** Relationship to the primary borrower (spouse, parent, etc.). */
    private String relationship;

    /** Whether the co-applicant has accepted shared repayment responsibility. */
    @Column(name = "shares_repayment", nullable = false)
    private boolean sharesRepayment;
}
