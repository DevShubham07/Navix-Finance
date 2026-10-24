package com.navix.loan.entity;

import com.navix.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Every rejection — automatic or manual — in one register for the admin console (V44).
 * The borrower always sees the same neutral message; the reason lives here, staff-only.
 * {@link #applicationId} is null when a re-attempt is refused before a draft exists.
 */
@Entity
@Table(name = "application_rejection")
@Getter
@Setter
@NoArgsConstructor
public class ApplicationRejection extends BaseAuditEntity {

    public static final String SELF_EMPLOYED = "SELF_EMPLOYED";
    public static final String PAST_DELINQUENCY = "PAST_DELINQUENCY";
    public static final String LOW_BUREAU_SCORE = "LOW_BUREAU_SCORE";
    public static final String MANUAL = "MANUAL";
    /**
     * Engine rule (V75): the Aadhaar number the borrower typed does not match the masked Aadhaar the
     * PAN record or DigiLocker returned for the same person (compared on the digits the mask reveals). The identity
     * documents disagree with each other, so the file is rejected outright rather than parked.
     */
    public static final String FRAUD_REJECTED = "FRAUD_REJECTED";
    /**
     * Engine rule: at submit-kyc the Aadhaar number typed is already on a submitted application of another
     * customer. Permanent block ({@link #INDEFINITE}); {@link #reasonDetail} names the earlier customer
     * and application.
     */
    public static final String AADHAAR_DUPLICATE = "AADHAAR_DUPLICATE";
    /**
     * A staffer blocked the customer for future loans (decided when a repayment is verified, or from
     * the customer page). Not a cooling-off: {@link #blockedUntil} is {@link #INDEFINITE} until an
     * ADMIN lifts it, which sets it to the moment of the unblock.
     */
    public static final String BLOCKED_BY_STAFF = "BLOCKED_BY_STAFF";
    /** The far-future {@link #blockedUntil} that means "until a staffer says otherwise". */
    public static final Instant INDEFINITE =
            java.time.LocalDate.of(9999, 12, 31).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();

    @Column(name = "application_id")
    private Long applicationId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /** The mobile the cooling-off block is keyed on (revamp.md decision 20). */
    @Column(name = "mobile", length = 15)
    private String mobile;

    @Column(name = "reason_code", nullable = false, length = 32)
    private String reasonCode;

    @Column(name = "reason_detail", length = 500)
    private String reasonDetail;

    @Column(name = "auto", nullable = false)
    private Boolean auto = Boolean.TRUE;

    /** When the borrower may apply again; null = recorded but not blocking. */
    @Column(name = "blocked_until")
    private Instant blockedUntil;

    /** True for a staff block with no end date (year 9999), as opposed to a dated cooling-off. */
    public boolean isIndefinite() {
        return blockedUntil != null && blockedUntil.atZone(java.time.ZoneOffset.UTC).getYear() >= 9999;
    }
}
