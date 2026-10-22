package com.navix.loan.entity;

import com.navix.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One Digio NACH eMandate registration attempt (V82). Per attempt, not per customer: a retry is a new
 * row. {@code umrn} present means {@link Status#ACTIVE}. Only {@code accountLast4} is kept, never the
 * full account number.
 */
@Entity
@Table(name = "mandate")
@Getter
@Setter
@NoArgsConstructor
public class Mandate extends BaseAuditEntity {

    public enum Status { PENDING_AUTHORIZATION, ACTIVE, AUTHORIZATION_FAILED, EXPIRED, BANK_UNSUPPORTED }

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "loan_application_id")
    private Long loanApplicationId;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Column(name = "digio_mandate_id", nullable = false, length = 64)
    private String digioMandateId;

    @Column(name = "umrn", length = 64)
    private String umrn;

    /** {@code <customer_id>-<attempt_no>}, at most 20 chars. */
    @Column(name = "customer_ref_number", nullable = false, length = 20)
    private String customerRefNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private Status status;

    @Column(name = "digio_state", length = 40)
    private String digioState;

    @Column(name = "digio_bank_state", length = 40)
    private String digioBankState;

    @Column(name = "auth_mode", length = 24)
    private String authMode;

    @Column(name = "auth_sub_mode", length = 24)
    private String authSubMode;

    /** Per-instruction ceiling, integer paise. */
    @Column(name = "maximum_amount_paise", nullable = false)
    private long maximumAmountPaise;

    @Column(name = "first_collection_date")
    private LocalDate firstCollectionDate;

    @Column(name = "final_collection_date")
    private LocalDate finalCollectionDate;

    @Column(name = "bank_name", length = 100)
    private String bankName;

    @Column(name = "account_last4", length = 4)
    private String accountLast4;

    @Column(name = "ifsc", length = 11)
    private String ifsc;

    /**
     * The Digio authorisation url for this attempt. Held so {@code init} can return a borrower to an
     * attempt already in flight instead of filing another NPCI mandate — the duplicate-filing incident
     * of 2026-10-20 (docs/digio/13 section 13). Expires with the attempt; never surfaced to staff.
     */
    @Column(name = "authentication_url", length = 512)
    private String authenticationUrl;

    @Column(name = "reject_code", length = 64)
    private String rejectCode;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    @Column(name = "authenticated_at")
    private Instant authenticatedAt;

    @Column(name = "registered_at")
    private Instant registeredAt;

    /** Set when staff cancel the mandate at the provider (V88); a cancelled row is never re-adopted. */
    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_by", length = 200)
    private String cancelledBy;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;
}
