package com.navix.collections.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * An overdue loan being worked by collections. The current DPD bucket is
 * computed live from the due date and is NOT a stored source of truth — the
 * {@code currentBucket} column, if used, is a denormalised snapshot only.
 *
 * TODO: confirm whether currentBucket is materialised or always recomputed.
 */
@Entity
@Table(name = "collection_case")
@Getter
@Setter
@NoArgsConstructor
public class CollectionCase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "loan_id", nullable = false)
    private UUID loanId;

    /** Denormalised snapshot of the live-computed DPD bucket; not authoritative. */
    @Column(name = "current_bucket")
    private String currentBucket;

    @Column(name = "assigned_officer_id")
    private UUID assignedOfficerId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
