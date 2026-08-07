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
 * The 12-hour cool-off a borrower earns after 3 failed penny drops (V46; revamp.md decision 40).
 *
 * <p>One row per customer (unique index, not the primary key — the surrogate id keeps this entity
 * shaped like every other one in the module). An expired {@link #lockedUntil} means the next 3
 * failures open a fresh window; a successful drop deletes the row outright.
 */
@Entity
@Table(name = "customer_penny_drop_lock")
@Getter
@Setter
@NoArgsConstructor
public class CustomerPennyDropLock extends BaseAuditEntity {

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "locked_until", nullable = false)
    private Instant lockedUntil;

    /** Failures counted into the window that produced this lock (kept for audit). */
    @Column(name = "failures", nullable = false)
    private Integer failures = 0;
}
