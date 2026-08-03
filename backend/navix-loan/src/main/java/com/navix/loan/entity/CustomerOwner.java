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
 * Sparse staff ownership of a customer (book of business). Natural PK on {@code customer_id} —
 * deliberately not a {@code BaseAuditEntity} (that class owns {@code @Id id}). No row = Unallocated.
 */
@Entity
@Table(name = "customer_owner")
@Getter
@Setter
@NoArgsConstructor
public class CustomerOwner {

    @Id
    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "owner_staff_id", nullable = false)
    private Long ownerStaffId;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt = Instant.now();
}
