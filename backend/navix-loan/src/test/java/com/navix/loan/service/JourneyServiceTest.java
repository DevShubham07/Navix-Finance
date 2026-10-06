package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.entity.ApplicationVerification;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicationVerificationRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JourneyServiceTest {

    private static final long APP = 1L;

    @Mock
    private LoanApplicationRepository applicationRepository;
    @Mock
    private CustomerProfileRepository profileRepository;
    @Mock
    private ApplicationVerificationRepository verificationRepository;
    @Mock
    private com.navix.loan.repository.ApplicationReferenceRepository referenceRepository;
    @Mock
    private com.navix.loan.repository.ApplicationDocumentRepository documentRepository;

    private JourneyService journey;
    private LoanApplication app;

    @BeforeEach
    void setUp() {
        journey = new JourneyService(applicationRepository, profileRepository, verificationRepository,
                referenceRepository, documentRepository);
        app = new LoanApplication();
        app.setId(APP);
        app.setCustomerId(7L);
        app.setStatus(ApplicationStatus.DRAFT);
        lenient().when(applicationRepository.findById(APP)).thenReturn(Optional.of(app));
        lenient().when(verificationRepository.findByApplicationIdOrderByIdAsc(APP)).thenReturn(List.of());
        lenient().when(documentRepository.findByApplicationIdOrderByIdAsc(APP)).thenReturn(List.of());
        lenient().when(applicationRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private CustomerProfile through(JourneyService.Step step) {
        CustomerProfile p = new CustomerProfile();
        if (step.ordinal() > JourneyService.Step.START.ordinal()) p.setTermsAcceptedAt(Instant.now());
        if (step.ordinal() > JourneyService.Step.OTP.ordinal()) p.setMobile("9876543210");
        if (step.ordinal() > JourneyService.Step.EMPLOYMENT.ordinal()) p.setEmploymentStatus("SALARIED");
        if (step.ordinal() > JourneyService.Step.EMPLOYER.ordinal()) {
            p.setEmployer("Acme");
            p.setPreviousSalaryDate(LocalDate.of(2026, 7, 25));
            p.setMonthlySalaryPaise(5_000_000L);
        }
        if (step.ordinal() > JourneyService.Step.EMAIL.ordinal()) {
            p.setOfficialEmail("a@acme.com");
            // Screen 6 is finished only when both inboxes are proven — see JourneyService.emailsSettled.
            p.setPersonalEmailVerified(true);
            p.setOfficialEmailOtpVerified(true);
        }
        if (step.ordinal() > JourneyService.Step.BANK.ordinal()) {
            p.setSalaryAccountNumber("123456789");
            p.setSalaryIfsc("HDFC0001234");
        }
        // V75: the card screens are proven by the typed number + documents, not by verification rows.
        if (step.ordinal() > JourneyService.Step.AADHAAR.ordinal()) {
            p.setAadhaar("234567890124");
        }
        if (step.ordinal() > JourneyService.Step.PAN_CARD.ordinal()) {
            docs(ApplicationVerificationService.AADHAAR_CARD_FRONT, ApplicationVerificationService.AADHAAR_CARD_BACK,
                    ApplicationVerificationService.PAN_CARD_FRONT, ApplicationVerificationService.PAN_CARD_BACK);
        } else if (step.ordinal() > JourneyService.Step.AADHAAR.ordinal()) {
            docs(ApplicationVerificationService.AADHAAR_CARD_FRONT, ApplicationVerificationService.AADHAAR_CARD_BACK);
        }
        when(profileRepository.findByApplicationId(APP)).thenReturn(Optional.of(p));
        return p;
    }

    private void docs(String... types) {
        List<com.navix.loan.entity.ApplicationDocument> rows = java.util.Arrays.stream(types).map(t -> {
            com.navix.loan.entity.ApplicationDocument d = new com.navix.loan.entity.ApplicationDocument();
            d.setApplicationId(APP);
            d.setDocType(t);
            return d;
        }).toList();
        lenient().when(documentRepository.findByApplicationIdOrderByIdAsc(APP)).thenReturn(rows);
    }

    @Test
    void heldAtEmailUntilBothInboxesAreProven() {
        CustomerProfile p = through(JourneyService.Step.BANK);

        p.setPersonalEmailVerified(null);
        assertThat(journey.current(APP).step()).isEqualTo("EMAIL");

        p.setPersonalEmailVerified(true);
        p.setOfficialEmailOtpVerified(null);
        assertThat(journey.current(APP).step()).isEqualTo("EMAIL");

        p.setOfficialEmailOtpVerified(true);
        assertThat(journey.current(APP).step()).isEqualTo("BANK");
    }

    @Test
    void anUndeliverableWorkEmailSettlesTheStepWithoutBeingVerified() {
        // The credit team picks it up from the REVIEW row; the borrower is not dead-ended on a code
        // box that will never arrive (revamp.md decision 10).
        CustomerProfile p = through(JourneyService.Step.BANK);
        p.setOfficialEmailOtpVerified(null);
        assertThat(journey.current(APP).step()).isEqualTo("EMAIL");

        when(verificationRepository.findByApplicationIdOrderByIdAsc(APP))
                .thenReturn(List.of(check(ApplicationVerificationService.OFFICIAL_EMAIL_OTP)));
        assertThat(journey.current(APP).step()).isEqualTo("BANK");
    }

    @Test
    void anUnreachablePersonalEmailIsNeverWavedThrough() {
        // Asymmetric on purpose: the personal address carries the sanction letter and statements, so
        // an undeliverable-work-email row must not settle it.
        CustomerProfile p = through(JourneyService.Step.BANK);
        p.setPersonalEmailVerified(null);
        // Never even consulted: the personal flag short-circuits, which is exactly the asymmetry.
        lenient().when(verificationRepository.findByApplicationIdOrderByIdAsc(APP))
                .thenReturn(List.of(check(ApplicationVerificationService.OFFICIAL_EMAIL_OTP)));

        assertThat(journey.current(APP).step()).isEqualTo("EMAIL");
    }

    @Test
    void advanceRefusesToRecordProgressPastEmailUntilItIsSettled() {
        CustomerProfile p = through(JourneyService.Step.BANK);
        p.setPersonalEmailVerified(null);

        // Guarded on >= EMAIL, so skipping screen 6's own call and jumping to BANK does not slip past.
        assertThatThrownBy(() -> journey.advance(APP, JourneyService.Step.BANK))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Verify both");
        assertThatThrownBy(() -> journey.advance(APP, JourneyService.Step.EMAIL))
                .isInstanceOf(BusinessException.class);
        assertThat(app.getJourneyStep()).isNull();

        // Earlier steps are unaffected.
        journey.advance(APP, JourneyService.Step.EMPLOYER);
        assertThat(app.getJourneyStep()).isEqualTo("EMPLOYER");

        p.setPersonalEmailVerified(true);
        journey.advance(APP, JourneyService.Step.EMAIL);
        assertThat(app.getJourneyStep()).isEqualTo("EMAIL");
    }

    @Test
    void emptyDraftStartsAtTheBeginning() {
        when(profileRepository.findByApplicationId(APP)).thenReturn(Optional.empty());
        assertThat(journey.current(APP).step()).isEqualTo("START");
        assertThat(journey.current(APP).route()).isEqualTo("/signup/start");
    }

    @Test
    void derivesTheFirstUnfinishedStepFromSavedData() {
        through(JourneyService.Step.EMPLOYER);
        assertThat(journey.current(APP).step()).isEqualTo("EMPLOYER");

        through(JourneyService.Step.BANK);
        assertThat(journey.current(APP).step()).isEqualTo("BANK");
    }

    @Test
    void payslipsThenConsent_areProvenByVerificationRows() {
        through(JourneyService.Step.PAYSLIPS);
        assertThat(journey.current(APP).step()).isEqualTo("PAYSLIPS");

        // Salary slips done → the Aadhaar-card screen (V75), not consent.
        when(verificationRepository.findByApplicationIdOrderByIdAsc(APP))
                .thenReturn(List.of(check(ApplicationVerificationService.SALARY)));
        assertThat(journey.current(APP).step()).isEqualTo("AADHAAR");

        CustomerProfile p = through(JourneyService.Step.PAN_CARD);
        assertThat(journey.current(APP).step()).isEqualTo("PAN_CARD");

        through(JourneyService.Step.CONSENT);
        assertThat(journey.current(APP).step()).isEqualTo("CONSENT");

        when(verificationRepository.findByApplicationIdOrderByIdAsc(APP)).thenReturn(List.of(
                check(ApplicationVerificationService.SALARY), check(ApplicationVerificationService.PAN)));
        assertThat(journey.current(APP).step()).isEqualTo("SUBMITTED");
        assertThat(p.getAadhaar()).isNotBlank();
    }

    @Test
    void aadhaarScreenNeedsTheNumberAndBothSides_panScreenNeedsBothSides() {
        CustomerProfile p = through(JourneyService.Step.AADHAAR);
        when(verificationRepository.findByApplicationIdOrderByIdAsc(APP))
                .thenReturn(List.of(check(ApplicationVerificationService.SALARY)));

        // Number typed, no images yet.
        p.setAadhaar("234567890124");
        assertThat(journey.current(APP).step()).isEqualTo("AADHAAR");

        // One side only.
        docs(ApplicationVerificationService.AADHAAR_CARD_FRONT);
        assertThat(journey.current(APP).step()).isEqualTo("AADHAAR");

        // Both sides but the number was never saved.
        docs(ApplicationVerificationService.AADHAAR_CARD_FRONT, ApplicationVerificationService.AADHAAR_CARD_BACK);
        p.setAadhaar(null);
        assertThat(journey.current(APP).step()).isEqualTo("AADHAAR");

        p.setAadhaar("234567890124");
        assertThat(journey.current(APP).step()).isEqualTo("PAN_CARD");

        docs(ApplicationVerificationService.AADHAAR_CARD_FRONT, ApplicationVerificationService.AADHAAR_CARD_BACK,
                ApplicationVerificationService.PAN_CARD_FRONT);
        assertThat(journey.current(APP).step()).isEqualTo("PAN_CARD");

        // The DigiLocker-fallback pair is a different document and does not satisfy the intake screen.
        docs(ApplicationVerificationService.AADHAAR_FRONT, ApplicationVerificationService.AADHAAR_BACK,
                ApplicationVerificationService.PAN_CARD_FRONT, ApplicationVerificationService.PAN_CARD_BACK);
        assertThat(journey.current(APP).step()).isEqualTo("AADHAAR");
    }

    @Test
    void aStalePointerCannotSkipTheCardScreens() {
        // A borrower who was mid-flow when V75 shipped carries a CONSENT pointer; the documents are
        // missing, so the server must hold them on the Aadhaar screen rather than let them submit.
        through(JourneyService.Step.AADHAAR);
        when(verificationRepository.findByApplicationIdOrderByIdAsc(APP))
                .thenReturn(List.of(check(ApplicationVerificationService.SALARY)));
        app.setJourneyStep("CONSENT");
        assertThat(journey.current(APP).step()).isEqualTo("AADHAAR");

        through(JourneyService.Step.PAN_CARD);
        assertThat(journey.current(APP).step()).isEqualTo("PAN_CARD");
    }

    @Test
    void theIntakeCountsTheTwoCardScreens() {
        through(JourneyService.Step.EMPLOYER);
        JourneyService.JourneyView view = journey.current(APP);
        assertThat(view.steps()).containsSequence("PAYSLIPS", "AADHAAR", "PAN_CARD", "CONSENT");
        assertThat(view.total()).isEqualTo(12);
        assertThat(JourneyService.Step.AADHAAR.route()).isEqualTo("/signup/aadhaar");
        assertThat(JourneyService.Step.PAN_CARD.route()).isEqualTo("/signup/pan-card");
    }

    @Test
    void passwordFollowsOtpAndSkippingItResumesAtEmployment() {
        through(JourneyService.Step.SET_PASSWORD);
        assertThat(journey.current(APP).step()).isEqualTo("SET_PASSWORD");

        app.setJourneyStep("SET_PASSWORD");
        assertThat(journey.current(APP).step()).isEqualTo("EMPLOYMENT");
    }

    @Test
    void pointerNeverDragsABorrowerBackwards() {
        through(JourneyService.Step.BANK);
        app.setJourneyStep("START");   // stale pointer from an older device
        assertThat(journey.current(APP).step()).isEqualTo("BANK");
    }

    @Test
    void advanceOnlyMovesForward() {
        through(JourneyService.Step.CONSENT);   // past screen 6, so the email gate is satisfied
        app.setJourneyStep("BANK");
        journey.advance(APP, JourneyService.Step.OTP);
        assertThat(app.getJourneyStep()).isEqualTo("BANK");

        journey.advance(APP, JourneyService.Step.CONSENT);
        assertThat(app.getJourneyStep()).isEqualTo("CONSENT");
    }

    @Test
    void submittedApplicationIsOutOfIntake() {
        app.setStatus(ApplicationStatus.KYC_PENDING);
        assertThat(journey.current(APP).step()).isEqualTo("DONE");
        assertThat(journey.current(APP).route()).isEqualTo("/loan/status");
    }

    // ---- the Phase-3 offer journey (V46) -----------------------------------

    @Test
    void aSanctionedApplicationStartsTheOfferJourney() {
        app.setStatus(ApplicationStatus.SANCTIONED);

        assertThat(journey.current(APP).step()).isEqualTo("OFFER_AMOUNT");
        assertThat(journey.current(APP).route()).isEqualTo("/loan/amount");
        assertThat(journey.current(APP).total()).isEqualTo(10);
        assertThat(journey.current(APP).steps()).doesNotContain("OFFER_ESIGN");
    }

    @Test
    void theOfferJourneyDerivesFromWhatTheBorrowerHasSaved() {
        app.setStatus(ApplicationStatus.SANCTIONED);
        app.setAmountRequested(1_500_000L);
        // Derivation steps over the repayment-date screen, which leaves no trace of its own.
        assertThat(journey.current(APP).step()).isEqualTo("OFFER_DIGILOCKER");

        when(verificationRepository.findByApplicationIdOrderByIdAsc(APP))
                .thenReturn(List.of(check(ApplicationVerificationService.AADHAAR)));
        assertThat(journey.current(APP).step()).isEqualTo("OFFER_REFERENCES");

        when(referenceRepository.findByApplicationIdOrderBySlotAsc(APP))
                .thenReturn(List.of(new com.navix.loan.entity.ApplicationReference(),
                        new com.navix.loan.entity.ApplicationReference()));
        assertThat(journey.current(APP).step()).isEqualTo("OFFER_SELFIE");
    }

    /** A FAILED check still counts as attempted — Phase 3 failures pass through (decision 11). */
    @Test
    void aFailedOfferCheckDoesNotHoldTheBorrowerOnItsScreen() {
        app.setStatus(ApplicationStatus.SANCTIONED);
        app.setAmountRequested(1_500_000L);
        ApplicationVerification failed = check(ApplicationVerificationService.AADHAAR);
        failed.setStatus(ApplicationVerificationService.FAIL);
        when(verificationRepository.findByApplicationIdOrderByIdAsc(APP)).thenReturn(List.of(failed));

        assertThat(journey.current(APP).step()).isEqualTo("OFFER_REFERENCES");
    }

    @Test
    void anIntakePointerLeftOnTheRowNeverHoldsBackTheOfferJourney() {
        app.setStatus(ApplicationStatus.SANCTIONED);
        app.setJourneyStep("BANK"); // an intake step, meaningless to the offer registry

        assertThat(journey.current(APP).step()).isEqualTo("OFFER_AMOUNT");
    }

    @Test
    void advanceResolvesAStepNameAgainstEitherRegistry() {
        // The controller takes the step as a string because both registries share one endpoint.
        journey.advance(APP, "OFFER_SELFIE");
        assertThat(app.getJourneyStep()).isEqualTo("OFFER_SELFIE");

        journey.advance(APP, "NOT_A_STEP"); // a stale client must not hard-fail
        assertThat(app.getJourneyStep()).isEqualTo("OFFER_SELFIE");
    }

    @Test
    void aLegacyEsignPointerResumesOnTheAgreementPage() {
        app.setStatus(ApplicationStatus.SANCTIONED);
        app.setAmountRequested(1_500_000L);
        app.setJourneyStep("OFFER_ESIGN");
        when(verificationRepository.findByApplicationIdOrderByIdAsc(APP)).thenReturn(List.of(
                check(ApplicationVerificationService.AADHAAR),
                check(ApplicationVerificationService.SELFIE),
                check(ApplicationVerificationService.ADDRESS)));
        when(referenceRepository.findByApplicationIdOrderBySlotAsc(APP)).thenReturn(List.of(
                new com.navix.loan.entity.ApplicationReference(),
                new com.navix.loan.entity.ApplicationReference()));

        assertThat(journey.current(APP).step()).isEqualTo("OFFER_SANCTION_LETTER");
        assertThat(journey.current(APP).route()).isEqualTo("/loan/sanction-letter");
    }

    @Test
    void aReapplyWithCarriedIdentityNeverOffersDigiLockerAgain() {
        app.setStatus(ApplicationStatus.SANCTIONED);
        app.setReappliedFrom(99L);
        when(verificationRepository.findByApplicationIdOrderByIdAsc(APP)).thenReturn(List.of(
                check(ApplicationVerificationService.AADHAAR),
                check(ApplicationVerificationService.SELFIE),
                check(ApplicationVerificationService.ADDRESS)));
        when(referenceRepository.findByApplicationIdOrderBySlotAsc(APP)).thenReturn(List.of(
                new com.navix.loan.entity.ApplicationReference(),
                new com.navix.loan.entity.ApplicationReference()));

        assertThat(journey.current(APP).steps())
                .doesNotContain("OFFER_DIGILOCKER", "OFFER_SELFIE", "OFFER_ADDRESS", "OFFER_REFERENCES", "OFFER_ESIGN");
        assertThat(journey.current(APP).total()).isEqualTo(6);
    }

    // ---- V74: staff "send the customer a link" reopen -----------------------

    /** A reopened row is excluded from {@code attemptedChecks}, so derivation sends the borrower right
     *  back to the screen staff just reopened instead of bouncing them past it. */
    @Test
    void aReopenedCheckIsIgnoredByDerivation() {
        app.setStatus(ApplicationStatus.SANCTIONED);
        app.setAmountRequested(1_500_000L);
        ApplicationVerification aadhaar = check(ApplicationVerificationService.AADHAAR);
        aadhaar.setReopenedAt(Instant.now());
        when(verificationRepository.findByApplicationIdOrderByIdAsc(APP)).thenReturn(List.of(aadhaar));

        assertThat(journey.current(APP).step()).isEqualTo("OFFER_DIGILOCKER");
    }

    /** {@code rewind} only moves the pointer back when it is genuinely ahead of the reopened step. */
    @Test
    void rewindMovesThePointerBackOnlyWhenItIsAhead() {
        app.setJourneyStep("OFFER_SANCTION_LETTER");
        journey.rewind(APP, JourneyService.OfferStep.OFFER_SELFIE);
        assertThat(app.getJourneyStep()).isEqualTo("OFFER_SELFIE");

        app.setJourneyStep("OFFER_DIGILOCKER"); // already behind the reopened step — leave it alone
        journey.rewind(APP, JourneyService.OfferStep.OFFER_SELFIE);
        assertThat(app.getJourneyStep()).isEqualTo("OFFER_DIGILOCKER");

        app.setJourneyStep(null);
        journey.rewind(APP, JourneyService.OfferStep.OFFER_SELFIE);
        assertThat(app.getJourneyStep()).isEqualTo("OFFER_SELFIE");

        app.setJourneyStep("BANK"); // an intake pointer, meaningless to the offer registry
        journey.rewind(APP, JourneyService.OfferStep.OFFER_SELFIE);
        assertThat(app.getJourneyStep()).isEqualTo("OFFER_SELFIE");
    }

    /**
     * The signature is the one offer step a mere row does not satisfy: esignInit writes PENDING as
     * soon as the provider session is minted, and before this the borrower who closed the Signzy tab
     * was derived straight onto the disbursal-account screen — six loans went out unsigned (Sep 2026).
     */
    @Test
    void aStartedButUnsignedESignHoldsTheBorrowerOnTheSanctionLetter() {
        app.setStatus(ApplicationStatus.SANCTIONED);
        app.setAmountRequested(1_500_000L);
        app.setJourneyStep("OFFER_DISBURSAL_ACCOUNT"); // the pointer the old derivation let them reach
        ApplicationVerification pending = check(ApplicationVerificationService.ESIGN);
        pending.setStatus(ApplicationVerificationService.PENDING);
        when(verificationRepository.findByApplicationIdOrderByIdAsc(APP)).thenReturn(List.of(
                check(ApplicationVerificationService.AADHAAR),
                check(ApplicationVerificationService.SELFIE),
                check(ApplicationVerificationService.ADDRESS),
                pending));
        when(verificationRepository.findByApplicationIdAndCheckType(APP, ApplicationVerificationService.ESIGN))
                .thenReturn(Optional.of(pending));
        when(referenceRepository.findByApplicationIdOrderBySlotAsc(APP)).thenReturn(List.of(
                new com.navix.loan.entity.ApplicationReference(),
                new com.navix.loan.entity.ApplicationReference()));

        assertThat(journey.current(APP).step()).isEqualTo("OFFER_SANCTION_LETTER");

        pending.setStatus(ApplicationVerificationService.PASS);
        assertThat(journey.current(APP).step()).isEqualTo("OFFER_DISBURSAL_ACCOUNT");
    }

    private static ApplicationVerification check(String type) {
        ApplicationVerification v = new ApplicationVerification();
        v.setApplicationId(APP);
        v.setCheckType(type);
        v.setStatus(ApplicationVerificationService.PASS);
        return v;
    }
}
