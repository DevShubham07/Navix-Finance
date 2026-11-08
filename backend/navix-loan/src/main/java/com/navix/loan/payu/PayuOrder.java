package com.navix.loan.payu;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One PayU checkout attempt for a loan (V93). Money is integer paise. */
@Entity
@Table(name = "payu_order")
@Getter
@Setter
@NoArgsConstructor
public class PayuOrder {

    public enum Status { INITIATED, PENDING, SUCCESS, FAILED, EXPIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "txnid", nullable = false, length = 25, updatable = false)
    private String txnid;

    @Column(name = "loan_id", nullable = false, updatable = false)
    private Long loanId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Column(name = "amount_paise", nullable = false, updatable = false)
    private long amountPaise;

    /** Exact string sent to PayU, null if none. */
    @Column(name = "additional_charges", length = 200, updatable = false)
    private String additionalCharges;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    @Column(name = "mihpayid", length = 40)
    private String mihpayid;

    @Column(name = "mode", length = 16)
    private String mode;

    @Column(name = "bankcode", length = 24)
    private String bankcode;

    @Column(name = "bank_ref_num", length = 120)
    private String bankRefNum;

    @Column(name = "fee_paise")
    private Long feePaise;

    @Column(name = "paid_on")
    private LocalDate paidOn;

    @Column(name = "error_code", length = 24)
    private String errorCode;

    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "overpayment", nullable = false)
    private boolean overpayment;

    @Column(name = "review_reason", length = 40)
    private String reviewReason;

    /** Set once a person has dealt with a review row (V94); never changes the money fields. */
    @Column(name = "review_resolved_at")
    private Instant reviewResolvedAt;

    @Column(name = "review_resolved_by")
    private Long reviewResolvedBy;

    @Column(name = "review_note", columnDefinition = "text")
    private String reviewNote;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    @Column(name = "next_check_at")
    private Instant nextCheckAt;

    @Column(name = "check_count", nullable = false)
    private int checkCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public boolean open() {
        return status == Status.INITIATED || status == Status.PENDING;
    }
}
