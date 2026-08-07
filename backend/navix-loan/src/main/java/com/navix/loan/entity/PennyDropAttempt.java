package com.navix.loan.entity;

import com.navix.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One penny-drop attempt (V46). Recorded rather than counted so the 3-strikes rule
 * (revamp.md decision 40) is auditable — a bare counter can't answer "against which account?".
 */
@Entity
@Table(name = "penny_drop_attempt")
@Getter
@Setter
@NoArgsConstructor
public class PennyDropAttempt extends BaseAuditEntity {

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "application_id")
    private Long applicationId;

    @Column(name = "account_number", length = 32)
    private String accountNumber;

    @Column(name = "ifsc", length = 16)
    private String ifsc;

    @Column(name = "succeeded", nullable = false)
    private Boolean succeeded = Boolean.FALSE;

    @Column(name = "failure_reason", length = 300)
    private String failureReason;
}
