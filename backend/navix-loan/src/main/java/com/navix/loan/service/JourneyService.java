package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.entity.ApplicationVerification;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicationReferenceRepository;
import com.navix.loan.repository.ApplicationVerificationRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where a borrower is in the onboarding journey, answered server-side so a second device resumes at
 * the right screen (V44; revamp.md C1). Resume used to be a localStorage breadcrumb.
 *
 * <p>The answer is the furthest of two things: what the borrower's saved data <em>proves</em> they
 * finished, and the {@code journey_step} pointer. Derivation alone can't see a skipped optional step
 * (set-password), and the pointer alone goes stale — taking the max of both is never wrong in the
 * direction that matters, which is sending someone back through work they already did.
 */
@Service
@RequiredArgsConstructor
public class JourneyService {

    /** The Phase-1 intake, in order. {@code DONE} means intake is finished (status left DRAFT). */
    public enum Step {
        START("start"),
        OTP("otp"),
        SET_PASSWORD("set-password"),
        EMPLOYMENT("employment"),
        EMPLOYER("employer"),
        EMAIL("email"),
        BANK("bank"),
        PAYSLIPS("payslips"),
        CONSENT("consent"),
        SUBMITTED("submitted"),
        DONE(null);

        public final String seg;

        Step(String seg) {
            this.seg = seg;
        }

        public String route() {
            return seg == null ? "/loan/status" : "/signup/" + seg;
        }
    }

    /**
     * The Phase-3 offer journey, in order (V46; revamp.md Phase 3) — what the borrower walks
     * <em>after</em> a Credit Executive sanctions their file and before the money is released.
     *
     * <p>A separate registry from {@link Step} rather than a continuation of it, because the two are
     * gated on different things: the intake runs while the application is DRAFT, this runs while it
     * is SANCTIONED, and a borrower can be at OFFER_AMOUNT on one application while a previous one
     * sits at DONE. {@code OFFER_DONE} means the offer is accepted and the file has left the
     * borrower's hands.
     */
    public enum OfferStep {
        OFFER_AMOUNT("amount"),
        OFFER_REPAYMENT_DATE("repayment-date"),
        OFFER_DIGILOCKER("digilocker"),
        OFFER_REFERENCES("references"),
        OFFER_SUMMARY("summary"),
        OFFER_SELFIE("selfie"),
        OFFER_ADDRESS("address"),
        OFFER_SANCTION_LETTER("sanction-letter"),
        OFFER_ESIGN("esign"),
        OFFER_SANCTIONED("sanctioned"),
        OFFER_DISBURSAL_ACCOUNT("disbursal-account"),
        OFFER_DONE(null);

        public final String seg;

        OfferStep(String seg) {
            this.seg = seg;
        }

        public String route() {
            return seg == null ? "/loan/status" : "/loan/" + seg;
        }
    }

    /**
     * Where the borrower is, and which steps their journey actually has.
     *
     * <p>{@code steps} is not a constant: a re-apply drops the screens whose evidence carried over
     * from the prior advance (V47), so its journey is genuinely shorter and {@code index}/{@code
     * total} count against that shorter list rather than a fixed eleven.
     */
    public record JourneyView(String step, String route, int index, int total, List<String> completed,
                              List<String> steps) {
    }

    private final LoanApplicationRepository applicationRepository;
    private final CustomerProfileRepository profileRepository;
    private final ApplicationVerificationRepository verificationRepository;
    private final ApplicationReferenceRepository referenceRepository;

    @Transactional(readOnly = true)
    public JourneyView current(Long appId) {
        LoanApplication app = applicationRepository.findById(appId)
                .orElseThrow(() -> new ResourceNotFoundException("LoanApplication", String.valueOf(appId)));
        if (app.getStatus() == ApplicationStatus.SANCTIONED) {
            OfferStep step = currentOfferStep(app);
            List<OfferStep> steps = applicableOfferSteps(app);
            List<String> completed = steps.stream()
                    .filter(s -> s.ordinal() < step.ordinal())
                    .map(Enum::name)
                    .toList();
            // A step dropped from this borrower's journey has no index; report the completed count,
            // which is where the progress bar should sit either way.
            int index = steps.indexOf(step);
            return new JourneyView(step.name(), step.route(),
                    index >= 0 ? index : completed.size(), steps.size(), completed,
                    steps.stream().map(Enum::name).toList());
        }
        Step step = currentStep(app);
        List<Step> steps = java.util.Arrays.stream(Step.values()).filter(s -> s != Step.DONE).toList();
        List<String> completed = steps.stream()
                .filter(s -> s.ordinal() < step.ordinal())
                .map(Enum::name)
                .toList();
        return new JourneyView(step.name(), step.route(), step.ordinal(), steps.size(), completed,
                steps.stream().map(Enum::name).toList());
    }

    /**
     * The offer steps this application actually has. A first-time borrower walks all eleven; a
     * re-apply skips the four whose evidence {@code ApplicationFlowService.carryOverForReapply}
     * brought forward — DigiLocker, references, selfie and address (revamp.md decision 45).
     *
     * <p>Keyed on {@code reappliedFrom}, not merely on "does a check row exist", so the list is
     * <b>stable for the whole journey</b>: a first-time borrower who has just passed DigiLocker must
     * still see it counted among their steps, or the progress bar would shrink under them.
     */
    private List<OfferStep> applicableOfferSteps(LoanApplication app) {
        List<OfferStep> all = java.util.Arrays.stream(OfferStep.values())
                .filter(s -> s != OfferStep.OFFER_DONE && s != OfferStep.OFFER_ESIGN)
                .toList();
        if (app.getReappliedFrom() == null) {
            return all;
        }
        Set<String> checks = attemptedChecks(app.getId());
        boolean hasReferences = referenceRepository.findByApplicationIdOrderBySlotAsc(app.getId()).size() >= 2;
        return all.stream().filter(s -> switch (s) {
            case OFFER_DIGILOCKER -> !checks.contains(ApplicationVerificationService.AADHAAR);
            case OFFER_REFERENCES -> !hasReferences;
            case OFFER_SELFIE -> !checks.contains(ApplicationVerificationService.SELFIE);
            case OFFER_ADDRESS -> !checks.contains(ApplicationVerificationService.ADDRESS);
            default -> true;
        }).toList();
    }

    /** Record how far the borrower has walked; never moves the pointer backwards. */
    @Transactional
    public void advance(Long appId, Step step) {
        applicationRepository.findById(appId).ifPresent(app -> {
            requireEmailsVerified(app, step);
            if (parse(app.getJourneyStep()).map(prev -> step.ordinal() > prev.ordinal()).orElse(true)) {
                app.setJourneyStep(step.name());
                applicationRepository.save(app);
            }
        });
    }

    /**
     * Same, for the Phase-3 offer journey. The two registries share one column, which is safe because
     * they are read under mutually exclusive statuses — and an intake pointer left behind by the same
     * application never parses as an {@link OfferStep}, so it simply doesn't hold anyone back.
     */
    @Transactional
    public void advance(Long appId, OfferStep step) {
        applicationRepository.findById(appId).ifPresent(app -> {
            if (parseOffer(app.getJourneyStep()).map(prev -> step.ordinal() > prev.ordinal()).orElse(true)) {
                app.setJourneyStep(step.name());
                applicationRepository.save(app);
            }
        });
    }

    /**
     * Advance by step name, resolving against whichever registry owns it. The controller takes the
     * step as a string because the two enums share one endpoint; an unrecognised name is ignored
     * rather than rejected, since the pointer is advisory and a stale client must not hard-fail.
     */
    @Transactional
    public void advance(Long appId, String stepName) {
        parse(stepName).ifPresent(step -> advance(appId, step));
        parseOffer(stepName).ifPresent(step -> advance(appId, step));
    }

    /**
     * The one precondition the pointer enforces: both email addresses must be OTP-verified before the
     * borrower can be recorded at or past {@link Step#EMAIL}.
     *
     * <p>This is the server side of a gate the client has always shown. Without it the disabled
     * Continue button on {@code /signup/email} was the only thing holding anyone — so a stale client
     * or a resume on a second device walked straight past a screen the UI presents as mandatory.
     *
     * <p><b>This throws, unlike the rest of this class.</b> {@link #advance(Long, String)} ignores an
     * unrecognised step name rather than rejecting it, because a stale client must not hard-fail on a
     * pointer that is only advisory. That reasoning does not extend to a <em>known</em> step whose
     * precondition is unmet: silently declining to move would let the caller believe it had advanced.
     * Do not "fix" this back to silent.
     *
     * <p>Guarded on {@code >= EMAIL} rather than {@code == EMAIL} so skipping the call for screen 6
     * and advancing straight to {@link Step#BANK} does not slip through.
     */
    private void requireEmailsVerified(LoanApplication app, Step step) {
        if (step.ordinal() < Step.EMAIL.ordinal()) {
            return;
        }
        CustomerProfile p = profileRepository.findByApplicationId(app.getId()).orElse(null);
        if (p == null || !emailsSettled(p, app.getId())) {
            throw new BusinessException("EMAIL_NOT_VERIFIED",
                    "Verify both your personal and work email addresses to continue");
        }
    }

    /**
     * Whether screen 6 is finished: the personal address is OTP-verified, and the work address is
     * either OTP-verified or has been recorded as undeliverable.
     *
     * <p>The two are deliberately asymmetric. The personal address is the contact channel for the
     * sanction letter and every statement, so an unreachable one has to be replaced — there is no way
     * to service that borrower otherwise. The work address only corroborates the employer, and
     * corporate mail filters routinely drop outside senders, so
     * {@code ApplicationVerificationService.requestOfficialEmailOtp} writes an {@code
     * OFFICIAL_EMAIL_OTP} row in REVIEW when the send fails and the file goes to the credit team
     * flagged instead (revamp.md decision 10). A row in <em>any</em> terminal status therefore
     * settles it — which is also why the flag is checked first: a re-apply carries the flag forward
     * (ApplicationFlowService.copyProfileForReborrow) but not the verification row.
     */
    private boolean emailsSettled(CustomerProfile p, Long appId) {
        if (!Boolean.TRUE.equals(p.getPersonalEmailVerified())) {
            return false;
        }
        return Boolean.TRUE.equals(p.getOfficialEmailOtpVerified())
                || attemptedChecks(appId).contains(ApplicationVerificationService.OFFICIAL_EMAIL_OTP);
    }

    private Step currentStep(LoanApplication app) {
        if (app.getStatus() != ApplicationStatus.DRAFT) {
            return Step.DONE;
        }
        Step derived = derive(app);
        return parse(app.getJourneyStep())
                .map(stored -> Step.values()[Math.max(derived.ordinal(),
                        Math.min(stored.ordinal() + 1, Step.DONE.ordinal()))])
                .orElse(derived);
    }

    private OfferStep currentOfferStep(LoanApplication app) {
        OfferStep derived = deriveOffer(app);
        OfferStep resolved = parseOffer(app.getJourneyStep())
                .filter(stored -> stored.ordinal() > derived.ordinal())
                .orElse(derived);
        // Signing now lives on the agreement page. Historical rows may still hold the retired
        // OFFER_ESIGN pointer; normalize them instead of reopening a separate signing screen.
        return resolved == OfferStep.OFFER_ESIGN ? OfferStep.OFFER_SANCTION_LETTER : resolved;
    }

    /**
     * The first offer step the borrower's saved data does not yet prove they finished.
     *
     * <p>Four screens leave no trace of their own — the locked repayment date, the summary, the
     * sanction letter and the confetti — so derivation steps over them exactly as it steps over
     * SET_PASSWORD in the intake; only the stored pointer can hold a borrower on one.
     *
     * <p>DigiLocker, selfie and address are satisfied by a row in <em>any</em> terminal status, not
     * by a PASS: a Phase-3 check that fails passes through silently and surfaces only on the staff
     * Verification Dashboard (revamp.md decision 11).
     */
    private OfferStep deriveOffer(LoanApplication app) {
        if (app.getAmountRequested() == null) {
            return OfferStep.OFFER_AMOUNT;
        }
        Set<String> checks = attemptedChecks(app.getId());
        if (!checks.contains(ApplicationVerificationService.AADHAAR)) {
            return OfferStep.OFFER_DIGILOCKER;
        }
        if (referenceRepository.findByApplicationIdOrderBySlotAsc(app.getId()).size() < 2) {
            return OfferStep.OFFER_REFERENCES;
        }
        if (!checks.contains(ApplicationVerificationService.SELFIE)) {
            return OfferStep.OFFER_SELFIE;
        }
        if (!checks.contains(ApplicationVerificationService.ADDRESS)) {
            return OfferStep.OFFER_ADDRESS;
        }
        if (!checks.contains(ApplicationVerificationService.ESIGN)) {
            return OfferStep.OFFER_SANCTION_LETTER;
        }
        if (app.getDisbursalConfirmedAt() == null) {
            return OfferStep.OFFER_DISBURSAL_ACCOUNT;
        }
        return OfferStep.OFFER_DONE;
    }

    private Set<String> attemptedChecks(Long appId) {
        return verificationRepository.findByApplicationIdOrderByIdAsc(appId).stream()
                .map(ApplicationVerification::getCheckType)
                .collect(Collectors.toSet());
    }

    /** The first step the borrower's saved data does not yet prove they finished. */
    private Step derive(LoanApplication app) {
        CustomerProfile p = profileRepository.findByApplicationId(app.getId()).orElse(null);
        if (p == null || p.getTermsAcceptedAt() == null) {
            return Step.START;
        }
        if (blank(p.getMobile())) {
            return Step.OTP;
        }
        if (blank(p.getEmploymentStatus())) {
            return parse(app.getJourneyStep())
                    .filter(step -> step.ordinal() >= Step.SET_PASSWORD.ordinal())
                    .map(step -> Step.EMPLOYMENT)
                    .orElse(Step.SET_PASSWORD);
        }
        // SET_PASSWORD is skippable and leaves no trace, so derivation steps over it — only the
        // stored pointer can hold a borrower there, which is what the max() in currentStep is for.
        if (blank(p.getEmployer()) || p.getPreviousSalaryDate() == null || p.getMonthlySalaryPaise() == null) {
            return Step.EMPLOYER;
        }
        // Held here until both addresses are settled, not merely typed in. derive() and
        // requireEmailsVerified() must agree: currentStep takes max(derived, stored + 1), so a
        // pointer that had been allowed past EMAIL would carry the borrower forward regardless of
        // what this returns.
        if (blank(p.getOfficialEmail()) || !emailsSettled(p, app.getId())) {
            return Step.EMAIL;
        }
        if (blank(p.getSalaryAccountNumber()) || blank(p.getSalaryIfsc())) {
            return Step.BANK;
        }
        Set<String> checks = verificationRepository.findByApplicationIdOrderByIdAsc(app.getId()).stream()
                .map(ApplicationVerification::getCheckType)
                .collect(Collectors.toSet());
        if (!checks.contains(ApplicationVerificationService.SALARY)) {
            return Step.PAYSLIPS;
        }
        if (!checks.contains(ApplicationVerificationService.PAN)) {
            return Step.CONSENT;
        }
        return Step.SUBMITTED;
    }

    private static Optional<Step> parse(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Step.valueOf(name));
        } catch (IllegalArgumentException stale) {
            return Optional.empty();
        }
    }

    private static Optional<OfferStep> parseOffer(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(OfferStep.valueOf(name));
        } catch (IllegalArgumentException notAnOfferStep) {
            // An intake pointer (START, BANK, …) left on the row — correct to ignore.
            return Optional.empty();
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
