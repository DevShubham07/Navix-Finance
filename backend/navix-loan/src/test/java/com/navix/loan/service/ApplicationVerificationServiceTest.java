package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    @Mock private DocumentStoragePort storage;
    @Mock private RiskPort risk;
    @Mock private CreditBriefService creditBriefService;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;

    private ApplicationVerificationService service;

    private static final Long APP = 42L;

    @BeforeEach
    void setUp() {
        service = new ApplicationVerificationService(verificationRepo, profileRepo, applicationRepo,
                documentRepo, verification, storage, risk, new ObjectMapper(), creditBriefService,
                eventPublisher);
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
                .thenReturn(new VerificationPort.PanCheck("TXN1", true, "SHUBHAM", "2003-03-24", "M",
                        true, "65XXXXXXXX90", "QVEPS0901K", "Haryana", "131001"));

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
                .thenReturn(new VerificationPort.PanCheck("TXN1", true, "SHUBHAM", "15/08/1992", "M",
                        true, "65XXXXXXXX90", "QVEPS0901K", "Haryana", "131001"));

        service.verifyPan(APP, "QVEPS0901K");

        assertThat(p.getDob()).isEqualTo(LocalDate.of(1992, 8, 15));
    }

    @Test
    void panVerify_doesNotOverwriteAnExistingDob() {
        CustomerProfile p = profile();
        p.setDob(LocalDate.of(1990, 1, 1)); // already on file (e.g. earlier DigiLocker / borrower-entered)
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(p));
        when(verification.verifyPan(anyString(), anyString()))
                .thenReturn(new VerificationPort.PanCheck("TXN1", true, "SHUBHAM", "2003-03-24", "M",
                        true, "65XXXXXXXX90", "QVEPS0901K", "Haryana", "131001"));

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
                .thenReturn(new VerificationPort.EmailCheck("TXN2", true, false, true, true, null));

        var result = service.verifyEmail(APP, "someone@gmail.com");

        assertThat(result.status()).isEqualTo("REVIEW");
    }

    @Test
    void pennyDrop_nameMismatch_isReview_match_isPass() {
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(profile()));

        when(verification.pennyDrop(anyString(), anyString(), anyString()))
                .thenReturn(new VerificationPort.PennyDropCheck("TXN3", true, "RAVI KUMAR", "HDFC Bank", "HDFC0002557"));
        assertThat(service.verifyPennyDrop(APP, "123", "HDFC0002557").status()).isEqualTo("REVIEW");

        when(verification.pennyDrop(anyString(), anyString(), anyString()))
                .thenReturn(new VerificationPort.PennyDropCheck("TXN4", true, "SHUBHAM", "HDFC Bank", "HDFC0002557"));
        assertThat(service.verifyPennyDrop(APP, "123", "HDFC0002557").status()).isEqualTo("PASS");
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
    void salary_setsEligibleLimitOnApplication() {
        CustomerProfile p = profile();
        LoanApplication app = new LoanApplication();
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(p));
        when(applicationRepo.findById(APP)).thenReturn(Optional.of(app));
        when(risk.eligibleLimitPaise(4_000_000L)).thenReturn(1_000_000L);

        var result = service.verifySalary(APP, 4_000_000L, List.of("applications/42/salary_slip/1.pdf"));

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
    void allRequiredPassed_falseWhenIncomplete_trueWhenAllPassAndAgreed() {
        // incomplete: no rows
        assertThat(service.allRequiredPassed(APP)).isFalse();

        // all required PASS/REVIEW
        when(verificationRepo.findByApplicationIdOrderByIdAsc(APP)).thenReturn(List.of(
                row("PAN", "PASS"), row("EMAIL", "REVIEW"), row("ADDRESS", "PASS"), row("AADHAAR", "PASS"),
                row("BUREAU", "PASS"), row("SALARY", "PASS"), row("PENNY_DROP", "PASS"), row("SELFIE", "REVIEW")));
        CustomerProfile agreed = profile();
        agreed.setAgreementAccepted(true);
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(agreed));

        assertThat(service.allRequiredPassed(APP)).isTrue();
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

    private static ApplicationVerification row(String type, String status) {
        ApplicationVerification v = new ApplicationVerification();
        v.setApplicationId(APP);
        v.setCheckType(type);
        v.setStatus(status);
        v.setMessage(type + " " + status);
        return v;
    }

    private VerificationPort.AadhaarResult aadhaar(String masked) {
        return new VerificationPort.AadhaarResult("TXN-DL", "SHUBHAM", "2003-03-24", "M", masked,
                "addr", "Haryana", "131001", null, null);
    }

    @Test
    void digilockerComplete_marksAadhaarVerified_andSetsDob() {
        CustomerProfile p = profile();
        p.setDigilockerClientId("CL1"); // session started
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(p));
        when(verification.digilockerAadhaar("CL1")).thenReturn(aadhaar("XXXXXXXX1234"));
        // Skip the S3 ingest cleanly: the doc-list lookup throws and is swallowed by the ingest try/catch.
        when(verification.digilockerList("CL1")).thenThrow(new RuntimeException("no docs in test"));

        var result = service.digilockerComplete(APP);

        assertThat(result.status()).isEqualTo("PASS");
        assertThat(p.getAadhaarVerified()).isTrue();             // the DigiLocker-verified flag (no raw number stored)
        assertThat(p.getDob()).isEqualTo(LocalDate.of(2003, 3, 24));
    }
}
