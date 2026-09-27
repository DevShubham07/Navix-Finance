package com.navix.loan.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.notification.event.VerificationStepLinkEvent;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.util.Masking;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.entity.ApplicationEvent;
import com.navix.loan.entity.ApplicationVerification;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicationEventRepository;
import com.navix.loan.repository.ApplicationVerificationRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Send the customer a link" — staff nudges a borrower straight back to a failed/abandoned
 * borrower-side verification step, without a magic link: the borrower still logs in (mobile OTP),
 * and the link is just {@code /login?next=<step route>}, the same proven pattern
 * {@code useOfferGuard} already relies on to resume a borrower wherever {@code JourneyService}
 * says they are.
 *
 * <p>Scoped to the offer-journey checks a borrower can get stuck on (ESIGN, DigiLocker/Aadhaar,
 * selfie, address) plus BUREAU when a security question is pending — mirrors
 * {@link BureauChallengeOutreachService}, whose single-application nudge ({@code notifyApplication})
 * this class delegates BUREAU sends to.
 *
 * <p>A failed Phase-3 check leaves the borrower's journey <em>already past</em> that screen —
 * {@code JourneyService.deriveOffer}/{@code attemptedChecks} treat a row in any status as attempted —
 * so a link alone would bounce them forward. {@code share} fixes that by <b>reopening</b> the check
 * (V74 {@code reopened_at}/{@code reopened_by}, ignored by {@code attemptedChecks}) and rewinding the
 * stored journey pointer ({@link JourneyService#rewind}) before sending the link. eSign is the one
 * exception that needs no reopen when nothing was ever recorded: a failed/abandoned signing writes no
 * PASS row, so the borrower is already sitting on the sanction-letter step.
 */
@Service
@RequiredArgsConstructor
public class VerificationOutreachService {

    /** Which {@link JourneyService.OfferStep} a check's "resume" link points at. BUREAU is handled
     *  separately below — its route is the credit-question screen, not an offer step. */
    private static final Map<String, JourneyService.OfferStep> CHECK_TO_STEP = Map.of(
            ApplicationVerificationService.ESIGN, JourneyService.OfferStep.OFFER_SANCTION_LETTER,
            ApplicationVerificationService.AADHAAR, JourneyService.OfferStep.OFFER_DIGILOCKER,
            ApplicationVerificationService.DIGILOCKER, JourneyService.OfferStep.OFFER_DIGILOCKER,
            ApplicationVerificationService.SELFIE, JourneyService.OfferStep.OFFER_SELFIE,
            ApplicationVerificationService.ADDRESS, JourneyService.OfferStep.OFFER_ADDRESS);

    private static final String BUREAU = ApplicationVerificationService.BUREAU;
    private static final String BUREAU_ROUTE_PREFIX = "/credit-question?appId=";

    private static final Map<String, String> STEP_LABELS = Map.of(
            ApplicationVerificationService.ESIGN, "Sanction letter & e-sign",
            ApplicationVerificationService.AADHAAR, "DigiLocker (Aadhaar)",
            ApplicationVerificationService.DIGILOCKER, "DigiLocker (Aadhaar)",
            ApplicationVerificationService.SELFIE, "Selfie",
            ApplicationVerificationService.ADDRESS, "Address",
            BUREAU, "Credit bureau question");

    private static final String REOPEN_MESSAGE = "Reopened for the customer to redo";

    /** How the link reaches the customer. {@code COPY} still reopens + audits, same as {@code EMAIL};
     *  it just doesn't publish a notification (the staffer hands the link over themselves). */
    public enum Channel { EMAIL, COPY }

    /** What the staff dialog needs to render + send the link. */
    public record ResumeLink(String url, String route, String stepLabel, boolean willReopen,
                             String maskedEmail, String maskedMobile, Instant lastSentAt) {
    }

    /** One eligible check's resolved target: the offer step (null for BUREAU), whether sending will
     *  reopen a row, and the row(s) a reopen would touch. */
    private record Eligibility(JourneyService.OfferStep step, String route, boolean willReopen,
                               ApplicationVerification primaryRow, ApplicationVerification secondaryRow) {
    }

    private final ApplicationVerificationRepository verificationRepo;
    private final LoanApplicationRepository applicationRepo;
    private final ApplicationEventRepository eventRepo;
    private final CustomerProfileRepository profileRepo;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final JourneyService journeyService;
    private final BureauChallengeOutreachService bureauChallengeOutreach;

    @Value("${navix.app.frontend-base-url:http://localhost:3000}")
    private String frontendBaseUrl;

    /** Read-only: what would happen, without sending anything. */
    @Transactional(readOnly = true)
    public ResumeLink preview(Long appId, String checkType) {
        CreditTeamGuard.requireCreditTeamOr("Sending a step link");
        String type = normalize(checkType);
        Eligibility eligibility = eligibility(appId, type);
        return toResumeLink(appId, type, eligibility);
    }

    /**
     * Send (or hand over) the link. Reopens the step when needed, rewinds the journey pointer, appends
     * the audit trail ({@code STEP_REOPENED} only when reopened, {@code STEP_LINK_SENT} always), and —
     * for {@link Channel#EMAIL} — actually notifies the borrower (BUREAU delegates to
     * {@link BureauChallengeOutreachService#notifyApplication}, which keeps its own once-only stamp).
     */
    @Transactional
    public ResumeLink share(Long appId, String checkType, Channel channel) {
        CreditTeamGuard.requireCreditTeamOr("Sending a step link");
        String type = normalize(checkType);
        Eligibility eligibility = eligibility(appId, type);
        CurrentActor actor = ActorContext.get();

        if (eligibility.willReopen()) {
            reopen(eligibility.primaryRow(), actor);
            if (eligibility.secondaryRow() != null) {
                reopen(eligibility.secondaryRow(), actor);
            }
            journeyService.rewind(appId, eligibility.step());
            logEvent(appId, "STEP_REOPENED", type, channel, actor);
        }

        if (channel == Channel.EMAIL) {
            if (BUREAU.equals(type)) {
                // The bureau nudge mails once per application; a skipped send must surface as an error
                // rather than be recorded below as a link that went out.
                if (bureauChallengeOutreach.notifyApplication(appId).notified() == 0) {
                    throw new BusinessException("BUREAU_CHALLENGE_ALREADY_NOTIFIED",
                            "This customer has already been emailed about the bureau question");
                }
            } else {
                LoanApplication app = requireApplication(appId);
                eventPublisher.publishEvent(new VerificationStepLinkEvent(app.getCustomerId(), appId,
                        type, STEP_LABELS.get(type), eligibility.route(), Instant.now()));
            }
        }
        logEvent(appId, "STEP_LINK_SENT", type, channel, actor);
        return toResumeLink(appId, type, eligibility);
    }

    // ---------------------------------------------------------------- eligibility

    private Eligibility eligibility(Long appId, String type) {
        LoanApplication app = requireApplication(appId);
        if (BUREAU.equals(type)) {
            if (app.getStatus() != ApplicationStatus.KYC_PENDING) {
                throw new BusinessException("STEP_LINK_NOT_ELIGIBLE",
                        "Only an application still awaiting review can be sent a bureau link");
            }
            ApplicationVerification row = verificationRepo.findByApplicationIdAndCheckType(appId, BUREAU)
                    .orElseThrow(() -> new BusinessException("STEP_LINK_NOT_ELIGIBLE",
                            "This application has no bureau check to chase"));
            if (!Boolean.TRUE.equals(derived(row).get("bureauChallenge"))) {
                throw new BusinessException("STEP_LINK_NOT_ELIGIBLE",
                        "This application is not waiting on a bureau security question");
            }
            return new Eligibility(null, BUREAU_ROUTE_PREFIX + appId, false, null, null);
        }

        JourneyService.OfferStep step = CHECK_TO_STEP.get(type);
        if (step == null) {
            throw new BusinessException("STEP_LINK_UNSUPPORTED", "No resend link for check: " + type);
        }
        if (app.getStatus() != ApplicationStatus.SANCTIONED) {
            throw new BusinessException("STEP_LINK_NOT_ELIGIBLE",
                    "Only a sanctioned application can resend an offer-journey link");
        }
        if (ApplicationVerificationService.AADHAAR.equals(type) || ApplicationVerificationService.DIGILOCKER.equals(type)) {
            // deriveOffer/attemptedChecks only ever read the AADHAAR row for this step, whichever card
            // ("AADHAAR" or "DIGILOCKER") staff clicked — so that row decides eligibility, and the
            // DIGILOCKER row (when present) is reopened alongside it purely so the customer doesn't
            // see a stale completed DIGILOCKER card next to the one they're being sent back to redo.
            Optional<ApplicationVerification> aadhaarRow = verificationRepo
                    .findByApplicationIdAndCheckType(appId, ApplicationVerificationService.AADHAAR);
            ApplicationVerification digilockerRow = verificationRepo
                    .findByApplicationIdAndCheckType(appId, ApplicationVerificationService.DIGILOCKER)
                    .orElse(null);
            return new Eligibility(step, step.route(), needsReopen(aadhaarRow), aadhaarRow.orElse(null),
                    digilockerRow);
        }

        Optional<ApplicationVerification> existing = verificationRepo.findByApplicationIdAndCheckType(appId, type);
        if (ApplicationVerificationService.ESIGN.equals(type) && existing.isPresent()
                && ApplicationVerificationService.PASS.equals(existing.get().getStatus())) {
            throw new BusinessException("STEP_ALREADY_DONE", "eSign is already complete");
        }
        return new Eligibility(step, step.route(), needsReopen(existing), existing.orElse(null), null);
    }

    /** A row counts as attempted until it is reopened, so a second send while a reopen is still open
     *  is a no-op on the row (idempotent) — only the {@code STEP_LINK_SENT} audit line is appended. */
    private static boolean needsReopen(Optional<ApplicationVerification> row) {
        return row.isPresent() && row.get().getReopenedAt() == null;
    }

    private void reopen(ApplicationVerification row, CurrentActor actor) {
        row.setStatus(ApplicationVerificationService.PENDING);
        row.setMessage(REOPEN_MESSAGE);
        row.setReopenedAt(Instant.now());
        row.setReopenedBy(actor != null ? actor.id() : null);
        verificationRepo.save(row);
    }

    // ---------------------------------------------------------------- audit + link

    private void logEvent(Long appId, String action, String checkType, Channel channel, CurrentActor actor) {
        LoanApplication app = applicationRepo.findById(appId).orElse(null);
        if (app == null) {
            return;
        }
        ApplicationEvent event = new ApplicationEvent();
        event.setApplicationId(appId);
        event.setFromStatus(app.getStatus());
        event.setToStatus(app.getStatus());
        event.setActorId(actor != null ? actor.id() : "system");
        event.setActorRole(actor != null ? actor.role() : null);
        event.setAction(action);
        event.setNotes(checkType + " via " + channel);
        event.setAt(Instant.now());
        eventRepo.save(event);
    }

    private ResumeLink toResumeLink(Long appId, String type, Eligibility eligibility) {
        CustomerProfile profile = profileRepo.findByApplicationId(appId).orElse(null);
        String maskedEmail = profile == null ? null : Masking.maskEmail(profile.getEmail());
        String maskedMobile = profile == null ? null : Masking.maskPhone(profile.getMobile());
        String url = frontendBaseUrl + "/login?next=" + URLEncoder.encode(eligibility.route(), StandardCharsets.UTF_8);
        return new ResumeLink(url, eligibility.route(), STEP_LABELS.get(type), eligibility.willReopen(),
                maskedEmail, maskedMobile, lastSentAt(appId, type));
    }

    /** The latest {@code STEP_LINK_SENT} event for THIS check — matched off the {@code notes} prefix
     *  since one application can send links for several different checks. */
    private Instant lastSentAt(Long appId, String type) {
        return eventRepo.findByApplicationIdAndActionOrderByAtDesc(appId, "STEP_LINK_SENT").stream()
                .filter(e -> e.getNotes() != null && e.getNotes().startsWith(type + " via "))
                .map(ApplicationEvent::getAt)
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> derived(ApplicationVerification row) {
        String raw = row.getDerived();
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception malformed) {
            return Map.of();
        }
    }

    private LoanApplication requireApplication(Long appId) {
        return applicationRepo.findById(appId)
                .orElseThrow(() -> new ResourceNotFoundException("LoanApplication", String.valueOf(appId)));
    }

    private static String normalize(String checkType) {
        return checkType == null ? "" : checkType.trim().toUpperCase();
    }
}
