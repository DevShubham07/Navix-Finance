package com.navix.loan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.exception.BusinessException;
import com.navix.common.notification.event.BureauQuestionPendingEvent;
import com.navix.common.security.ActorContext;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.entity.ApplicationVerification;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicationVerificationRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADMIN-triggered outreach to borrowers whose bureau report is parked behind an unanswered KBA
 * question (see {@code ApplicationVerificationService.answerBureauChallenge}).
 *
 * <p><b>Sends notifications only — it never calls Fintrix.</b> That is what makes it free to preview,
 * safe to re-run, and safe to cap. The billable part (minting a fresh question) is deliberately left
 * to the borrower's own click on the question screen, so we only ever spend on someone who actually
 * turned up.
 *
 * <p>Deliberately an ADMIN endpoint rather than a migration or a scheduler: mailing a cohort of real
 * customers is a customer-facing act that a human should time and watch. Mirrors
 * {@link BureauBackfillService} — same {@code requireAdmin()}, same capped-run shape, same
 * no-admin-screen/driven-by-curl convention.
 */
@Service
@RequiredArgsConstructor
public class BureauChallengeOutreachService {

    private static final Logger log = LoggerFactory.getLogger(BureauChallengeOutreachService.class);

    /** Hard cap on one run. The known cohort is 28; this leaves room without risking a mass send. */
    public static final int MAX_ROWS_PER_RUN = 200;

    /**
     * Only applications still genuinely in play are chased. A REJECTED file gains nothing from a
     * bureau score unless someone reopens it, and that is the rescore backfill's job, not this one —
     * in the first production cohort all 11 rejected ones were MANUAL decisions with a cooling-off
     * period, i.e. deliberate human calls we must not undercut with a "come back" email.
     */
    private static final ApplicationStatus ELIGIBLE_STATUS = ApplicationStatus.KYC_PENDING;

    private final ApplicationVerificationRepository verificationRepo;
    private final LoanApplicationRepository applicationRepo;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    /** One row per borrower who would be (or was) contacted. */
    public record OutreachRow(Long applicationId, Long customerId, boolean alreadyNotified) {
    }

    public record OutreachSummary(int eligible, int notified, int skippedAlreadyNotified,
                                  boolean dryRun, List<OutreachRow> rows) {
    }

    /** Zero sends, zero provider calls — run this first and eyeball the count. */
    @Transactional(readOnly = true)
    public OutreachSummary preview(int limit) {
        return collect(limit, true);
    }

    /**
     * Publishes {@link BureauQuestionPendingEvent} per eligible application. Delivery is IN_APP +
     * EMAIL (no SMS — the DLT templates cannot carry a variable link); the engine is async and
     * fire-and-forget, so a delivery failure never fails this run.
     */
    @Transactional
    public OutreachSummary notifyPending(int limit) {
        return collect(limit, false);
    }

    private OutreachSummary collect(int limit, boolean dryRun) {
        requireAdmin();
        if (limit <= 0 || limit > MAX_ROWS_PER_RUN) {
            throw new BusinessException("OUTREACH_LIMIT_INVALID",
                    "limit must be between 1 and " + MAX_ROWS_PER_RUN);
        }
        List<OutreachRow> rows = new ArrayList<>();
        int notified = 0;
        int skipped = 0;

        // The cohort is tens of rows, so filtering parsed `derived` in Java is the right amount of
        // machinery. ponytail: in-memory filter; add a jsonb query if this ever grows past a few hundred.
        for (ApplicationVerification row : verificationRepo.findByCheckTypeAndStatus(
                ApplicationVerificationService.BUREAU, "REVIEW")) {
            if (rows.size() >= limit) {
                break;
            }
            Map<String, Object> derived = derived(row);
            if (!Boolean.TRUE.equals(derived.get("bureauChallenge"))
                    || Boolean.TRUE.equals(derived.get("bureauChallengeSkipped"))) {
                continue;
            }
            LoanApplication app = applicationRepo.findById(row.getApplicationId()).orElse(null);
            if (app == null || app.getStatus() != ELIGIBLE_STATUS) {
                continue;
            }
            boolean alreadyNotified = derived.get("bureauChallengeNotifiedAt") != null;
            rows.add(new OutreachRow(app.getId(), app.getCustomerId(), alreadyNotified));
            if (alreadyNotified) {
                skipped++;
                continue;
            }
            if (!dryRun) {
                eventPublisher.publishEvent(
                        new BureauQuestionPendingEvent(app.getCustomerId(), app.getId(), Instant.now()));
                stampNotified(row, derived);
                notified++;
            }
        }
        log.info("bureau challenge outreach dryRun={} eligible={} notified={} alreadyNotified={}",
                dryRun, rows.size(), notified, skipped);
        return new OutreachSummary(rows.size(), notified, skipped, dryRun, rows);
    }

    /**
     * Merged into the existing derived, never replacing it — {@code upsert} writes derived wholesale,
     * so the question and its order/report ids would be lost by a careless overwrite here.
     */
    private void stampNotified(ApplicationVerification row, Map<String, Object> derived) {
        Map<String, Object> merged = new LinkedHashMap<>(derived);
        merged.put("bureauChallengeNotifiedAt", Instant.now().toString());
        try {
            row.setDerived(objectMapper.writeValueAsString(merged));
            verificationRepo.save(row);
        } catch (Exception serialisationFailure) {
            // The notification has already been published; failing the run here would re-send on the
            // next pass. Losing the stamp only risks one duplicate email, which is the safer trade.
            log.warn("could not stamp bureauChallengeNotifiedAt application={} exception={}",
                    row.getApplicationId(), serialisationFailure.getClass().getSimpleName());
        }
    }

    private Map<String, Object> derived(ApplicationVerification row) {
        String raw = row.getDerived();
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        } catch (Exception malformed) {
            return Map.of();
        }
    }

    private void requireAdmin() {
        if (!"ADMIN".equals(ActorContext.get().role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "ADMIN required");
        }
    }
}
