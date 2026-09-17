package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.navix.common.verification.ProviderFailureDetails;
import com.navix.common.verification.VerificationPort;
import com.navix.loan.entity.ApplicationVerification;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.repository.ApplicationDocumentRepository;
import com.navix.loan.repository.ApplicationVerificationRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The guards around answering a bureau KBA challenge.
 *
 * <p>These are billing tests as much as correctness tests: every provider call here is live and
 * billable with no sandbox, and this is the one provider path a BORROWER can trigger. Each case below
 * asserts that a bad request is turned away BEFORE {@link VerificationPort} is touched.
 */
@ExtendWith(MockitoExtension.class)
class BureauChallengeAnswerTest {

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
    @Mock private com.navix.common.featureflag.FeatureFlagService featureFlags;
    @Mock private com.navix.loan.repository.CustomerLimitOverrideRepository limitOverrideRepository;

    private ApplicationVerificationService service;

    private static final Long APP = 42L;
    /** Note the padding — CRIF sends its options this way and compares them literally. */
    private static final String PADDED_OPTION = " PAYU FINANCE INDIA PRIVATE LIMITED ";

    @BeforeEach
    void setUp() {
        service = new ApplicationVerificationService(verificationRepo, profileRepo, applicationRepo,
                documentRepo, verification, esign, otpVerifier, emailOtp, storage, risk,
                new EligibilityService(applicationRepo, limitOverrideRepository, risk), new ObjectMapper(),
                creditBriefService, eventPublisher, changeLogger, flow, pennyDropGuard, featureFlags);
        lenient().when(verificationRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(profileRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        ActorContext.set(new CurrentActor("7", "Borrower", "BORROWER"));
    }

    @AfterEach
    void tearDown() {
        ActorContext.clear();
    }

    private ApplicationVerification parked(String derivedJson) {
        ApplicationVerification row = new ApplicationVerification();
        row.setApplicationId(APP);
        row.setCheckType("BUREAU");
        row.setStatus("REVIEW");
        row.setDerived(derivedJson);
        lenient().when(verificationRepo.findByApplicationIdAndCheckType(APP, "BUREAU"))
                .thenReturn(Optional.of(row));
        return row;
    }

    private static String derived(String extra) {
        return "{\"bureauChallenge\":true,\"bureauChallengeQuestion\":\"Which lender?\","
                + "\"bureauChallengeOptions\":[\"" + PADDED_OPTION + "\",\" Galada Finance Ltd \"],"
                + "\"bureauChallengeOrderId\":\"txn-prod-1\"" + extra + "}";
    }

    private CustomerProfile profileExists() {
        CustomerProfile p = new CustomerProfile();
        p.setApplicationId(APP);
        p.setFullName("Sample Person");
        p.setMobile("9000000001");
        lenient().when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(p));
        return p;
    }

    /**
     * A stand-in for {@code VerificationException}, which lives in navix-verification and is invisible
     * from this module — the exact reason {@link ProviderFailureDetails} exists. Only
     * {@code providerCode()} matters here: it is what tells a closed question (CRIF {@code S02}) apart
     * from a wrong answer, and the two must never be recorded the same way.
     */
    private static RuntimeException providerFailure(Integer httpStatus, String providerCode) {
        class Failure extends RuntimeException implements ProviderFailureDetails {
            @Override public Integer httpStatus() {
                return httpStatus;
            }

            @Override public String endpoint() {
                return "/crif_combine";
            }

            @Override public String providerCode() {
                return providerCode;
            }

            @Override public String safeDetail() {
                return "kba closed";
            }
        }
        return new Failure();
    }

    /**
     * The 39 rows parked in production before the answer flow shipped hold an orderId and NO reportId.
     * They are unanswerable, and must be turned away here rather than spending a call to find out.
     */
    @Test
    void rowWithoutAReportIdIsRejectedWithoutCallingTheProvider() {
        parked(derived(""));

        assertThatThrownBy(() -> service.answerBureauChallenge(APP, PADDED_OPTION))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("expired");

        verify(verification, never())
                .answerBureauChallenge(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    /** An answer that is not one of the offered options can only be wrong — don't pay to learn that. */
    @Test
    void answerOutsideTheOfferedOptionsIsRejectedWithoutCallingTheProvider() {
        parked(derived(",\"bureauChallengeReportId\":\"CCR-1\""));

        assertThatThrownBy(() -> service.answerBureauChallenge(APP, "SOME OTHER BANK"))
                .isInstanceOf(BusinessException.class);

        verify(verification, never())
                .answerBureauChallenge(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    /**
     * The padding regression, at the service boundary: a trimmed option is NOT the stored option, so it
     * is refused rather than being forwarded and silently failing at the bureau.
     */
    @Test
    void trimmedAnswerIsNotAcceptedAsTheStoredOption() {
        parked(derived(",\"bureauChallengeReportId\":\"CCR-1\""));

        assertThatThrownBy(() -> service.answerBureauChallenge(APP, PADDED_OPTION.trim()))
                .isInstanceOf(BusinessException.class);

        verify(verification, never())
                .answerBureauChallenge(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    /** Attempts are capped so a determined borrower cannot spend without limit. */
    @Test
    void attemptBudgetIsEnforcedWithoutCallingTheProvider() {
        parked(derived(",\"bureauChallengeReportId\":\"CCR-1\",\"bureauChallengeAttempts\":3"));

        var result = service.answerBureauChallenge(APP, PADDED_OPTION);

        assertThat(result.status()).isEqualTo("REVIEW");
        verify(verification, never())
                .answerBureauChallenge(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    /** The happy path forwards the answer VERBATIM and finishes through the normal scored-pull path. */
    @Test
    void validAnswerIsForwardedVerbatimAndScores() {
        parked(derived(",\"bureauChallengeReportId\":\"CCR-1\""));
        profileExists();
        when(verification.answerBureauChallenge(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString()))
                .thenReturn(new VerificationPort.BureauCheck("CCR-1", "FINTRIX_CRIF", 510, false,
                        1, 0, 0.0, null, "{\"data\":{\"HEADER\":{}}}", null, null));

        var result = service.answerBureauChallenge(APP, PADDED_OPTION);

        assertThat(result.status()).isEqualTo("PASS");
        // The exact padded string reaches the port — no trimming anywhere in between.
        verify(verification).answerBureauChallenge("txn-prod-1", "CCR-1", PADDED_OPTION,
                "Sample Person", "9000000001", "navix-42-BUREAU");
    }

    /** Skipping is free, keeps the file in REVIEW, and leaves a flag the credit team can see. */
    @Test
    void skipRecordsAFlagAndNeverCallsTheProvider() {
        parked(derived(",\"bureauChallengeReportId\":\"CCR-1\""));

        var result = service.skipBureauChallenge(APP);

        assertThat(result.status()).isEqualTo("REVIEW");
        assertThat(result.derived()).containsEntry("bureauChallengeSkipped", true);
        // The question itself survives the skip, so staff can still see what was asked.
        assertThat(result.derived()).containsKey("bureauChallengeQuestion");
        verify(verification, never())
                .answerBureauChallenge(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    /**
     * Applications 9741 and 9474 walked S11 → S11 → S02 in twelve seconds: the follow-up envelope was
     * mis-parsed, so the old question stayed open, the borrower kept answering it, and after three
     * billed attempts CRIF closed the order and the report was lost. {@code S02} is the bureau saying
     * the question is spent — a local attempt counter cannot see that, so the verdict has to be
     * persisted as its own flag with the budget burnt down to the cap.
     */
    @Test
    void exhaustedAnswerPersistsTheClosedFlagAndStopsFurtherCalls() {
        parked(derived(",\"bureauChallengeReportId\":\"CCR-1\""));
        profileExists();
        when(verification.answerBureauChallenge(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString()))
                .thenThrow(providerFailure(200, ProviderFailureDetails.KBA_EXHAUSTED));

        var result = service.answerBureauChallenge(APP, PADDED_OPTION);

        assertThat(result.status()).isEqualTo("REVIEW");
        assertThat(result.derived()).containsEntry("bureauChallengeExhausted", true)
                // Burnt to MAX_CHALLENGE_ATTEMPTS rather than incremented: a local counter that still
                // had room would invite another billable call CRIF has already refused to honour.
                .containsEntry("bureauChallengeAttempts", 3);
        ArgumentCaptor<ApplicationVerification> saved = ArgumentCaptor.forClass(ApplicationVerification.class);
        verify(verificationRepo).save(saved.capture());
        assertThat(saved.getValue().getDerived()).contains("\"bureauChallengeExhausted\":true");
    }

    /**
     * The flag above only earns its keep if the next answer is turned away for free. Every call to
     * CRIF is billable with no sandbox, and this is the one provider path a BORROWER can trigger — a
     * closed question with a live-looking options list on screen is precisely the shape that invites
     * repeated clicking.
     */
    @Test
    void aClosedChallengeIsNotSentToTheProviderAgain() {
        parked(derived(",\"bureauChallengeReportId\":\"CCR-1\",\"bureauChallengeExhausted\":true"));

        var result = service.answerBureauChallenge(APP, PADDED_OPTION);

        assertThat(result.status()).isEqualTo("REVIEW");
        verifyNoInteractions(verification);
    }

    /**
     * An {@code orderId} only means something to the vendor that issued it, and
     * {@code VerificationFailureService} reads the provider off the row to decide whether a real
     * report was thrown away. The answer and skip paths used to write a hardcoded
     * {@code FINTRIX_CRIF}, so one rejected answer relabelled a Digitap-issued challenge as Fintrix's
     * — and the label has to stay true for either of those readers to be right.
     */
    @Test
    void answerFailurePreservesTheStoredIssuer() {
        ApplicationVerification row = parked(derived(",\"bureauChallengeReportId\":\"CCR-1\""));
        row.setProvider("DIGITAP_EXPERIAN");
        profileExists();
        when(verification.answerBureauChallenge(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString()))
                .thenThrow(providerFailure(200, "S11"));

        service.answerBureauChallenge(APP, PADDED_OPTION);

        ArgumentCaptor<ApplicationVerification> saved = ArgumentCaptor.forClass(ApplicationVerification.class);
        verify(verificationRepo).save(saved.capture());
        assertThat(saved.getValue().getProvider()).isEqualTo("DIGITAP_EXPERIAN");
    }

    /**
     * Exhaustion closes one CRIF order, not the borrower's file. A re-mint is a NEW order with a new
     * question, so the flag must not ride along into it — carrying it forward would leave the fresh
     * question permanently unanswerable and the only route to the report would be a manual credit
     * decision. The attempt counter, by contrast, is deliberately carried so a borrower cannot reset
     * their own budget by asking for another question.
     */
    @Test
    void remintAfterExhaustionIsStillAllowedOutsideTheCooldown() {
        parked(derived(",\"bureauChallengeReportId\":\"CCR-1\",\"bureauChallengeExhausted\":true,"
                + "\"bureauChallengeAttempts\":3"));
        CustomerProfile p = profileExists();
        p.setDob(LocalDate.of(1992, 8, 15));
        ApplicationVerification consent = new ApplicationVerification();
        consent.setApplicationId(APP);
        consent.setCheckType("BUREAU_CONSENT");
        consent.setStatus("PASS");
        when(verificationRepo.findByApplicationIdAndCheckType(APP, "BUREAU_CONSENT"))
                .thenReturn(Optional.of(consent));
        when(verification.pullBureau(any(), any(), any(), any(), any(), any()))
                .thenReturn(new VerificationPort.BureauCheck("CCR-2", "FINTRIX_CRIF", null, false,
                        null, null, null, null, null, null,
                        new VerificationPort.PendingChallenge("Which lender?",
                                List.of(PADDED_OPTION), "txn-prod-2", "CCR-2")));

        var result = service.refreshBureauChallenge(APP);

        assertThat(result.status()).isEqualTo("REVIEW");
        assertThat(result.derived()).containsEntry("bureauChallenge", true)
                .containsEntry("bureauChallengeOrderId", "txn-prod-2");
        assertThat(result.derived()).doesNotContainKey("bureauChallengeExhausted");
        // The spent budget still follows the borrower across the re-mint.
        assertThat(result.derived()).containsEntry("bureauChallengeAttempts", 3);
    }
}
