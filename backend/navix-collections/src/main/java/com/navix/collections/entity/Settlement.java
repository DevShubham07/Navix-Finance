package com.navix.collections.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * A partial settlement proposal on a collection case. An officer proposes it and
 * the Collections Head must approve <i>or reject</i> it (maker-checker). Records the
 * agreed settlement amount and the proposal/approval/rejection metadata; {@link #status}
 * is the source of truth for the maker-checker state.
 */
@Entity
@Table(name = "settlement")
@Getter
@Setter
@NoArgsConstructor
public class Settlement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "collection_case_id", nullable = false)
    private UUID collectionCaseId;

    /** Agreed settlement amount, in paise. */
    @Column(name = "settlement_amount", nullable = false)
    private Long settlementAmount;

    /** Maker-checker state: PROPOSED until the Head approves or rejects it. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SettlementStatus status = SettlementStatus.PROPOSED;

    /** Collections officer who proposed it (FK to {@code staff_user.id}, a bigint). */
    @Column(name = "proposed_by", nullable = false)
    private Long proposedBy;

    /** Collections Head who approved (FK to {@code staff_user.id}); null until approved. */
    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "approved_at")
    private Instant approvedAt;

    /** Collections Head who rejected (FK to {@code staff_user.id}); null unless rejected. */
    @Column(name = "rejected_by")
    private Long rejectedBy;

    @Column(name = "rejected_at")
    private Instant rejectedAt;
}
