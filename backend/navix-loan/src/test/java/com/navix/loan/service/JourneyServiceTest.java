package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

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

    private JourneyService journey;
    private LoanApplication app;

    @BeforeEach
    void setUp() {
        journey = new JourneyService(applicationRepository, profileRepository, verificationRepository,
                referenceRepository);
        app = new LoanApplication();
        app.setId(APP);
        app.setCustomerId(7L);
        app.setStatus(ApplicationStatus.DRAFT);
        lenient().when(applicationRepository.findById(APP)).thenReturn(Optional.of(app));
        lenient().when(verificationRepository.findByApplicationIdOrderByIdAsc(APP)).thenReturn(List.of());
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
        if (step.ordinal() > JourneyService.Step.EMAIL.ordinal()) p.setOfficialEmail("a@acme.com");
        if (step.ordinal() > JourneyService.Step.BANK.ordinal()) {
            p.setSalaryAccountNumber("123456789");
            p.setSalaryIfsc("HDFC0001234");
        }
        when(profileRepository.findByApplicationId(APP)).thenReturn(Optional.of(p));
        return p;
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

        when(verificationRepository.findByApplicationIdOrderByIdAsc(APP))
                .thenReturn(List.of(check(ApplicationVerificationService.SALARY)));
        assertThat(journey.current(APP).step()).isEqualTo("CONSENT");

        when(verificationRepository.findByApplicationIdOrderByIdAsc(APP)).thenReturn(List.of(
                check(ApplicationVerificationService.SALARY), check(ApplicationVerificationService.PAN)));
        assertThat(journey.current(APP).step()).isEqualTo("SUBMITTED");
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

    private static ApplicationVerification check(String type) {
        ApplicationVerification v = new ApplicationVerification();
        v.setApplicationId(APP);
        v.setCheckType(type);
        v.setStatus(ApplicationVerificationService.PASS);
        return v;
    }
}
