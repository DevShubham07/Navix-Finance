package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.exception.BusinessException;
import com.navix.common.risk.RiskPort;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.storage.DocumentStoragePort;
import com.navix.common.verification.VerificationPort;
import com.navix.loan.entity.ApplicationVerification;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.repository.ApplicationDocumentRepository;
import com.navix.loan.repository.ApplicationVerificationRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    private void parked(String derivedJson) {
        ApplicationVerification row = new ApplicationVerification();
        row.setApplicationId(APP);
        row.setCheckType("BUREAU");
        row.setStatus("REVIEW");
        row.setDerived(derivedJson);
        lenient().when(verificationRepo.findByApplicationIdAndCheckType(APP, "BUREAU"))
                .thenReturn(Optional.of(row));
    }

    private static String derived(String extra) {
        return "{\"bureauChallenge\":true,\"bureauChallengeQuestion\":\"Which lender?\","
                + "\"bureauChallengeOptions\":[\"" + PADDED_OPTION + "\",\" Galada Finance Ltd \"],"
                + "\"bureauChallengeOrderId\":\"txn-prod-1\"" + extra + "}";
    }

    private void profileExists() {
        CustomerProfile p = new CustomerProfile();
        p.setApplicationId(APP);
        p.setFullName("Sample Person");
        p.setMobile("9000000001");
        lenient().when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(p));
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
}
