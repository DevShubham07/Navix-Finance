package com.navix.loan.entity;

import com.navix.common.entity.BaseAuditEntity;
import com.navix.loan.domain.DsaCommissionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One commission owed to one DSA for one lead's first disbursed loan (V55). There is exactly one
 * row per {@link com.navix.loan.entity.Lead} — {@code uq_dsa_commission_lead} is the DB-level
 * backstop that makes "only the first loan ever earns commission" unbreakable even if the
 * application-layer guard in {@code DsaCommissionService} has a bug. {@code netDisbursedPaise} and
 * {@code rateBps} are snapshotted at accrual so a later rate change never rewrites history.
 * Mirrors {@link ReferralPayout}'s accrual-ledger shape.
 */
@Entity
@Table(name = "dsa_commission")
@Getter
@Setter
@NoArgsConstructor
public class DsaCommission extends BaseAuditEntity {

    /** The DSA who earns this commission (real {@code staff_user.id}). */
    @Column(name = "dsa_staff_id", nullable = false)
    private Long dsaStaffId;

    /** The DSA lead this commission was earned for. Unique — one commission per lead, ever. */
    @Column(name = "lead_id", nullable = false)
    private Long leadId;

    /** The borrower whose loan qualified this commission (for context/display). */
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /** The attributed application (the lead's pinned, single earliest post-lead application). */
    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    /** The disbursed loan that earned this commission. */
    @Column(name = "loan_id", nullable = false)
    private Long loanId;

    /** Snapshot of {@code loan.netDisbursed} at accrual, in paise — the commission base. */
    @Column(name = "net_disbursed_paise", nullable = false)
    private long netDisbursedPaise;

    /** Snapshot of the commission rate in basis points (350 = 3.5%) at accrual. */
    @Column(name = "rate_bps", nullable = false)
    private int rateBps;

    /** The commission amount, in paise = round(netDisbursedPaise x rateBps / 10_000), HALF_UP. */
    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DsaCommissionStatus status = DsaCommissionStatus.ACCRUED;

    @Column(name = "accrued_at", nullable = false)
    private Instant accruedAt;

    /** When the loan closed clean and this flipped ACCRUED -> PAYABLE. Null until then. */
    @Column(name = "payable_at")
    private Instant payableAt;

    /** When ADMIN settled this commission. Null until PAID. */
    @Column(name = "paid_at")
    private Instant paidAt;

    /** Bank/UPI transaction reference ADMIN logged when marking this paid. */
    @Column(name = "txn_ref", length = 64)
    private String txnRef;

    /** The ADMIN who paid it (their name), or null. */
    @Column(name = "paid_by", length = 160)
    private String paidBy;

    /** Why this commission was voided (settlement / default / write-off / ADMIN override). */
    @Column(name = "void_reason", length = 240)
    private String voidReason;

    @Column(name = "voided_at")
    private Instant voidedAt;
}
