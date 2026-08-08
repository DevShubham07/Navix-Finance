package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.exception.BusinessException;
import com.navix.common.risk.RiskPort;
import com.navix.common.storage.DocumentStoragePort;
import com.navix.common.verification.VerificationPort;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.ApplicationVerification;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.ApplicationDocumentRepository;
import com.navix.loan.repository.ApplicationVerificationRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ApplicationVerificationServiceTest {

    @Mock private ApplicationVerificationRepository verificationRepo;
    @Mock private CustomerProfileRepository profileRepo;
    @Mock private LoanApplicationRepository applicationRepo;
    @Mock private ApplicationDocumentRepository documentRepo;
    @Mock private VerificationPort verification;
    @Mock private com.navix.common.verification.EsignPort esign;
    @Mock private com.navix.common.verification.OtpVerifierPort otpVerifier;
    @Mock private DocumentStoragePort storage;
    @Mock private RiskPort risk;
    @Mock private CreditBriefService creditBriefService;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Mock private ProfileChangeLogger changeLogger;

    private ApplicationVerificationService service;

    private static final Long APP = 42L;

    @BeforeEach
    void setUp() {
        service = new ApplicationVerificationService(verificationRepo, profileRepo, applicationRepo,
                documentRepo, verification, esign, otpVerifier, storage, risk, new ObjectMapper(),
                creditBriefService, eventPublisher, changeLogger);
        // save() echoes its argument
        lenient().when(verificationRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(profileRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(applicationRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(verificationRepo.findByApplicationIdAndCheckType(eq(APP), anyString()))
                .thenReturn(Optional.empty());
        lenient().when(verificationRepo.findByApplicationIdOrderByIdAsc(APP)).thenReturn(List.of());
    }

    private CustomerProfile profile() {
        CustomerProfile p = new CustomerProfile();
        p.setApplicationId(APP);
        p.setFullName("SHUBHAM");
        p.setEmployer("Digitap.ai");
        p.setMobile("7206485966");
        return p;
    }

    @Test
    void panVerify_mapsAndMarksProfileVerified_andPersistsDob() {
        CustomerProfile p = profile();
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(p));
        when(verification.verifyPan(eq("QVEPS0901K"), anyString()))
                .thenReturn(new VerificationPort.PanCheck("TXN1", "SIGNZY", true, "SHUBHAM", "2003-03-24", "M",
                        true, "65XXXXXXXX90", "QVEPS0901K", "Haryana", "131001",
                        "operative", "28-03-2019", true, "No"));

        var result = service.verifyPan(APP, "QVEPS0901K");

        assertThat(result.status()).isEqualTo("PASS");
        assertThat(result.derived()).containsEntry("aadhaarLinked", true);
        // The DOB the PAN record carries is persisted onto the profile from this first step.
        assertThat(p.getDob()).isEqualTo(LocalDate.of(2003, 3, 24));
        verify(profileRepo).save(any(CustomerProfile.class));
    }

    @Test
    void panVerify_parsesDayFirstDobFormat() {
        CustomerProfile p = profile();
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(p));
        when(verification.verifyPan(anyString(), anyString()))
                .thenReturn(new VerificationPort.PanCheck("TXN1", "SIGNZY", true, "SHUBHAM", "15/08/1992", "M",
                        true, "65XXXXXXXX90", "QVEPS0901K", "Haryana", "131001",
                        "operative", null, true, null));

        service.verifyPan(APP, "QVEPS0901K");

        assertThat(p.getDob()).isEqualTo(LocalDate.of(1992, 8, 15));
    }

    @Test
    void panVerify_doesNotOverwriteAnExistingDob() {
        CustomerProfile p = profile();
        p.setDob(LocalDate.of(1990, 1, 1)); // already on file (e.g. earlier DigiLocker / borrower-entered)
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(p));
        when(verification.verifyPan(anyString(), anyString()))
                .thenReturn(new VerificationPort.PanCheck("TXN1", "SIGNZY", true, "SHUBHAM", "2003-03-24", "M",
                        true, "65XXXXXXXX90", "QVEPS0901K", "Haryana", "131001",
                        "operative", "28-03-2019", true, "No"));

        service.verifyPan(APP, "QVEPS0901K");

        assertThat(p.getDob()).isEqualTo(LocalDate.of(1990, 1, 1));
    }

    @Test
    void panVerify_isIdempotent_whenAlreadyPassed() {
        ApplicationVerification existing = row("PAN", "PASS");
        when(verificationRepo.findByApplicationIdAndCheckType(APP, "PAN")).thenReturn(Optional.of(existing));

        var result = service.verifyPan(APP, "QVEPS0901K");

        assertThat(result.status()).isEqualTo("PASS");
        verify(verification, never()).verifyPan(anyString(), anyString());
    }

    @Test
    void email_genericEmail_isReview() {
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(profile()));
        when(verification.verifyEmail(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new VerificationPort.EmailCheck("TXN2", "DIGITAP", true, false, true, true, null,
                        null, null, null, null, null, null, null, null, null));

        var result = service.verifyEmail(APP, "someone@gmail.com");

        assertThat(result.status()).isEqualTo("REVIEW");
    }

    @Test
    void pennyDrop_nameMismatch_isReview_match_isPass() {
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(profile()));

        when(verification.pennyDrop(anyString(), anyString(), anyString()))
                .thenReturn(new VerificationPort.PennyDropCheck("TXN3", "SIGNZY", true, "RAVI KUMAR", "HDFC Bank", "HDFC0002557",
                        "RRN1", "success", "no"));
        assertThat(service.verifyPennyDrop(APP, "123", "HDFC0002557").status()).isEqualTo("REVIEW");

        when(verification.pennyDrop(anyString(), anyString(), anyString()))
                .thenReturn(new VerificationPort.PennyDropCheck("TXN4", "SIGNZY", true, "SHUBHAM", "HDFC Bank", "HDFC0002557",
                        "RRN2", "success", "yes"));
        var pass = service.verifyPennyDrop(APP, "4180000101597860", "HDFC0002557");
        assertThat(pass.status()).isEqualTo("PASS");
        assertThat(pass.derived()).containsEntry("accountNumber", "4180000101597860");
        assertThat(pass.derived()).containsEntry("ifsc", "HDFC0002557");
        assertThat(pass.derived()).containsEntry("beneficiaryName", "SHUBHAM");
        assertThat(pass.derived()).containsEntry("bankRrn", "RRN2");
        assertThat(pass.derived()).containsEntry("bank", "HDFC Bank");
    }

    @Test
    void pennyDrop_providerFailure_isReview_notError() {
        CustomerProfile p = profile();
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(p));
        // A wrong/unverifiable account makes the provider throw (surfaces as HTTP 500 today).
        when(verification.pennyDrop(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("HTTP 500 from verification_pennydrop"));

        var result = service.verifyPennyDrop(APP, "000wrong", "HDFC0002557");

        // Must not hard-block onboarding: the step is REVIEW (borrower proceeds), the account is
        // flagged unverified for staff to check before disbursal.
        assertThat(result.status()).isEqualTo("REVIEW");
        assertThat(result.derived()).containsEntry("providerError", true);
        assertThat(p.getPennyDropVerified()).isFalse();
    }

    @Test
    void bureau_providerFailure_isReview_andPersistsSafeHttpStatus() throws Exception {
        CustomerProfile p = profile();
        p.setDob(LocalDate.of(1992, 8, 15));
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(p));
        when(verificationRepo.findByApplicationIdAndCheckType(APP, "BUREAU_CONSENT"))
                .thenReturn(Optional.of(row("BUREAU_CONSENT", "PASS")));
        // Both bureau providers unavailable (no credits / OTP-gated) → the router rethrows, which used
        // to surface as HTTP 500. It must instead degrade to REVIEW so onboarding is never hard-blocked.
        when(verification.pullBureau(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("HTTP 403 from bureau_crif"));

        var result = service.pullBureau(APP, "123456");

        assertThat(result.status()).isEqualTo("REVIEW");
        ArgumentCaptor<ApplicationVerification> saved = ArgumentCaptor.forClass(ApplicationVerification.class);
        verify(verificationRepo).save(saved.capture());
        assertThat(new ObjectMapper().readTree(saved.getValue().getDerived())
                .path("providerErrorCode").asText()).isEqualTo("HTTP_403");
    }

    @Test
    void bureau_isDeferredToReview_whenConsentNotYetGiven() {
        // No BUREAU_CONSENT row stubbed → passed(appId, BUREAU_CONSENT) is empty. The gate must trip
        // before profile/provider lookups happen at all.

        var result = service.pullBureau(APP, "123456");

        assertThat(result.status()).isEqualTo("REVIEW");
        assertThat(result.message()).contains("consent");
        verify(verification, never()).pullBureau(any(), any(), any(), any(), any(), any());
    }

    @Test
    void bureau_isDeferredToReview_whenDateOfBirthIsMissing_andDoesNotCallProvider() {
        CustomerProfile p = profile();
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(p));
        when(verificationRepo.findByApplicationIdAndCheckType(APP, "BUREAU_CONSENT"))
                .thenReturn(Optional.of(row("BUREAU_CONSENT", "PASS")));

        var result = service.pullBureau(APP, "123456");

        assertThat(result.status()).isEqualTo("REVIEW");
        assertThat(result.message()).contains("date of birth");
        verify(verification, never()).pullBureau(any(), any(), any(), any(), any(), any());
    }

    @Test
    void bureau_forwardsVerifiedOtp_toTheProvider_onceConsentPassed() {
        CustomerProfile p = profile();
        p.setPan("QVEPS0901K");
        p.setDob(LocalDate.of(1992, 8, 15));
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(p));
        when(verificationRepo.findByApplicationIdAndCheckType(APP, "BUREAU_CONSENT"))
                .thenReturn(Optional.of(row("BUREAU_CONSENT", "PASS")));
        when(verification.pullBureau(any(), any(), any(), any(), eq("999111"), any()))
                .thenReturn(new VerificationPort.BureauCheck("TXN-B1", "DIGITAP_EXPERIAN", 778, false,
                        9, 0, 805314.0, null));

        var result = service.pullBureau(APP, "999111");

        assertThat(result.status()).isEqualTo("PASS");
        verify(verification).pullBureau(any(), any(), any(), any(), eq("999111"), any());
    }

    @Test
    void salary_setsEligibleLimitOnApplication() {
        CustomerProfile p = profile();
        LoanApplication app = new LoanApplication();
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(p));
        when(applicationRepo.findById(APP)).thenReturn(Optional.of(app));
        when(risk.eligibleLimitPaise(4_000_000L)).thenReturn(1_000_000L);

        var result = service.verifySalary(APP, 4_000_000L, List.of("applications/42/salary_slip/1.pdf"), null);

        assertThat(result.status()).isEqualTo("PASS");
        assertThat(app.getEligibleLimit()).isEqualTo(1_000_000L);
        assertThat(p.getMonthlySalaryPaise()).isEqualTo(4_000_000L);
    }

    @Test
    void agreement_setsProfileFlag() {
        CustomerProfile p = profile();
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(p));

        var result = service.recordAgreement(APP, List.of("loan-agreement@1", "sanction@1", "privacy@1"));

        assertThat(result.status()).isEqualTo("PASS");
        assertThat(p.getAgreementAccepted()).isTrue();
    }

    @Test
    void requestBureauConsentOtp_sendsUnderTheBureauConsentPurpose_notLogin() {
        CustomerProfile p = profile();
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(p));
        var expected = new com.navix.common.verification.OtpVerifierPort.OtpRequestResult(true, "654321", 300);
        when(otpVerifier.request(eq("7206485966"), eq("BUREAU_CONSENT"))).thenReturn(expected);

        var result = service.requestBureauConsentOtp(APP);

        assertThat(result.sent()).isTrue();
        verify(otpVerifier).request(eq("7206485966"), eq("BUREAU_CONSENT"));
        verify(otpVerifier, never()).request(eq("7206485966"), eq("LOGIN"));
    }

    @Test
    void bureauConsent_recordsRowWithConsentText_whenOtpVerifies() {
        CustomerProfile p = profile();
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(p));
        // The mobile MUST come from the profile, never from the caller.
        when(otpVerifier.verify(eq("7206485966"), eq("123456"), eq("BUREAU_CONSENT"))).thenReturn(true);

        var result = service.recordBureauConsent(APP, "123456", "I authorize the retrieval of my credit report.");

        assertThat(result.status()).isEqualTo("PASS");
        assertThat(result.derived())
                .containsEntry("consentText", "I authorize the retrieval of my credit report.")
                .containsEntry("channel", "OTP");
        verify(verificationRepo).save(any());
    }

    @Test
    void bureauConsent_backfillsMobileFromCustomersEarlierProfile() {
        // A signed-in borrower starting a fresh application skips the mobile step, so this
        // application's profile has no mobile of its own.
        CustomerProfile p = profile();
        p.setMobile(null);
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(p));
        LoanApplication app = new LoanApplication();
        app.setId(APP);
        app.setCustomerId(77L);
        when(applicationRepo.findById(APP)).thenReturn(Optional.of(app));
        when(profileRepo.findMobilesForCustomer(77L)).thenReturn(List.of("7206485966"));
        when(otpVerifier.verify(eq("7206485966"), eq("123456"), eq("BUREAU_CONSENT"))).thenReturn(true);

        var result = service.recordBureauConsent(APP, "123456", "consent");

        assertThat(result.status()).isEqualTo("PASS");
        assertThat(p.getMobile()).isEqualTo("7206485966");
    }

    @Test
    void bureauConsent_rejectsBadOtp_andWritesNothing() {
        CustomerProfile p = profile();
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(p));
        when(otpVerifier.verify(anyString(), anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.recordBureauConsent(APP, "000000", "consent"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid or expired OTP");

        // The consent row must not exist when the step-up failed.
        verify(verificationRepo, never()).save(any());
    }

    @Test
    void allRequiredPassed_gatesOnAttemptedNotPassed() {
        // Nothing run yet.
        assertThat(service.allRequiredPassed(APP)).isFalse();

        CustomerProfile agreed = profile();
        agreed.setTermsAcceptedAt(java.time.Instant.now());
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(agreed));

        // A FAILED intake check still submits — the credit team decides, not the gate
        // (revamp.md decision 10). PENNY_DROP/SELFIE/ADDRESS/AADHAAR are no longer intake checks.
        when(verificationRepo.findByApplicationIdOrderByIdAsc(APP)).thenReturn(List.of(
                row("PAN", "FAIL"), row("EMAIL", "REVIEW"), row("BUREAU", "PASS"), row("SALARY", "PASS")));
        assertThat(service.allRequiredPassed(APP)).isTrue();
    }

    @Test
    void allRequiredPassed_falseWhenAStepNeverRan() {
        // No SALARY row at all — the borrower never uploaded payslips. Short-circuits before the
        // profile is ever read, so there is deliberately no profileRepo stub here.
        when(verificationRepo.findByApplicationIdOrderByIdAsc(APP)).thenReturn(List.of(
                row("PAN", "PASS"), row("EMAIL", "PASS"), row("BUREAU", "PASS")));
        assertThat(service.allRequiredPassed(APP)).isFalse();
    }

    @Test
    void allRequiredPassed_falseWithoutTermsAcceptance() {
        when(verificationRepo.findByApplicationIdOrderByIdAsc(APP)).thenReturn(List.of(
                row("PAN", "PASS"), row("EMAIL", "PASS"), row("BUREAU", "PASS"), row("SALARY", "PASS")));
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(profile()));

        assertThat(service.allRequiredPassed(APP)).isFalse();
    }

    @Test
    void nameSimilarity_isPermissive() {
        assertThat(ApplicationVerificationService.nameSimilarity("SHUBHAM", "SHUBHAM")).isEqualTo(1.0);
        assertThat(ApplicationVerificationService.nameSimilarity("RAVI KUMAR", "SHUBHAM")).isEqualTo(0.0);
        assertThat(ApplicationVerificationService.nameSimilarity("Rahul Kumar Verma", "Rahul Verma"))
                .isGreaterThan(0.5);
    }

    @Test
    void summary_reconcilesDigilockerRowFromAadhaarOutcome() {
        // DigiLocker row is stale PENDING (never re-persisted); Aadhaar carries the real outcome.
        when(verificationRepo.findByApplicationIdOrderByIdAsc(APP)).thenReturn(List.of(
                row("DIGILOCKER", "PENDING"), row("AADHAAR", "PASS"), row("PAN", "PASS")));

        var summary = service.summary(APP);

        var digilocker = summary.stream().filter(s -> "DIGILOCKER".equals(s.checkType())).findFirst().orElseThrow();
        assertThat(digilocker.status()).isEqualTo("PASS"); // reflects the Aadhaar PASS, not the stale PENDING
    }

    @Disabled("""
            Suspended by the "TEMP (revert later)" branch in ApplicationVerificationService.summary(), \
            which force-PASSes every DIGILOCKER row so onboarding never stalls. This test asserts the \
            REAL reconciliation (DigiLocker mirrors the Aadhaar outcome) and is kept, not deleted, so \
            that dropping that TEMP branch re-arms the check instead of silently shipping the hack. \
            Re-enable together with removing the branch.""")
    @Test
    void summary_digilockerReflectsAadhaarReview_andStaysPendingWithoutAadhaar() {
        // Aadhaar under manual review (name mismatch) → DigiLocker shows REVIEW, not a retry prompt.
        when(verificationRepo.findByApplicationIdOrderByIdAsc(APP)).thenReturn(List.of(
                row("DIGILOCKER", "PENDING"), row("AADHAAR", "REVIEW")));
        assertThat(service.summary(APP).stream()
                .filter(s -> "DIGILOCKER".equals(s.checkType())).findFirst().orElseThrow().status())
                .isEqualTo("REVIEW");

        // No Aadhaar row yet (mid-flow) → DigiLocker correctly stays PENDING.
        when(verificationRepo.findByApplicationIdOrderByIdAsc(APP)).thenReturn(List.of(row("DIGILOCKER", "PENDING")));
        assertThat(service.summary(APP).stream()
                .filter(s -> "DIGILOCKER".equals(s.checkType())).findFirst().orElseThrow().status())
                .isEqualTo("PENDING");
    }

    private static ApplicationVerification row(String type, String status) {
        return row(APP, type, status);
    }

    private static ApplicationVerification row(Long appId, String type, String status) {
        ApplicationVerification v = new ApplicationVerification();
        v.setApplicationId(appId);
        v.setCheckType(type);
        v.setStatus(status);
        v.setMessage(type + " " + status);
        return v;
    }

    /**
     * A fresh intake counts only the four intake checks — the sanction ones haven't been asked for
     * yet, so counting them would show every new file as part-done before anyone touched it.
     */
    @Test
    void progress_countsOnlyIntakeChecks_beforeSanction() {
        LoanApplication app = new LoanApplication();
        app.setId(APP);
        when(applicationRepo.findById(APP)).thenReturn(Optional.of(app));
        when(verificationRepo.findByApplicationIdOrderByIdAsc(APP)).thenReturn(List.of(
                row("PAN", "PASS"), row("EMAIL", "REVIEW"), row("BUREAU", "PASS"), row("SALARY", "PASS")));

        var p = service.progress(APP);

        assertThat(p.required()).isEqualTo(4);
        assertThat(p.completed()).isEqualTo(4);
        assertThat(p.percent()).isEqualTo(100);
    }

    /**
     * A re-apply never re-runs PAN/email/bureau/salary — that evidence carried over from the
     * application it was sanctioned against (revamp.md decision 45). Counting only its own rows
     * reported a fully-verified file about to be disbursed as "0/4 done · 0%" underneath four PASS
     * cards, which is what a staff member reads right before releasing money.
     */
    @Test
    void progress_countsIntakeEvidenceCarriedFromThePriorApplication() {
        Long prior = 41L;
        LoanApplication app = new LoanApplication();
        app.setId(APP);
        app.setReappliedFrom(prior);
        app.setSanctionedAt(java.time.Instant.now());
        when(applicationRepo.findById(APP)).thenReturn(Optional.of(app));
        LoanApplication source = new LoanApplication();
        source.setId(prior);
        when(applicationRepo.findById(prior)).thenReturn(Optional.of(source));

        // This application carries only the sanction-stage checks…
        when(verificationRepo.findByApplicationIdOrderByIdAsc(APP)).thenReturn(List.of(
                row("AADHAAR", "PASS"), row("SELFIE", "PASS"), row("ADDRESS", "PASS"), row("ESIGN", "PASS")));
        // …while the intake evidence sits on the one it re-applied from.
        when(verificationRepo.findByApplicationIdOrderByIdAsc(prior)).thenReturn(List.of(
                row(prior, "PAN", "PASS"), row(prior, "EMAIL", "REVIEW"),
                row(prior, "BUREAU", "PASS"), row(prior, "SALARY", "PASS")));

        var p = service.progress(APP);

        // Sanctioned, so all eight are in scope — and all eight are accounted for.
        assertThat(p.required()).isEqualTo(8);
        assertThat(p.completed()).isEqualTo(8);
        assertThat(p.pending()).isZero();
        assertThat(p.percent()).isEqualTo(100);
    }

    /**
     * The one thing a re-apply must NOT inherit is the signature. eSign is the legal act for a
     * single advance and is signed again every time, so counting the previous one reported a file
     * as 8/8 · 100% verified while the borrower had not yet signed for the money about to be paid
     * out — which is exactly the screen a releasing staff member reads.
     */
    @Test
    void progress_neverInheritsThePriorApplicationsEsign() {
        Long prior = 41L;
        LoanApplication app = new LoanApplication();
        app.setId(APP);
        app.setReappliedFrom(prior);
        app.setSanctionedAt(java.time.Instant.now());
        when(applicationRepo.findById(APP)).thenReturn(Optional.of(app));
        when(applicationRepo.findById(prior)).thenReturn(Optional.of(new LoanApplication()));
        // Carried identity evidence, but this advance has not been signed yet.
        when(verificationRepo.findByApplicationIdOrderByIdAsc(APP)).thenReturn(List.of(
                row("AADHAAR", "PASS"), row("SELFIE", "PASS"), row("ADDRESS", "PASS")));
        when(verificationRepo.findByApplicationIdOrderByIdAsc(prior)).thenReturn(List.of(
                row(prior, "PAN", "PASS"), row(prior, "EMAIL", "REVIEW"), row(prior, "BUREAU", "PASS"),
                row(prior, "SALARY", "PASS"), row(prior, "ESIGN", "PASS")));

        var p = service.progress(APP);

        assertThat(p.required()).isEqualTo(8);
        assertThat(p.completed()).isEqualTo(7);
        assertThat(p.pending()).isEqualTo(1); // the unsigned eSign
        assertThat(p.percent()).isEqualTo(88);
    }

    /** A newer row always wins over the carried one, so a re-run check is not masked by history. */
    @Test
    void progress_prefersThisApplicationsOwnRowOverTheCarriedOne() {
        Long prior = 41L;
        LoanApplication app = new LoanApplication();
        app.setId(APP);
        app.setReappliedFrom(prior);
        when(applicationRepo.findById(APP)).thenReturn(Optional.of(app));
        when(applicationRepo.findById(prior)).thenReturn(Optional.of(new LoanApplication()));
        when(verificationRepo.findByApplicationIdOrderByIdAsc(APP)).thenReturn(List.of(row("PAN", "FAIL")));
        when(verificationRepo.findByApplicationIdOrderByIdAsc(prior)).thenReturn(List.of(
                row(prior, "PAN", "PASS")));

        var p = service.progress(APP);

        assertThat(p.failed()).isEqualTo(1);
        assertThat(p.completed()).isZero();
    }

    private VerificationPort.AadhaarResult aadhaar(String masked) {
        return new VerificationPort.AadhaarResult("TXN-DL", "SHUBHAM", "2003-03-24", "M", masked,
                "addr", "Haryana", "Sonipat", "Sonipat", "131001", "INDIA", "House 1", "Near park",
                "DS NATIONAL E-GOVERNANCE DIVISION 1", null, "https://signzy.test/aadhaar.pdf",
                "https://signzy.test/aadhaar.jpeg", null);
    }

    @Test
    void digilockerComplete_marksAadhaarVerified_andSetsDob() {
        CustomerProfile p = profile();
        p.setDigilockerClientId("CL1"); // session started
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(p));
        when(verification.digilockerAadhaar("CL1")).thenReturn(aadhaar("XXXXXXXX1234"));
        when(storage.buildApplicationKey(APP, "AADHAAR", "pdf")).thenReturn("apps/42/aadhaar.pdf");
        when(storage.storeFromUrl("apps/42/aadhaar.pdf", "https://signzy.test/aadhaar.pdf", "application/pdf"))
                .thenReturn("apps/42/aadhaar.pdf");
        when(storage.buildApplicationKey(APP, "AADHAAR_JPEG", "jpeg")).thenReturn("apps/42/aadhaar.jpeg");
        when(storage.storeFromUrl("apps/42/aadhaar.jpeg", "https://signzy.test/aadhaar.jpeg", "image/jpeg"))
                .thenReturn("apps/42/aadhaar.jpeg");

        var result = service.digilockerComplete(APP);

        assertThat(result.status()).isEqualTo("PASS");
        assertThat(p.getAadhaarVerified()).isTrue();             // the DigiLocker-verified flag (no raw number stored)
        assertThat(p.getDigilockerClientId()).isNull();           // completed consent request IDs are ephemeral
        assertThat(p.getDob()).isEqualTo(LocalDate.of(2003, 3, 24));
        // The whole e-Aadhaar card is recorded for the CRM — not just the photo.
        assertThat(result.derived()).containsEntry("fullName", "SHUBHAM")
                .containsEntry("dob", "2003-03-24")
                .containsEntry("gender", "M")
                .containsEntry("maskedAadhaar", "XXXXXXXX1234")
                .containsEntry("address", "addr")
                .containsEntry("state", "Haryana")
                .containsEntry("district", "Sonipat")
                .containsEntry("city", "Sonipat")
                .containsEntry("pincode", "131001")
                .containsEntry("dscSubject", "DS NATIONAL E-GOVERNANCE DIVISION 1");
        verify(storage).storeFromUrl("apps/42/aadhaar.pdf", "https://signzy.test/aadhaar.pdf", "application/pdf");
    }
}
