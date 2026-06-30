package com.navix.loan.entity;

import com.navix.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A borrower's notification preferences (Phase 2.2), keyed by the stable {@code applicant_id} (not
 * application_id) so one row survives across reborrows. Opt-ins default to {@code true}; the
 * notification engine suppresses an opted-out SMS/EMAIL channel. IN_APP is the inbox and is never gated.
 */
@Entity
@Table(name = "borrower_preferences")
@Getter
@Setter
@NoArgsConstructor
public class BorrowerPreferences extends BaseAuditEntity {

    @Column(name = "applicant_id", nullable = false, unique = true)
    private Long applicantId;

    @Column(name = "email_opt_in", nullable = false)
    private boolean emailOptIn = true;

    @Column(name = "sms_opt_in", nullable = false)
    private boolean smsOptIn = true;

    /** Marketing / pre-approved offers (informational; no separate channel today). */
    @Column(name = "offers_opt_in", nullable = false)
    private boolean offersOptIn = true;
}
