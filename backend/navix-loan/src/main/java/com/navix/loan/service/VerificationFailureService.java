package com.navix.loan.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.dto.CaseFailureReason;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicationVerificationRepository;
import com.navix.loan.repository.ApplicationVerificationRepository.CaseFailureRow;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Why an application has no usable credit decision — one {@link CaseFailureReason} per application,
 * computed from the {@code application_verification} rows it already has.
 *
 * <p><b>Computed on read, never stored.</b> Same convention as the DPD bucket and the loan's
 * outstanding balance: a classifier bug is fixed by a deploy rather than a data repair, and there is
 * no second copy to drift. Deliberately separate from {@link BureauStateService} — that answers the
 * three-value "did a pull happen and what did it find" question the Bureau column has always asked,
 * and this answers the different, larger question of what is standing in the way. Neither replaces
 * the other and the Bureau column is unchanged.
 *
 * <p><b>It classifies history, not just new failures.</b> The 207 applications stuck in the
 * September 2026 queue were recorded before the fixes that would have labelled them, so the rules
 * below read what those rows actually contain — a blank name on the profile, a real report sitting
 * inside a response marked "no record" — rather than only the markers written from now on. That is
 * what lets the backlog explain itself without a single billable re-pull.
 *
 * <p>Batched to mirror {@link BureauStateService#states}: list views resolve every row in a fixed
 * number of queries, chunked to stay clear of Postgres' {@code IN (...)} parameter limit.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VerificationFailureService {

    private static final String BUREAU = "BUREAU";
    private static final String BUREAU_CONSENT = "BUREAU_CONSENT";
    private static final String PAN = "PAN";
    private static final List<String> CHECK_TYPES = List.of(BUREAU, BUREAU_CONSENT, PAN);
    private static final int CHUNK_SIZE = 1000;

    private static final String PASS = "PASS";
    private static final String REVIEW = "REVIEW";
    private static final String FAIL = "FAIL";

    private final ApplicationVerificationRepository verificationRepo;
    private final CustomerProfileRepository profileRepo;
    private final LoanApplicationRepository applicationRepo;
    private final ObjectMapper objectMapper;

    /** The classified answer for one application, plus the check it came from. */
    public record CaseFailure(CaseFailureReason reason, String checkType) {

        public static CaseFailure none() {
            return new CaseFailure(CaseFailureReason.NONE, null);
        }
    }

    /** Single-application lookup — the detail dialog and the per-application endpoint. */
    public CaseFailure failure(Long applicationId) {
        if (applicationId == null) {
            return CaseFailure.none();
        }
        return failures(List.of(applicationId)).getOrDefault(applicationId, CaseFailure.none());
    }

    /**
     * Batched lookup for list views. Applications with nothing wrong are present with
     * {@link CaseFailureReason#NONE}; ids that do not resolve are simply absent, so callers should
     * default via {@code getOrDefault}.
     */
    public Map<Long, CaseFailure> failures(Collection<Long> applicationIds) {
        if (applicationIds == null || applicationIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = new ArrayList<>(new HashSet<>(applicationIds));
        ids.removeIf(java.util.Objects::isNull);
        if (ids.isEmpty()) {
            return Map.of();
        }

        Map<Long, Map<String, CaseFailureRow>> rowsByApp = new HashMap<>();
        Map<Long, CustomerProfile> profiles = new HashMap<>();
        Map<Long, LoanApplication> applications = new HashMap<>();
        for (int from = 0; from < ids.size(); from += CHUNK_SIZE) {
            List<Long> chunk = ids.subList(from, Math.min(from + CHUNK_SIZE, ids.size()));
            for (CaseFailureRow row : verificationRepo.findByApplicationIdInAndCheckTypeIn(chunk, CHECK_TYPES)) {
                rowsByApp.computeIfAbsent(row.getApplicationId(), k -> new HashMap<>())
                        .put(row.getCheckType(), row);
            }
            for (CustomerProfile profile : profileRepo.findByApplicationIdIn(chunk)) {
                profiles.put(profile.getApplicationId(), profile);
            }
            for (LoanApplication app : applicationRepo.findAllById(chunk)) {
                applications.put(app.getId(), app);
            }
        }

        Map<Long, CaseFailure> out = new HashMap<>();
        for (Long id : ids) {
            out.put(id, classify(rowsByApp.getOrDefault(id, Map.of()), profiles.get(id),
                    applications.get(id)));
        }
        return out;
    }

    /**
     * The precedence walk. First match wins, in {@link CaseFailureReason} declaration order, so an
     * application with more than one problem reports the one that has to be solved first.
     */
    private CaseFailure classify(Map<String, CaseFailureRow> rows, CustomerProfile profile,
                                 LoanApplication application) {
        CaseFailureRow bureau = rows.get(BUREAU);
        CaseFailureRow pan = rows.get(PAN);
        Map<String, Object> bureauDerived = derivedOf(bureau);

        if (bureau != null && REVIEW.equals(bureau.getStatus())) {
            if (bureauDerived.containsKey("identityMismatch")) {
                return new CaseFailure(CaseFailureReason.BUREAU_IDENTITY_MISMATCH, BUREAU);
            }
            String missing = String.valueOf(bureauDerived.get("missingProfileField"));
            if ("name".equals(missing)) {
                return new CaseFailure(CaseFailureReason.BUREAU_MISSING_NAME, BUREAU);
            }
            if ("dob".equals(missing)) {
                return new CaseFailure(CaseFailureReason.BUREAU_MISSING_DOB, BUREAU);
            }
            if (Boolean.TRUE.equals(bureauDerived.get("bureauChallenge"))) {
                return new CaseFailure(Boolean.TRUE.equals(bureauDerived.get("bureauChallengeSkipped"))
                        ? CaseFailureReason.BUREAU_KBA_SKIPPED
                        : CaseFailureReason.BUREAU_KBA_PENDING, BUREAU);
            }
            if (Boolean.TRUE.equals(bureauDerived.get("bureauMaskedMobileRequired"))) {
                return new CaseFailure(CaseFailureReason.BUREAU_MASKED_MOBILE_FOLLOW_UP, BUREAU);
            }
            CaseFailureReason providerReason = providerReason(bureauDerived);
            if (providerReason != null) {
                return new CaseFailure(providerReason, BUREAU);
            }
            // A REVIEW we have no marker for. Consent-deferred rows land here (they carry no derived
            // at all), so name the honest generic rather than inventing a provider failure.
            return new CaseFailure(hasConsent(rows)
                    ? CaseFailureReason.BUREAU_PROVIDER_UNAVAILABLE
                    : CaseFailureReason.BUREAU_CONSENT_PENDING, BUREAU);
        }

        // A PAN problem only matters once the bureau is not itself the blocker: a failed PAN is how a
        // profile ends up nameless, and BUREAU_MISSING_NAME above is the more useful thing to say.
        if (pan != null && FAIL.equals(pan.getStatus())) {
            return new CaseFailure(CaseFailureReason.PAN_INVALID, PAN);
        }
        if (pan != null && REVIEW.equals(pan.getStatus())
                && Boolean.TRUE.equals(derivedOf(pan).get("providerError"))
                && (bureau == null || !PASS.equals(bureau.getStatus()))) {
            return new CaseFailure(CaseFailureReason.PAN_UNVERIFIED, PAN);
        }

        if (bureau != null && PASS.equals(bureau.getStatus())) {
            boolean noRecord = Boolean.TRUE.equals(bureauDerived.get("noRecord"));
            if (noRecord) {
                // Historical rows. A CRIF no-hit that still carries a provider transaction id is a
                // report the pre-fix rule threw away for having an out-of-band score: that id is the
                // report's own REPORT-ID, read out of the HEADER, and a genuine thin file has no
                // report node to read one from. 47 of the September 2026 queue look like this, and
                // re-running now keeps what it returns — so they are worth separating from the 84
                // true no-hits they are currently indistinguishable from.
                //
                // Reading the stored response itself would be exact, but it is a full credit report
                // per row and the probe would have to happen in SQL; this is one already-loaded
                // column. The cost is a rare false positive — a report shell carrying an id and
                // nothing else — which surfaces as an offer to re-run, not as an automatic spend.
                if (bureau.getProviderTxnId() != null && !bureau.getProviderTxnId().isBlank()) {
                    return new CaseFailure(CaseFailureReason.BUREAU_REPORT_DISCARDED, BUREAU);
                }
                // A bureau request built without a name cannot match anyone; the provider rejected it
                // and the row was recorded as a thin file. The blank name is the durable evidence.
                if (nameMissing(profile)) {
                    return new CaseFailure(CaseFailureReason.BUREAU_MISSING_NAME, BUREAU);
                }
                return new CaseFailure(CaseFailureReason.BUREAU_NO_RECORD, BUREAU);
            }
            if (bureau.getScore() == null) {
                return new CaseFailure(CaseFailureReason.BUREAU_NO_SCORE, BUREAU);
            }
            return CaseFailure.none();
        }

        if (!hasConsent(rows)) {
            return new CaseFailure(CaseFailureReason.BUREAU_CONSENT_PENDING, BUREAU_CONSENT);
        }
        if (bureau == null) {
            return new CaseFailure(CaseFailureReason.BUREAU_NOT_RUN, BUREAU);
        }
        if (awaitingAssignment(application)) {
            return new CaseFailure(CaseFailureReason.AWAITING_ASSIGNMENT, null);
        }
        return CaseFailure.none();
    }

    /**
     * Map the stored {@code providerErrorCode} onto something a credit officer can act on. The codes
     * themselves are written by {@code ApplicationVerificationService.providerErrorCode}.
     */
    private static CaseFailureReason providerReason(Map<String, Object> derived) {
        Object raw = derived.get("providerErrorCode");
        if (raw == null) {
            return null;
        }
        String code = String.valueOf(raw).toUpperCase(Locale.ROOT);
        return switch (code) {
            case "HTTP_402" -> CaseFailureReason.BUREAU_PROVIDER_NO_BALANCE;
            case "HTTP_400" -> CaseFailureReason.BUREAU_PROVIDER_REJECTED_REQUEST;
            case "HTTP_422" -> CaseFailureReason.BUREAU_PROVIDER_PLAN_LIMIT;
            case "MASKED_MOBILE_REQUIRED" -> CaseFailureReason.BUREAU_MASKED_MOBILE_FOLLOW_UP;
            default -> CaseFailureReason.BUREAU_PROVIDER_UNAVAILABLE;
        };
    }

    /**
     * The file is fine and simply has nobody on it. Only meaningful before a reviewer is assigned —
     * once it moves on, the queue itself is the answer.
     */
    private static boolean awaitingAssignment(LoanApplication application) {
        return application != null
                && application.getStatus() == ApplicationStatus.KYC_PENDING
                && application.getAssignedExecutiveId() == null;
    }

    private static boolean hasConsent(Map<String, CaseFailureRow> rows) {
        CaseFailureRow consent = rows.get(BUREAU_CONSENT);
        return consent != null && PASS.equals(consent.getStatus());
    }

    private static boolean nameMissing(CustomerProfile profile) {
        return profile == null || profile.getFullName() == null || profile.getFullName().isBlank();
    }

    private Map<String, Object> derivedOf(CaseFailureRow row) {
        if (row == null || row.getDerived() == null || row.getDerived().isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(row.getDerived(),
                    new TypeReference<Map<String, Object>>() { });
            return parsed == null ? Map.of() : parsed;
        } catch (Exception malformed) {
            // Unreadable stored JSON must not decide a borrower's row is fine. Degrade to "no markers"
            // and let the status-based rules speak; never claim a clean file on a parse failure.
            log.warn("Could not parse stored derived JSON on a verification row: {}",
                    malformed.toString());
            return Map.of();
        }
    }
}
