package com.navix.loan.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * An ADMIN-set eMandate ceiling for one customer, replacing the 2 x max(limit, sanctioned) formula for
 * NEW registrations (V89). Changes only what the bank mandate is registered for — never what the
 * customer may borrow (that is {@link CustomerLimitOverride}). No row = the formula applies.
 */
@Entity
@Table(name = "customer_mandate_ceiling_override")
@Getter
@Setter
@NoArgsConstructor
public class CustomerMandateCeilingOverride {

    @Id
    @Column(name = "customer_id")
    private Long customerId;

    /** The ceiling in integer paise. Bounds are enforced in MandateService. */
    @Column(name = "ceiling_paise", nullable = false)
    private Long ceilingPaise;

    @Column(name = "note")
    private String note;

    /** Who set it ("name (role, id N)"), for the audit trail. */
    @Column(name = "set_by", length = 200)
    private String setBy;

    @Column(name = "set_at", nullable = false)
    private Instant setAt = Instant.now();
}
