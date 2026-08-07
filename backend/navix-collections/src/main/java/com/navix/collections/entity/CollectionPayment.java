package com.navix.collections.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A payment a borrower in collections made, as recorded by the Collection Executive (or Head) who
 * took it — and validated by the Accountant before it moves a rupee (V47; revamp.md decisions 43,
 * 44).
 *
 * <p>Its own table rather than a flag on the loan ledger's {@code payment}, because it is a
 * collections artefact with its own approval chain: it exists from the moment the officer records
 * it, but only becomes a ledger payment when the Accountant validates it. {@link #ledgerPaymentId}
 * is that link, written once at validation and never again — it is also the idempotency guard, so a
 * double-click can't credit the loan twice.
 */
@Entity
@Table(name = "collection_payment")
@Getter
@Setter
@NoArgsConstructor
public class CollectionPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "collection_case_id", nullable = false)
    private UUID collectionCaseId;

    @Column(name = "loan_id", nullable = false)
    private Long loanId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private CollectionPaymentKind kind;

    /** What the borrower paid, in paise. */
    @Column(name = "amount_paise", nullable = false)
    private Long amountPaise;

    /** When the borrower actually paid — not when this row was raised or validated. */
    @Column(name = "paid_on")
    private LocalDate paidOn;

    @Column(name = "txn_ref", length = 120)
    private String txnRef;

    /** Flexible collections proof: a screenshot key, a UTR, or the officer's note. */
    @Column(name = "proof_ref", length = 1000)
    private String proofRef;

    /** The approved settlement this payment settles; set only for {@code SETTLEMENT}. */
    @Column(name = "settlement_id")
    private UUID settlementId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private CollectionPaymentStatus status = CollectionPaymentStatus.PENDING_ACCOUNTANT;

    /** The collections staff id that recorded it (FK to {@code staff_user.id}). */
    @Column(name = "raised_by", nullable = false)
    private Long raisedBy;

    @Column(name = "raised_at", nullable = false, updatable = false)
    private Instant raisedAt = Instant.now();

    /** The Accountant who validated or rejected it. */
    @Column(name = "validated_by")
    private Long validatedBy;

    @Column(name = "validated_at")
    private Instant validatedAt;

    /** The Accountant's remarks, notified back to the raising officer and the Collection Head. */
    @Column(name = "remarks")
    private String remarks;

    /** The {@code payment} row this created in the loan ledger; null until validated. */
    @Column(name = "ledger_payment_id")
    private Long ledgerPaymentId;
}
