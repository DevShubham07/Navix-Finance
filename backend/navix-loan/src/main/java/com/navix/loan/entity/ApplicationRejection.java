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
    public static final String MANUAL = "MANUAL";

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
}
