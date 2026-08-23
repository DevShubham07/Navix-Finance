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
 * One row per application per bureau-backfill run (V62; plana.md Part B §9) — the outcome ledger a
 * re-run reads to skip already-processed applications and retry only {@code FAILED} ones.
 */
@Entity
@Table(name = "bureau_backfill_row")
@Getter
@Setter
@NoArgsConstructor
public class BureauBackfillRow extends BaseAuditEntity {

    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "cohort", nullable = false, length = 32)
    private String cohort;

    @Column(name = "outcome", nullable = false, length = 32)
    private String outcome;

    @Column(name = "old_score")
    private Long oldScore;

    @Column(name = "new_score")
    private Long newScore;

    /** Which call failed (BUREAU_PULL, PDF_INGEST, BRIEF_GENERATE, REOPEN, NOTIFY); null on a clean row. */
    @Column(name = "failed_step", length = 32)
    private String failedStep;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_detail", length = 500)
    private String errorDetail;

    /** Links to the {@code provider_api_execution} row for the raw request/response, when there is one. */
    @Column(name = "provider_execution_id")
    private Long providerExecutionId;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;
}
