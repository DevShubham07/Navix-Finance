package com.navix.kyc.entity;

import com.navix.common.entity.BaseAuditEntity;
import com.navix.kyc.domain.KycCheckType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * A single check (PAN / AADHAAR / SELFIE / ADDRESS) within a {@link KycCase}.
 * TODO: link to KycCase, add raw-result payload and result enum.
 */
@Entity
@Table(name = "kyc_check")
@Getter
@Setter
public class KycCheck extends BaseAuditEntity {

    /** FK to the parent KYC case. */
    private Long kycCaseId;

    @Enumerated(EnumType.STRING)
    private KycCheckType type;

    /** Outcome of this check. TODO: promote to enum (PASS/FAIL/REVIEW). */
    private String result;

    /** Optional match/confidence score. */
    private BigDecimal score;
}
