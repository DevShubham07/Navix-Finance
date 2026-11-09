package com.navix.loan.entity;

import com.navix.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One NACH debit presentation attempt against a {@link Mandate} (V84). Per attempt: a retry is a new
 * row. The partial unique index on in-flight rows (SCHEDULED/IN_PROGRESS) per loan is what makes a
 * double debit impossible; money is integer paise.
 */
@Entity
@Table(name = "mandate_debit")
@Getter
@Setter
@NoArgsConstructor
public class MandateDebit extends BaseAuditEntity {

    public enum Status { SCHEDULED, IN_PROGRESS, SUCCEEDED, FAILED, FAILED_TECHNICAL, CANCELLED, SKIPPED }

    public enum Trigger { SCHEDULED, MANUAL }

    @Column(name = "mandate_id", nullable = false)
    private Long mandateId;

    @Column(name = "loan_id", nullable = false)
    private Long loanId;

    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "digio_plan_id", length = 64)
    private String digioPlanId;

    @Column(name = "digio_txn_id", length = 64)
    private String digioTxnId;

    @Column(name = "unique_key", nullable = false, length = 64)
    private String uniqueKey;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Column(name = "settlement_date")
    private LocalDate settlementDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private Status status;

    @Column(name = "digio_status", length = 40)
    private String digioStatus;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "utr", length = 64)
    private String utr;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Column(name = "dpd_at_attempt")
    private Integer dpdAtAttempt;

    @Enumerated(EnumType.STRING)
    @Column(name = "triggered_by", nullable = false, length = 16)
    private Trigger triggeredBy;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "overpayment", nullable = false)
    private boolean overpayment;

    /**
     * How many times THIS row has been presented to the provider (V86). A FAILED_TECHNICAL row is
     * re-presented with its original {@code unique_key} and {@code attempt_no}, so this counter is the
     * only thing that advances on the recovery path — and the only thing that can bound it.
     */
    @Column(name = "present_count", nullable = false)
    private int presentCount = 1;
}
