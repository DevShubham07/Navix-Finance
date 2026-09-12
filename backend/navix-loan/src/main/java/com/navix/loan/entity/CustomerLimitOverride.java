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
 * An ADMIN-set eligible limit for one customer, overriding the 25%-of-salary rule (V69). Natural PK
 * on {@code customer_id} — deliberately not a {@code BaseAuditEntity} (that class owns {@code @Id
 * id}), matching {@link CustomerOwner}. No row = the salary rule applies.
 *
 * <p>Stored per customer rather than per application because the limit is re-derived from salary on
 * payslip verification, on a salary edit, and on every reborrow — an override written onto a single
 * {@code loan_application} row would be overwritten by any of those.
 */
@Entity
@Table(name = "customer_limit_override")
@Getter
@Setter
@NoArgsConstructor
public class CustomerLimitOverride {

    @Id
    @Column(name = "customer_id")
    private Long customerId;

    /** The granted limit in integer paise. No upper ceiling; the service enforces the ₹1,000 floor. */
    @Column(name = "limit_paise", nullable = false)
    private Long limitPaise;

    @Column(name = "note")
    private String note;

    /** The staff id that set it, for the audit trail. */
    @Column(name = "set_by")
    private Long setBy;

    @Column(name = "set_at", nullable = false)
    private Instant setAt = Instant.now();
}
