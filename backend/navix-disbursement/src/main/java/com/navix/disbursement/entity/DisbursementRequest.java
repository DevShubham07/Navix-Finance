package com.navix.disbursement.entity;

import com.navix.disbursement.domain.DisbursementStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A request to disburse an approved loan. One per loan; tracks where the
 * request sits in the {@link DisbursementStatus} approval/transfer chain.
 *
 * TODO: wire fields for amount, beneficiary account snapshot, audit columns.
 */
@Entity
@Table(name = "disbursement_request")
@Getter
@Setter
@NoArgsConstructor
public class DisbursementRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "loan_id", nullable = false)
    private UUID loanId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private DisbursementStatus status = DisbursementStatus.PENDING_CREDIT_REVIEW;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt;
}
