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
import java.time.LocalDate;
import java.util.UUID;

/**
 * A hardship-driven revised repayment plan for a collection case. Proposed by an
 * officer and Head-approved (maker-checker). Captures the revised due date /
 * instalment structure agreed with a borrower facing hardship.
 *
 * TODO: model instalment schedule rows if a multi-instalment plan is required.
 */
@Entity
@Table(name = "repayment_plan")
@Getter
@Setter
@NoArgsConstructor
public class RepaymentPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "collection_case_id", nullable = false)
    private UUID collectionCaseId;

    /** Revised single repayment date agreed under hardship. */
    @Column(name = "revised_due_date", nullable = false)
    private LocalDate revisedDueDate;

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
