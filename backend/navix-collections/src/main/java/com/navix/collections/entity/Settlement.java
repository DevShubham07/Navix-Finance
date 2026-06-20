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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A partial settlement proposal on a collection case. An officer proposes it and
 * the Collections Head must approve it (maker-checker). Records the agreed
 * settlement amount and approval metadata.
 *
 * TODO: add status enum (PROPOSED/APPROVED/REJECTED) if richer flow is needed.
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

    @Column(name = "settlement_amount", nullable = false)
    private BigDecimal settlementAmount;

    @Column(name = "proposed_by", nullable = false)
    private UUID proposedBy;

    /** Collections Head who approved; null until approved. */
    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "approved_at")
    private Instant approvedAt;
}
