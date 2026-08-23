package com.navix.loan.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.verification.ProviderCallContext;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.domain.BureauBackfillCohort;
import com.navix.loan.domain.BureauBackfillOutcome;
import com.navix.loan.dto.BureauBackfillDtos.BackfillPreview;
import com.navix.loan.dto.BureauBackfillDtos.BackfillRunSummary;
import com.navix.loan.entity.ApplicationDocument;
import com.navix.loan.entity.ApplicationRejection;
import com.navix.loan.entity.ApplicationVerification;
import com.navix.loan.entity.BureauBackfillRow;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicationDocumentRepository;
import com.navix.loan.repository.ApplicationRejectionRepository;
import com.navix.loan.repository.ApplicationVerificationRepository;
import com.navix.loan.repository.BureauBackfillRowRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADMIN-only backfill that re-pulls the Fintrix CRIF bureau for the customers the old sub-600 floor
 * turned away, plus three "refresh in place" cohorts, once Fintrix drops the floor to 550
 * (plana.md Part B). Two phases:
 *
 * <ul>
 *   <li>{@link #preview()} — counts per cohort, zero provider calls. The number an operator approves
 *       before anything is spent.</li>
 *   <li>{@link #execute(BureauBackfillCohort, int)} — batched, resumable, capped at
 *       {@link #MAX_ROWS_PER_RUN} rows. One {@link BureauBackfillRow} per application per run;
 *       re-running skips every application whose LATEST row is already a non-{@code FAILED} outcome
 *       and retries only the {@code FAILED} ones — never a loop, one deliberate call at a time.</li>
 * </ul>
 *
 * <p><b>A refresh never auto-rejects.</b> Every call into {@link ApplicationVerificationService} here
 * passes {@code allowAutoReject=false} — a batch job must not knock a customer out of a live queue,
 * and {@code autoReject} would fail outright anyway (it requires the BORROWER role; this runs as
 * ADMIN). Only the REJECTS cohort ever changes an application's status, and only forward via
 * {@link ApplicationFlowService#reopenAfterRescore}, never a reject.
 *
 * <p><b>Not an HTTP request.</b> {@link ActorContext} and {@link ProviderCallContext} are normally
 * populated by servlet filters off the live request; a batch loop binds/clears them explicitly per
 * row so the audit trail and the provider-call dashboard attribute correctly (plana.md §9 warning).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BureauBackfillService {

    /**
     * Hard cap a single execute() call cannot exceed. Cohort sizes were unknown at write time (plan
     * §10 — the prod psql call was blocked); 500 is a conservative starting point.
     * ponytail: static cap, revisit once real cohort sizes are known from a preview run.
     */
    public static final int MAX_ROWS_PER_RUN = 500;

    private static final CurrentActor BACKFILL_ACTOR =
            new CurrentActor("system", "Bureau Rescore Backfill", "ADMIN");

    private final LoanApplicationRepository applicationRepo;
    private final ApplicationRejectionRepository rejectionRepo;
    private final CustomerProfileRepository profileRepo;
    private final ApplicationVerificationRepository verificationRepo;
    private final ApplicationDocumentRepository documentRepo;
    private final BureauBackfillRowRepository backfillRepo;
    private final ApplicationVerificationService verificationService;
    private final ApplicationFlowService flow;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public BackfillPreview preview() {
        requireAdmin();
        List<LoanApplication> rejects = rejectsCohortApplications();
        Instant now = Instant.now();
        long inside = 0;
        long outside = 0;
        for (LoanApplication app : rejects) {
            Instant blockedUntil = latestBlockedUntil(app.getId());
            if (blockedUntil != null && blockedUntil.isAfter(now)) {
                inside++;
            } else {
                outside++;
            }
        }
        Map<BureauBackfillCohort, Long> counts = new EnumMap<>(BureauBackfillCohort.class);
        counts.put(BureauBackfillCohort.REJECTS, (long) rejects.size());
        counts.put(BureauBackfillCohort.CREDIT_REVIEW,
                (long) cohortApplications(BureauBackfillCohort.CREDIT_REVIEW).size());
        counts.put(BureauBackfillCohort.PENDING_REVIEW,
                (long) cohortApplications(BureauBackfillCohort.PENDING_REVIEW).size());
        counts.put(BureauBackfillCohort.APPROVED,
                (long) cohortApplications(BureauBackfillCohort.APPROVED).size());
        return new BackfillPreview(counts, inside, outside);
    }

    /**
     * Not wrapped in a single transaction on purpose: each application is its own attempt (its own
     * pullBureau/reopen transaction underneath), so a crash mid-run leaves every completed row
     * committed and resumable rather than rolling the whole batch back.
     */
    public BackfillRunSummary execute(BureauBackfillCohort cohort, int limit) {
        requireAdmin();
        if (limit <= 0 || limit > MAX_ROWS_PER_RUN) {
            throw new BusinessException("BACKFILL_LIMIT_INVALID",
                    "limit must be between 1 and " + MAX_ROWS_PER_RUN);
        }
        String runId = java.util.UUID.randomUUID().toString();
        int processed = 0;
        for (LoanApplication app : cohortApplications(cohort)) {
            if (processed >= limit) {
                break;
            }
            if (alreadyTerminal(app.getId())) {
                continue;
            }
            processRow(runId, cohort, app);
            processed++;
        }
        return new BackfillRunSummary(runId, cohort, processed);
    }

    // ---- per-application attempt -----------------------------------------------------

    private void processRow(String runId, BureauBackfillCohort cohort, LoanApplication app) {
        Long appId = app.getId();
        Long customerId = app.getCustomerId();
        CustomerProfile before = profileRepo.findByApplicationId(appId).orElse(null);
        Long oldScore = before != null ? before.getBureauScore() : null;
        Instant priorBriefAt = before != null ? before.getCreditBriefGeneratedAt() : null;
        Long priorReportDocId = latestReportDocId(appId);

        CurrentActor original = ActorContext.get();
        ActorContext.set(BACKFILL_ACTOR);
        ProviderCallContext.setApplicationId(appId);
        try {
            ApplicationVerificationService.StepResult ignored;
            try {
                ignored = verificationService.pullBureau(appId, null, true, false);
            } catch (RuntimeException e) {
                saveRow(runId, cohort, appId, customerId, BureauBackfillOutcome.FAILED, oldScore, null,
                        "BUREAU_PULL", errorCode(e), errorDetail(e));
                return;
            }
            // Read the persisted row rather than `ignored`'s StepResult: pullBureau's returned
            // StepResult never carries score/provider (see its record's "short form" constructor),
            // so the row is the only reliable source of what actually happened.
            ApplicationVerification row = verificationRepo
                    .findByApplicationIdAndCheckType(appId, ApplicationVerificationService.BUREAU)
                    .orElse(null);
            if (row == null) {
                saveRow(runId, cohort, appId, customerId, BureauBackfillOutcome.FAILED, oldScore, null,
                        "BUREAU_PULL", "NO_VERIFICATION_ROW", "pullBureau left no BUREAU row");
                return;
            }

            Long newScore = row.getScore();
            if (!"PASS".equals(row.getStatus())) {
                // REVIEW: consent/DOB missing, a swallowed provider failure, or an identity mismatch —
                // finishBureauPull's only three REVIEW paths.
                Map<String, Object> derived = readDerived(row.getDerived());
                if (derived.containsKey("identityMismatch")) {
                    saveRow(runId, cohort, appId, customerId, BureauBackfillOutcome.MISMATCH_REVIEW,
                            oldScore, newScore, null, null, String.valueOf(derived.get("identityMismatch")));
                } else if (Boolean.TRUE.equals(derived.get("bureauChallenge"))) {
                    // A real, existing report gated behind a CRIF security question — not a failure, and
                    // not retryable (that's the whole point: stop burning a billable call every re-run).
                    saveRow(runId, cohort, appId, customerId, BureauBackfillOutcome.KBA_REQUIRED,
                            oldScore, newScore, null, null, row.getMessage());
                } else {
                    String code = derived.containsKey("providerErrorCode")
                            ? String.valueOf(derived.get("providerErrorCode"))
                            : derived.containsKey("missingProfileField")
                                    ? "MISSING_" + derived.get("missingProfileField") : "REVIEW";
                    saveRow(runId, cohort, appId, customerId, BureauBackfillOutcome.FAILED, oldScore,
                            newScore, "BUREAU_PULL", code, row.getMessage());
                }
                return;
            }

            CustomerProfile after = profileRepo.findByApplicationId(appId).orElse(null);
            boolean briefGenerated = after != null
                    && !Objects.equals(priorBriefAt, after.getCreditBriefGeneratedAt());
            if (!briefGenerated) {
                // Thin-file/no-hit: CreditBriefService.generate no-op'd on facts==null. A real answer,
                // not a failure — but distinguishable from a refresh that actually produced a brief.
                saveRow(runId, cohort, appId, customerId, BureauBackfillOutcome.NO_BRIEF, oldScore,
                        newScore, null, null, null);
                return;
            }

            String failedStep = Objects.equals(priorReportDocId, latestReportDocId(appId))
                    ? "PDF_INGEST" : null;

            if (cohort == BureauBackfillCohort.REJECTS) {
                if (newScore != null && newScore >= ApplicationFlowService.MIN_BUREAU_SCORE) {
                    reopenRejectedApplication(runId, appId, customerId, oldScore, newScore, failedStep);
                } else {
                    saveRow(runId, cohort, appId, customerId, BureauBackfillOutcome.STILL_BELOW,
                            oldScore, newScore, failedStep, null, null);
                }
            } else {
                saveRow(runId, cohort, appId, customerId, BureauBackfillOutcome.REFRESHED, oldScore,
                        newScore, failedStep, null, null);
            }
        } finally {
            ProviderCallContext.clear();
            ActorContext.set(original);
        }
    }

    private void reopenRejectedApplication(String runId, Long appId, Long customerId, Long oldScore,
                                           Long newScore, String failedStep) {
        String completenessNote = verificationService.allRequiredPassed(appId)
                ? null : "REQUIRED check set incomplete at reopen";
        ApplicationFlowService.ReopenOutcome reopenOutcome;
        try {
            reopenOutcome = flow.reopenAfterRescore(appId, oldScore, newScore, completenessNote);
        } catch (RuntimeException e) {
            saveRow(runId, BureauBackfillCohort.REJECTS, appId, customerId, BureauBackfillOutcome.FAILED,
                    oldScore, newScore, "REOPEN", errorCode(e), errorDetail(e));
            return;
        }
        BureauBackfillOutcome outcome = reopenOutcome == ApplicationFlowService.ReopenOutcome.REOPENED
                ? BureauBackfillOutcome.REOPENED : BureauBackfillOutcome.SKIPPED;
        saveRow(runId, BureauBackfillCohort.REJECTS, appId, customerId, outcome, oldScore, newScore,
                failedStep, null, null);
    }

    private void saveRow(String runId, BureauBackfillCohort cohort, Long appId, Long customerId,
                         BureauBackfillOutcome outcome, Long oldScore, Long newScore, String failedStep,
                         String errorCode, String errorDetail) {
        BureauBackfillRow row = new BureauBackfillRow();
        row.setRunId(runId);
        row.setApplicationId(appId);
        row.setCustomerId(customerId);
        row.setCohort(cohort.name());
        row.setOutcome(outcome.name());
        row.setOldScore(oldScore);
        row.setNewScore(newScore);
        row.setFailedStep(failedStep);
        row.setErrorCode(truncate(errorCode, 64));
        row.setErrorDetail(truncate(errorDetail, 500));
        row.setProviderExecutionId(ProviderCallContext.lastExecutionId());
        row.setAttemptedAt(Instant.now());
        backfillRepo.save(row);
        log.info("bureau backfill run={} cohort={} application={} outcome={} failedStep={}",
                runId, cohort, appId, outcome, failedStep);
    }

    // ---- cohort selection --------------------------------------------------------------

    private List<LoanApplication> cohortApplications(BureauBackfillCohort cohort) {
        return switch (cohort) {
            case REJECTS -> rejectsCohortApplications();
            case CREDIT_REVIEW ->
                    applicationRepo.findByStatusOrderByCreatedAtDescIdDesc(ApplicationStatus.CREDIT_EXEC_PENDING);
            case PENDING_REVIEW -> concat(
                    applicationRepo.findByStatusOrderByCreatedAtDescIdDesc(ApplicationStatus.REVIEW_PENDING),
                    applicationRepo.findByStatusOrderByCreatedAtDescIdDesc(ApplicationStatus.KYC_PENDING));
            case APPROVED -> concat(
                    applicationRepo.findByStatusOrderByCreatedAtDescIdDesc(ApplicationStatus.KYC_APPROVED),
                    applicationRepo.findByStatusOrderByCreatedAtDescIdDesc(ApplicationStatus.PRE_APPROVED),
                    applicationRepo.findByStatusOrderByCreatedAtDescIdDesc(ApplicationStatus.SANCTIONED));
        };
    }

    /** REJECTED applications with a LOW_BUREAU_SCORE rejection row — excludes MANUAL/SELF_EMPLOYED. */
    private List<LoanApplication> rejectsCohortApplications() {
        return rejectionRepo.findByReasonCodeOrderByIdDesc(ApplicationRejection.LOW_BUREAU_SCORE).stream()
                .map(ApplicationRejection::getApplicationId)
                .filter(Objects::nonNull)
                .distinct()
                .map(applicationRepo::findById)
                .flatMap(Optional::stream)
                .filter(a -> a.getStatus() == ApplicationStatus.REJECTED)
                .toList();
    }

    @SafeVarargs
    private static List<LoanApplication> concat(List<LoanApplication>... lists) {
        List<LoanApplication> out = new ArrayList<>();
        for (List<LoanApplication> l : lists) {
            out.addAll(l);
        }
        return out;
    }

    /** A re-run skips any application whose latest row is already a non-FAILED outcome. */
    private boolean alreadyTerminal(Long appId) {
        return backfillRepo.findFirstByApplicationIdOrderByIdDesc(appId)
                .map(row -> !BureauBackfillOutcome.FAILED.name().equals(row.getOutcome()))
                .orElse(false);
    }

    private Instant latestBlockedUntil(Long appId) {
        return rejectionRepo.findByApplicationIdAndReasonCode(appId, ApplicationRejection.LOW_BUREAU_SCORE)
                .stream()
                .map(ApplicationRejection::getBlockedUntil)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);
    }

    private Long latestReportDocId(Long appId) {
        return documentRepo.findFirstByApplicationIdAndDocTypeOrderByIdDesc(
                appId, ApplicationVerificationService.BUREAU_REPORT)
                .map(ApplicationDocument::getId)
                .orElse(null);
    }

    private Map<String, Object> readDerived(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String errorCode(RuntimeException e) {
        return e instanceof BusinessException be ? be.getCode() : e.getClass().getSimpleName();
    }

    private static String errorDetail(RuntimeException e) {
        return truncate(e.getMessage(), 500);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private void requireAdmin() {
        if (!"ADMIN".equals(ActorContext.get().role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "ADMIN required");
        }
    }
}
