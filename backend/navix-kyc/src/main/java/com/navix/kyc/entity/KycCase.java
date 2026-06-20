package com.navix.kyc.entity;

import com.navix.common.entity.BaseAuditEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The overall KYC case for a single borrower, aggregating individual checks.
 * TODO: add status enum and link to the resulting {@code KycCheck} set.
 */
@Entity
@Table(name = "kyc_case")
@Getter
@Setter
public class KycCase extends BaseAuditEntity {

    /** FK to the borrower under verification. */
    private Long borrowerId;

    /** Overall KYC outcome. TODO: promote to enum (PENDING/PASSED/FAILED). */
    private String status;
}
