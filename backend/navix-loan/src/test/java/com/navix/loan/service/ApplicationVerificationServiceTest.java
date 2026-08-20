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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.exception.BusinessException;
import com.navix.common.risk.RiskPort;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.storage.DocumentStoragePort;
import com.navix.common.verification.EmailOtpPort;
import com.navix.common.verification.EsignPort;
import com.navix.common.verification.VerificationPort;
import com.navix.loan.entity.ApplicationDocument;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
    @Mock private com.navix.common.verification.EmailOtpPort emailOtp;
    @Mock private DocumentStoragePort storage;
    @Mock private RiskPort risk;
    @Mock private CreditBriefService creditBriefService;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Mock private ProfileChangeLogger changeLogger;
    @Mock private ApplicationFlowService flow;
    @Mock private PennyDropGuard pennyDropGuard;

    private ApplicationVerificationService service;

    private static final Long APP = 42L;

    @BeforeEach
    void setUp() {
        service = new ApplicationVerificationService(verificationRepo, profileRepo, applicationRepo,
                documentRepo, verification, esign, otpVerifier, emailOtp, storage, risk, new ObjectMapper(),
                creditBriefService, eventPublisher, changeLogger, flow, pennyDropGuard);
        // save() echoes its argument
        lenient().when(verificationRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(profileRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(applicationRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(verificationRepo.findByApplicationIdAndCheckType(eq(APP), anyString()))
                .thenReturn(Optional.empty());
        lenient().when(verificationRepo.findByApplicationIdOrderByIdAsc(APP)).thenReturn(List.of());
    }

    @AfterEach
    void clearActor() {
        ActorContext.clear();
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
    void manualDecision_preservesPennyDropDerivedAndAddsOverrideAudit() {
        ActorContext.set(new CurrentActor("17", "Credit Reviewer", "CREDIT_HEAD"));
        LoanApplication app = new LoanApplication();
        app.setId(APP);
        ApplicationVerification existing = row("PENNY_DROP", "PASS");
        existing.setDerived("{\"accountNumber\":\"4180000101597860\",\"bankRrn\":\"RRN-9\"}");
        when(applicationRepo.findById(APP)).thenReturn(Optional.of(app));
        when(verificationRepo.findByApplicationIdAndCheckType(APP, "PENNY_DROP"))
                .thenReturn(Optional.of(existing));

        var result = service.manualDecision(APP, "PENNY_DROP", true, "confirmed");

        assertThat(result.derived())
                .containsEntry("accountNumber", "4180000101597860")
                .containsEntry("bankRrn", "RRN-9")
                .containsEntry("manualOverride", true)
                .containsEntry("manualBy", "Credit Reviewer");
        assertThat(result.derived().get("manualAt")).isInstanceOf(String.class);
        assertThat(java.time.Instant.parse((String) result.derived().get("manualAt"))).isNotNull();
    }

    @Test
    void recordBankProofPending_flagsPennyDropAsReviewAwaitingTheDisbursementHead() {
        service.recordBankProofPending(APP, "999988887777", "ICIC0004321");

        ArgumentCaptor<ApplicationVerification> captor = ArgumentCaptor.forClass(ApplicationVerification.class);
        verify(verificationRepo).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("REVIEW");
        assertThat(captor.getValue().getProvider()).isEqualTo("MANUAL_PROOF");
    }

    @Test
    void recordAadhaarProofPending_requiresBothSides_missingBack() {
        when(documentRepo.findFirstByApplicationIdAndDocTypeOrderByIdDesc(APP, "AADHAAR_FRONT"))
                .thenReturn(Optional.of(new ApplicationDocument()));
        when(documentRepo.findFirstByApplicationIdAndDocTypeOrderByIdDesc(APP, "AADHAAR_BACK"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordAadhaarProofPending(APP))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "AADHAAR_PROOF_REQUIRED");
    }

    @Test
    void recordAadhaarProofPending_requiresBothSides_missingFront() {
        when(documentRepo.findFirstByApplicationIdAndDocTypeOrderByIdDesc(APP, "AADHAAR_FRONT"))
                .thenReturn(Optional.empty());
        lenient().when(documentRepo.findFirstByApplicationIdAndDocTypeOrderByIdDesc(APP, "AADHAAR_BACK"))
                .thenReturn(Optional.of(new ApplicationDocument()));

        assertThatThrownBy(() -> service.recordAadhaarProofPending(APP))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "AADHAAR_PROOF_REQUIRED");
    }

    @Test
    void recordAadhaarProofPending_withBothSides_upsertsAadhaarAsReviewAwaitingTheDisbursementHead() {
        when(documentRepo.findFirstByApplicationIdAndDocTypeOrderByIdDesc(APP, "AADHAAR_FRONT"))
                .thenReturn(Optional.of(new ApplicationDocument()));
        when(documentRepo.findFirstByApplicationIdAndDocTypeOrderByIdDesc(APP, "AADHAAR_BACK"))
                .thenReturn(Optional.of(new ApplicationDocument()));

        var result = service.recordAadhaarProofPending(APP);

        assertThat(result.status()).isEqualTo("REVIEW");
        assertThat(result.derived()).containsEntry("aadhaarProofPending", true);
        ArgumentCaptor<ApplicationVerification> captor = ArgumentCaptor.forClass(ApplicationVerification.class);
        verify(verificationRepo).save(captor.capture());
        assertThat(captor.getValue().getCheckType()).isEqualTo("AADHAAR");
        assertThat(captor.getValue().getStatus()).isEqualTo("REVIEW");
        assertThat(captor.getValue().getProvider()).isEqualTo("MANUAL_PROOF");
    }

    @Test
    void manualDecision_stillClearsDerivedForNonPennyDropChecks() {
        ActorContext.set(new CurrentActor("17", "Credit Reviewer", "CREDIT_HEAD"));
        LoanApplication app = new LoanApplication();
        app.setId(APP);
        ApplicationVerification existing = row("PAN", "REVIEW");
        existing.setDerived("{\"aadhaarLinked\":true}");
        when(applicationRepo.findById(APP)).thenReturn(Optional.of(app));
        when(verificationRepo.findByApplicationIdAndCheckType(APP, "PAN"))
                .thenReturn(Optional.of(existing));

        var result = service.manualDecision(APP, "PAN", true, "confirmed");

        assertThat(result.derived()).isEmpty();
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
    void bureauPersistsCompleteProviderResponseAndPassesItToTheGeneratedReport() throws Exception {
        CustomerProfile p = profile();
        p.setPan("QVEPS0901K");
        p.setDob(LocalDate.of(1992, 8, 15));
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(p));
        when(verificationRepo.findByApplicationIdAndCheckType(APP, "BUREAU_CONSENT"))
                .thenReturn(Optional.of(row("BUREAU_CONSENT", "PASS")));
        var facts = new com.navix.common.verification.BureauReportFacts(
                "SHUBHAM", "QVEPS0901K", "7206485966", "1992-08-15", "Sonipat", "131001",
                778, 3, 2, 1, 0, 25000L, 10000L, 15000L, 2, "RPT-FULL-1");
        String raw = """
                {"http_response_code":200,"request_id":"REQ-FULL-1","result":{"result_json":{
                "INProfileResponse":{"CAIS_Account":{"CAIS_Account_DETAILS":[{
                "Subscriber_Name":"TEST BANK","Payment_History_Profile":"000000"}]},
                "CAPS":{"CAPS_Application_Details":[{"Amount_Financed":"10000"}]}}}}}
                """;
        when(verification.pullBureau(any(), any(), any(), any(), eq("999111"), any()))
                .thenReturn(new VerificationPort.BureauCheck("REQ-FULL-1", "DIGITAP_EXPERIAN", 778,
                        false, 2, 0, 25000.0, facts, raw));

        var result = service.pullBureau(APP, "999111");

        assertThat(result.status()).isEqualTo("PASS");
        ArgumentCaptor<ApplicationVerification> saved = ArgumentCaptor.forClass(ApplicationVerification.class);
        verify(verificationRepo).save(saved.capture());
        var persisted = new ObjectMapper().readTree(saved.getValue().getRawResponse());
        assertThat(persisted.path("result").path("result_json").path("INProfileResponse")
                .path("CAIS_Account").path("CAIS_Account_DETAILS").path(0)
                .path("Payment_History_Profile").asText()).isEqualTo("000000");
        verify(creditBriefService).generate(APP, p, facts, raw);
    }

    @Test
    void salary_setsEligibleLimitOnApplication() {
        CustomerProfile p = profile();
        LoanApplication app = new LoanApplication();
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(p));
        when(applicationRepo.findById(APP)).thenReturn(Optional.of(app));
        when(risk.eligibleLimitPaise(4_000_000L)).thenReturn(1_000_000L);

        var result = service.verifySalary(APP, 4_000_000L, List.of("applications/42/salary_slip/1.pdf"), null, null);

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

    // ---- Personal-email OTP -------------------------------------------------------

    @Test
    void requestPersonalEmailOtp_rejectsWhenNoEmailOnFile() {
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(profile()));

        assertThatThrownBy(() -> service.requestPersonalEmailOtp(APP))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No personal email on file");
        verifyNoInteractions(emailOtp);
    }

    @Test
    void requestPersonalEmailOtp_sendsToTheSavedAddress() {
        CustomerProfile p = profile();
        p.setEmail("borrower@example.com");
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(p));
        var expected = new com.navix.common.verification.OtpVerifierPort.OtpRequestResult(true, null, 600);
        when(emailOtp.request("borrower@example.com", EmailOtpPort.PERSONAL_EMAIL)).thenReturn(expected);

        var result = service.requestPersonalEmailOtp(APP);

        assertThat(result.sent()).isTrue();
        verify(emailOtp).request("borrower@example.com", EmailOtpPort.PERSONAL_EMAIL);
    }

    @Test
    void personalEmailOtp_passesAndFlagsProfile_whenOtpVerifies() {
        CustomerProfile p = profile();
        p.setEmail("borrower@example.com");
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(p));
        when(emailOtp.verify("borrower@example.com", "123456", EmailOtpPort.PERSONAL_EMAIL)).thenReturn(true);

        var result = service.verifyPersonalEmailOtp(APP, "123456");

        assertThat(result.status()).isEqualTo("PASS");
        assertThat(p.getPersonalEmailVerified()).isTrue();
        // Shown in full on the staff verification tab, matching every other identity field.
        assertThat(result.derived()).containsEntry("email", "borrower@example.com");
    }

    @Test
    void personalEmailOtp_wrongCodeThrowsInvalidOtp() {
        CustomerProfile p = profile();
        p.setEmail("borrower@example.com");
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(p));
        when(emailOtp.verify(anyString(), anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.verifyPersonalEmailOtp(APP, "000000"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid or expired code");
        verify(verificationRepo, never()).save(any());
    }

    @Test
    void emailOtp_isNotAmongTheRequiredIntakeChecks() {
        // Additive/non-blocking by design — must not wedge an application that never uses it.
        assertThat(ApplicationVerificationService.REQUIRED)
                .doesNotContain(ApplicationVerificationService.EMAIL_OTP);
        assertThat(ApplicationVerificationService.KNOWN_CHECKS)
                .doesNotContain(ApplicationVerificationService.EMAIL_OTP);
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

    /**
     * The two production penny-drop lockouts: a bank holding a shortened or initialised form of a
     * long name. Under the old Jaccard ratio both scored below the 0.60 threshold however well the
     * tokens actually agreed, so the borrower could never pass.
     */
    @Test
    void nameSimilarity_passesAnAbbreviatedBankName() {
        double threshold = ApplicationVerificationService.NAME_MATCH_THRESHOLD;
        // customer 7783414 — every token the bank holds matched; old score was 2/4 = 0.50.
        assertThat(ApplicationVerificationService.nameSimilarity(
                "FACKEER MOHAMED ABDUL JALEEL", "ABDUL JALEEL")).isGreaterThanOrEqualTo(threshold);
        // customer 43802 — an initial standing in for the first name.
        assertThat(ApplicationVerificationService.nameSimilarity(
                "ELANGO SAIPRASATH", "SAIPRASATH E")).isGreaterThanOrEqualTo(threshold);
        assertThat(ApplicationVerificationService.nameSimilarity(
                "R SHARMA", "RAHUL SHARMA")).isGreaterThanOrEqualTo(threshold);
    }

    /** …without becoming a rubber stamp: a different person must still land below the threshold. */
    @Test
    void nameSimilarity_stillRejectsADifferentPerson() {
        double threshold = ApplicationVerificationService.NAME_MATCH_THRESHOLD;
        assertThat(ApplicationVerificationService.nameSimilarity(
                "RAHUL KUMAR SHARMA", "AMIT KUMAR VERMA")).isLessThan(threshold);
        assertThat(ApplicationVerificationService.nameSimilarity(
                "RAHUL SHARMA", "RAHUL VERMA")).isLessThan(threshold);
        // A lone token proves too little for containment — it keeps the strict Jaccard measure.
        assertThat(ApplicationVerificationService.nameSimilarity(
                "JOHN", "JOHN SMITH")).isLessThan(threshold);
    }

    /**
     * A manual PENNY_DROP pass has to unblock the borrower, not just annotate the file:
     * OfferService.confirmDisbursalAccount reads the application flag, never the verification row.
     */
    @Test
    void manualPennyDropPass_marksTheCheckedAccountVerifiedAndLiftsTheLock() {
        ActorContext.set(new CurrentActor("31", "Neha Rao", "CREDIT_HEAD"));
        LoanApplication app = new LoanApplication();
        app.setId(APP);
        app.setCustomerId(43802L);
        CustomerProfile profile = new CustomerProfile();
        profile.setApplicationId(APP);
        when(applicationRepo.findById(APP)).thenReturn(Optional.of(app));
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(profile));
        when(verificationRepo.findByApplicationIdAndCheckType(APP, "PENNY_DROP"))
                .thenReturn(Optional.of(rowWithDerived("PENNY_DROP", "REVIEW",
                        "{\"accountNumber\":\"50100123454205\",\"ifsc\":\"HDFC0001875\"}")));

        service.manualDecision(APP, "PENNY_DROP", true, "Confirmed against payslip");

        assertThat(app.getDisbursalAccountVerified()).isTrue();
        assertThat(app.getDisbursalAccountNumber()).isEqualTo("50100123454205");
        assertThat(app.getDisbursalIfsc()).isEqualTo("HDFC0001875");
        assertThat(profile.getPennyDropVerified()).isTrue();
        verify(pennyDropGuard).clearLock(43802L);
    }

    /** Never bless a destination we cannot identify — the override records, but does not verify. */
    @Test
    void manualPennyDropPass_leavesTheFlagAloneWhenNoAccountIsKnown() {
        ActorContext.set(new CurrentActor("31", "Neha Rao", "CREDIT_HEAD"));
        LoanApplication app = new LoanApplication();
        app.setId(APP);
        app.setCustomerId(43802L);
        when(applicationRepo.findById(APP)).thenReturn(Optional.of(app));
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.empty());
        when(verificationRepo.findByApplicationIdAndCheckType(APP, "PENNY_DROP"))
                .thenReturn(Optional.empty());

        service.manualDecision(APP, "PENNY_DROP", true, null);

        // The column defaults to false, so "untouched" is "not true" rather than null.
        assertThat(app.getDisbursalAccountVerified()).isNotEqualTo(Boolean.TRUE);
        verify(pennyDropGuard, never()).clearLock(any());
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

    private static ApplicationVerification rowWithDerived(String type, String status, String derivedJson) {
        ApplicationVerification v = row(type, status);
        v.setDerived(derivedJson);
        return v;
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

    // ---- DigiLocker status poll --------------------------------------------------

    @Test
    void digilockerStatus_isPendingWithNoSession() {
        // Neither the popup has been opened nor did init leave a session — must not report success,
        // and must not throw DIGILOCKER_NOT_STARTED either (the old pre-fix behavior).
        CustomerProfile p = profile();
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(p));
        when(verificationRepo.findByApplicationIdAndCheckType(APP, ApplicationVerificationService.AADHAAR))
                .thenReturn(Optional.empty());

        var result = service.digilockerStatus(APP);

        assertThat(result.status()).isEqualTo("PENDING");
        verify(verification, never()).digilockerStatus(any());
    }

    @Test
    void digilockerStatus_isPassOnceAadhaarIsFinalized_evenWithoutAClientId() {
        // digilockerComplete nulls out digilockerClientId on completion — the AADHAAR check must be
        // consulted BEFORE the null-clientId branch, or a completed DigiLocker reports PENDING forever.
        CustomerProfile p = profile();
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(p));
        ApplicationVerification aadhaarRow = new ApplicationVerification();
        aadhaarRow.setApplicationId(APP);
        aadhaarRow.setCheckType(ApplicationVerificationService.AADHAAR);
        aadhaarRow.setStatus("PASS");
        when(verificationRepo.findByApplicationIdAndCheckType(APP, ApplicationVerificationService.AADHAAR))
                .thenReturn(Optional.of(aadhaarRow));

        var result = service.digilockerStatus(APP);

        assertThat(result.status()).isEqualTo("PASS");
        verify(verification, never()).digilockerStatus(any());
    }

    @Test
    void digilockerStatus_degradesToPendingWhenTheProviderThrows() {
        CustomerProfile p = profile();
        p.setDigilockerClientId("CL1");
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(p));
        when(verificationRepo.findByApplicationIdAndCheckType(APP, ApplicationVerificationService.AADHAAR))
                .thenReturn(Optional.empty());
        when(verification.digilockerStatus("CL1")).thenThrow(new RuntimeException("boom"));

        var result = service.digilockerStatus(APP);

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.derived()).containsEntry("providerError", true);
    }

    @Test
    void digilockerStatus_reportsPendingWhileTheProviderStallsAtClientInitiated() {
        // The actual reported bug: opening the popup must not read as success.
        CustomerProfile p = profile();
        p.setDigilockerClientId("CL1");
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(p));
        when(verificationRepo.findByApplicationIdAndCheckType(APP, ApplicationVerificationService.AADHAAR))
                .thenReturn(Optional.empty());
        when(verification.digilockerStatus("CL1")).thenReturn(
                new VerificationPort.DigiLockerStatus("CL1", "client_initiated", false, false, false));

        var result = service.digilockerStatus(APP);

        assertThat(result.status()).isEqualTo("PENDING");
    }

    // ---- Aadhaar e-sign ---------------------------------------------------------

    /** The stored sanction letter the signature is taken against. */
    private ApplicationDocument sanctionLetter() {
        ApplicationDocument letter = new ApplicationDocument();
        letter.setId(7L);
        letter.setApplicationId(APP);
        letter.setDocType(ApplicationVerificationService.SANCTION_LETTER);
        letter.setS3ObjectKey("apps/42/sanction-letter.pdf");
        letter.setSizeBytes(2048L);
        return letter;
    }

    private void givenSanctionLetter() {
        when(documentRepo.findFirstByApplicationIdAndDocTypeOrderByIdDesc(
                APP, ApplicationVerificationService.SANCTION_LETTER))
                .thenReturn(Optional.of(sanctionLetter()));
        when(storage.presignDownload("apps/42/sanction-letter.pdf")).thenReturn("https://s3.test/kfs.pdf");
    }

    /** An AADHAAR row as digilockerComplete writes it — the only source of gender / YOB / last-4. */
    private void givenAadhaarOnFile() {
        ApplicationVerification aadhaar = new ApplicationVerification();
        aadhaar.setApplicationId(APP);
        aadhaar.setCheckType(ApplicationVerificationService.AADHAAR);
        aadhaar.setStatus(ApplicationVerificationService.PASS);
        aadhaar.setDerived("""
                {"fullName":"SHUBHAM","dob":"2003-03-24","gender":"M","maskedAadhaar":"XXXXXXXX1234"}
                """);
        when(verificationRepo.findByApplicationIdAndCheckType(
                APP, ApplicationVerificationService.AADHAAR)).thenReturn(Optional.of(aadhaar));
    }

    @Test
    void esignInit_sendsAadhaarDemographicsWhenDigilockerRan() {
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(profile()));
        givenSanctionLetter();
        givenAadhaarOnFile();
        when(esign.initiate(any())).thenReturn(
                new EsignPort.EsignSession("C-1|S-1", "https://signzy.test/esign/S-1", "SIGNZY"));

        var result = service.esignInit(APP, "https://app.test/ok", "https://app.test/no");

        ArgumentCaptor<EsignPort.EsignRequest> sent = ArgumentCaptor.forClass(EsignPort.EsignRequest.class);
        verify(esign).initiate(sent.capture());
        assertThat(sent.getValue().signer().gender()).isEqualTo("M");
        assertThat(sent.getValue().signer().yearOfBirth()).isEqualTo("2003");
        // Only the last four survive our storage — the raw UID is never persisted.
        assertThat(sent.getValue().signer().uidLastFourDigits()).isEqualTo("1234");
        assertThat(sent.getValue().documentUrl()).isEqualTo("https://s3.test/kfs.pdf");
        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.derived())
                .containsEntry("url", "https://signzy.test/esign/S-1")
                .containsEntry("matchMode", "STRICT");
    }

    @Test
    void esignInit_degradesToNameOnlyWhenAadhaarMissing() {
        // DigiLocker is deliberately non-blocking, so a borrower can reach the signature without it.
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(profile()));
        givenSanctionLetter();
        when(esign.initiate(any())).thenReturn(
                new EsignPort.EsignSession("C-2|S-2", "https://signzy.test/esign/S-2", "SIGNZY"));

        var result = service.esignInit(APP, "https://app.test/ok", "https://app.test/no");

        ArgumentCaptor<EsignPort.EsignRequest> sent = ArgumentCaptor.forClass(EsignPort.EsignRequest.class);
        verify(esign).initiate(sent.capture());
        assertThat(sent.getValue().signer().gender()).isNull();
        assertThat(sent.getValue().signer().yearOfBirth()).isNull();
        assertThat(result.derived()).containsEntry("matchMode", "NAME_ONLY");
    }

    @Test
    @ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void esignInit_fallsBackToDrawnSignatureWhenProviderIsDown(
            org.springframework.boot.test.system.CapturedOutput output) {
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(profile()));
        givenSanctionLetter();
        when(esign.initiate(any())).thenThrow(new IllegalStateException("provider down"));

        var result = service.esignInit(APP, "https://app.test/ok", "https://app.test/no");

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.derived())
                .containsEntry("fallback", true)
                .containsEntry("providerErrorCode", "UNEXPECTED_PROVIDER_FAILURE");
        assertThat(output).contains("eSign initiate failed application=42");
        // No row: the drawn-signature path owns the ESIGN row if the borrower takes it.
        verify(verificationRepo, never()).save(any());
    }

    @Test
    @ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void esignInit_surfacesTheProviderHttpStatusSoAMisconfigurationIsNotSilent(
            org.springframework.boot.test.system.CapturedOutput output) {
        // A blank NAVIX_ESIGN_CALLBACK_URL ships "callbackUrl":"" and Signzy 400s; a missing
        // SIGNZY_PROD_TOKEN 401s. Both used to look identical (a bare drawn-signature fallback).
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(profile()));
        givenSanctionLetter();
        when(esign.initiate(any())).thenThrow(
                new IllegalStateException("HTTP 400 from /api/v3/contract/initiate"));

        var result = service.esignInit(APP, "https://app.test/ok", "https://app.test/no");

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.derived()).containsEntry("providerErrorCode", "HTTP_400");
        assertThat(output).contains("eSign initiate failed application=42")
                .contains("errorCode=HTTP_400")
                .contains("/api/v3/contract/initiate");
        // The signer's identity and the presigned KFS URL must never reach the log.
        assertThat(output).doesNotContain("SHUBHAM").doesNotContain("s3.test/kfs.pdf");
    }

    @Test
    void esignInit_requiresTheSanctionLetterFirst() {
        when(documentRepo.findFirstByApplicationIdAndDocTypeOrderByIdDesc(
                APP, ApplicationVerificationService.SANCTION_LETTER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.esignInit(APP, "https://app.test/ok", "https://app.test/no"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("sanction letter");
    }

    @Test
    void esignStatus_staysPendingWhileTheBorrowerIsStillSigning() {
        ApplicationVerification row = pendingEsignRow();
        when(verificationRepo.findByApplicationIdAndCheckType(APP, ApplicationVerificationService.ESIGN))
                .thenReturn(Optional.of(row));
        when(esign.fetch("C-1|S-1")).thenReturn(
                new EsignPort.EsignResult(false, false, null, null, "C-1", "SIGNZY", null));

        var result = service.esignStatus(APP);

        assertThat(result.status()).isEqualTo("PENDING");
        verify(storage, never()).store(anyString(), any(), anyString());
    }

    @Test
    void esignStatus_storesTheProvidersSignedCopyOnSuccess() {
        ApplicationVerification row = pendingEsignRow();
        when(verificationRepo.findByApplicationIdAndCheckType(APP, ApplicationVerificationService.ESIGN))
                .thenReturn(Optional.of(row));
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(profile()));
        when(documentRepo.findFirstByApplicationIdAndDocTypeOrderByIdDesc(
                APP, ApplicationVerificationService.SANCTION_LETTER))
                .thenReturn(Optional.of(sanctionLetter()));
        when(documentRepo.findFirstByApplicationIdAndDocTypeOrderByIdDesc(
                APP, ApplicationVerificationService.SIGNED_AGREEMENT)).thenReturn(Optional.empty());
        when(documentRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        byte[] signedPdf = "%PDF-signed".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(esign.fetch("C-1|S-1")).thenReturn(new EsignPort.EsignResult(
                true, true, java.time.Instant.parse("2026-08-11T12:30:00Z"), signedPdf,
                "C-1", "SIGNZY", null));

        var result = service.esignStatus(APP);

        assertThat(result.status()).isEqualTo("PASS");
        // eMudhra's copy carries the digital signature certificate, so it replaces the rendered letter.
        verify(storage).store("applications/42/signed_agreement/sanction-letter-signed.pdf",
                signedPdf, "application/pdf");
    }

    @Test
    void esignStatus_reviewsWhenTheContractCompletedWithoutASignature() {
        // The usual cause is the Aadhaar name not matching ours. A human decides; the borrower can
        // still fall back to drawing.
        ApplicationVerification row = pendingEsignRow();
        when(verificationRepo.findByApplicationIdAndCheckType(APP, ApplicationVerificationService.ESIGN))
                .thenReturn(Optional.of(row));
        when(esign.fetch("C-1|S-1")).thenReturn(new EsignPort.EsignResult(
                true, false, null, null, "C-1", "SIGNZY", "Name match: 0.42"));

        var result = service.esignStatus(APP);

        assertThat(result.status()).isEqualTo("REVIEW");
        assertThat(result.message()).isEqualTo("Name match: 0.42");
    }

    @Test
    void esignStatus_mintsAFreshContractWhenTheOldOneLapsed() {
        // A sanction never expires but the provider's contract does, so a borrower returning days later
        // must get a new one rather than an error.
        ApplicationVerification row = pendingEsignRow();
        // Read twice before the re-mint (the terminal-state guard, then the row itself), and gone
        // afterwards — esignInit must not find a row it would short-circuit on.
        when(verificationRepo.findByApplicationIdAndCheckType(APP, ApplicationVerificationService.ESIGN))
                .thenReturn(Optional.of(row), Optional.of(row), Optional.empty());
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(profile()));
        givenSanctionLetter();
        when(esign.fetch("C-1|S-1")).thenReturn(new EsignPort.EsignResult(
                false, false, null, null, "C-1", "SIGNZY", EsignPort.CONTRACT_EXPIRED));
        when(esign.initiate(any())).thenReturn(
                new EsignPort.EsignSession("C-9|S-9", "https://signzy.test/esign/S-9", "SIGNZY"));

        var result = service.esignStatus(APP);

        verify(verificationRepo).delete(row);
        verify(esign).initiate(any());
        assertThat(result.derived()).containsEntry("url", "https://signzy.test/esign/S-9");
    }

    @Test
    void esignStatus_refusesBeforeSigningHasStarted() {
        when(verificationRepo.findByApplicationIdAndCheckType(APP, ApplicationVerificationService.ESIGN))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.esignStatus(APP))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Start signing");
    }

    private ApplicationVerification pendingEsignRow() {
        ApplicationVerification row = new ApplicationVerification();
        row.setApplicationId(APP);
        row.setCheckType(ApplicationVerificationService.ESIGN);
        row.setStatus(ApplicationVerificationService.PENDING);
        row.setProvider("SIGNZY");
        row.setProviderTxnId("C-1|S-1");
        row.setDerived("{\"sessionId\":\"C-1|S-1\",\"matchMode\":\"STRICT\"}");
        return row;
    }
}
