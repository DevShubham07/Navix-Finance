package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.dto.CaseFailureReason;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicationVerificationRepository;
import com.navix.loan.repository.ApplicationVerificationRepository.CaseFailureRow;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The precedence walk in {@link VerificationFailureService}.
 *
 * <p>Every row of the September 2026 pending-queue classification is pinned here, because the whole
 * point of this service is that "a real report is one security question away", "we never had a name
 * to send" and "this person genuinely has no credit file" stop rendering identically. A reason that
 * silently changes is a credit officer being told the wrong thing about a real borrower.
 *
 * <p>Declaration order in {@link CaseFailureReason} IS the precedence, so the ordering tests at the
 * bottom are load-bearing, not decoration.
 */
@ExtendWith(MockitoExtension.class)
class VerificationFailureServiceTest {

    private static final Long APP = 5455L;

    @Mock private ApplicationVerificationRepository verificationRepo;
    @Mock private CustomerProfileRepository profileRepo;
    @Mock private LoanApplicationRepository applicationRepo;

    private VerificationFailureService service;

    @BeforeEach
    void setUp() {
        service = new VerificationFailureService(verificationRepo, profileRepo, applicationRepo,
                new ObjectMapper());
        // Most cases turn on the verification rows alone; the two tests that care about the profile
        // or the application override these.
        lenient().when(profileRepo.findByApplicationIdIn(anyCollection())).thenReturn(List.of());
        lenient().when(applicationRepo.findAllById(any())).thenReturn(List.of());
    }

    // ---------------------------------------------------------------- helpers

    /**
     * A stub of the repository's interface projection. Written by hand rather than mocked so each
     * test reads as the row it is describing.
     */
    private static CaseFailureRow row(String checkType, String status, String derived,
                                      Long score, String provider, String providerTxnId) {
        return new CaseFailureRow() {
            @Override public Long getApplicationId() {
                return APP;
            }

            @Override public String getCheckType() {
                return checkType;
            }

            @Override public String getStatus() {
                return status;
            }

            @Override public String getDerived() {
                return derived;
            }

            @Override public String getMessage() {
                return null;
            }

            @Override public Long getScore() {
                return score;
            }

            @Override public String getProvider() {
                return provider;
            }

            @Override public String getProviderTxnId() {
                return providerTxnId;
            }
        };
    }

    private static CaseFailureRow bureau(String status, String derived) {
        return row("BUREAU", status, derived, null, "FINTRIX_CRIF", null);
    }

    private static CaseFailureRow consentGiven() {
        return row("BUREAU_CONSENT", "PASS", null, null, "DhanBoost", null);
    }

    private CaseFailureReason reasonFor(CaseFailureRow... rows) {
        when(verificationRepo.findByApplicationIdInAndCheckTypeIn(anyCollection(), anyCollection()))
                .thenReturn(List.of(rows));
        return service.failure(APP).reason();
    }

    private static CustomerProfile profileNamed(String fullName) {
        CustomerProfile p = new CustomerProfile();
        p.setApplicationId(APP);
        p.setFullName(fullName);
        return p;
    }

    // ---------------------------------------------------------------- REVIEW rows

    @Test
    void identityMismatchOutranksEverythingElse() {
        assertThat(reasonFor(consentGiven(),
                bureau("REVIEW", "{\"identityMismatch\":\"PAN differs\",\"providerError\":true,"
                        + "\"providerErrorCode\":\"HTTP_503\"}")))
                .isEqualTo(CaseFailureReason.BUREAU_IDENTITY_MISMATCH);
    }

    @Test
    void missingNameIsReportedAsSomethingAnAdminCanType() {
        assertThat(reasonFor(consentGiven(), bureau("REVIEW", "{\"missingProfileField\":\"name\"}")))
                .isEqualTo(CaseFailureReason.BUREAU_MISSING_NAME);
    }

    @Test
    void missingDobIsItsOwnReason() {
        assertThat(reasonFor(consentGiven(), bureau("REVIEW", "{\"missingProfileField\":\"dob\"}")))
                .isEqualTo(CaseFailureReason.BUREAU_MISSING_DOB);
    }

    @Test
    void anUnansweredSecurityQuestionIsNotAFailedPull() {
        assertThat(reasonFor(consentGiven(), bureau("REVIEW", "{\"bureauChallenge\":true}")))
                .isEqualTo(CaseFailureReason.BUREAU_KBA_PENDING);
    }

    @Test
    void aSkippedSecurityQuestionIsDistinctFromAnUnaskedOne() {
        assertThat(reasonFor(consentGiven(),
                bureau("REVIEW", "{\"bureauChallenge\":true,\"bureauChallengeSkipped\":true}")))
                .isEqualTo(CaseFailureReason.BUREAU_KBA_SKIPPED);
    }

    @Test
    void maskedMobileFollowUpIsRecognised() {
        assertThat(reasonFor(consentGiven(),
                bureau("REVIEW", "{\"providerError\":true,\"bureauMaskedMobileRequired\":true,"
                        + "\"providerErrorCode\":\"MASKED_MOBILE_REQUIRED\"}")))
                .isEqualTo(CaseFailureReason.BUREAU_MASKED_MOBILE_FOLLOW_UP);
    }

    @Test
    void providerErrorCodesMapToWhatAnOfficerCanActOn() {
        assertThat(reasonFor(consentGiven(),
                bureau("REVIEW", "{\"providerError\":true,\"providerErrorCode\":\"HTTP_402\"}")))
                .isEqualTo(CaseFailureReason.BUREAU_PROVIDER_NO_BALANCE);
        assertThat(reasonFor(consentGiven(),
                bureau("REVIEW", "{\"providerError\":true,\"providerErrorCode\":\"HTTP_400\"}")))
                .isEqualTo(CaseFailureReason.BUREAU_PROVIDER_REJECTED_REQUEST);
        assertThat(reasonFor(consentGiven(),
                bureau("REVIEW", "{\"providerError\":true,\"providerErrorCode\":\"HTTP_422\"}")))
                .isEqualTo(CaseFailureReason.BUREAU_PROVIDER_PLAN_LIMIT);
        assertThat(reasonFor(consentGiven(),
                bureau("REVIEW", "{\"providerError\":true,\"providerErrorCode\":\"TRANSPORT_FAILURE\"}")))
                .isEqualTo(CaseFailureReason.BUREAU_PROVIDER_UNAVAILABLE);
    }

    // ---------------------------------------------------------------- PASS rows

    /**
     * The 47. A Fintrix no-hit that still carries a transaction id held a real report: that id is the
     * report's own HEADER.REPORT-ID, and a genuine thin file has no report node to read one from.
     */
    @Test
    void aFintrixNoHitCarryingAReportIdIsADiscardedReport() {
        assertThat(reasonFor(consentGiven(),
                row("BUREAU", "PASS", "{\"noRecord\":true}", null, "FINTRIX_CRIF", "CCR260822CR41")))
                .isEqualTo(CaseFailureReason.BUREAU_REPORT_DISCARDED);
    }

    /**
     * Digitap's Experian client takes its transaction id from the envelope's {@code request_id}, which
     * is present on EVERY response including a legitimate no-record. Without the provider gate every
     * Digitap thin file would be labelled a discarded report and offered a billable re-run that could
     * only ever come back empty.
     */
    @Test
    void aDigitapNoRecordIsNotMistakenForADiscardedReport() {
        // Named, so the missing-name rule above cannot claim this row first — the point here is the
        // provider gate, not the name.
        when(profileRepo.findByApplicationIdIn(anyCollection()))
                .thenReturn(List.of(profileNamed("Sample Person")));
        assertThat(reasonFor(consentGiven(),
                row("BUREAU", "PASS", "{\"noRecord\":true}", null, "DIGITAP_EXPERIAN", "req-9931")))
                .isEqualTo(CaseFailureReason.BUREAU_NO_RECORD);
    }

    /**
     * The 44. A bureau request built without a name cannot match anyone, the provider rejects it, and
     * the row was recorded as a thin file. The blank name on the profile is the durable evidence —
     * and it is what an admin fixes. Note the copy says "name missing", never "PAN failed": a PAN that
     * PASSES can still return no name.
     */
    @Test
    void aNamelessProfileExplainsAnApparentThinFile() {
        when(profileRepo.findByApplicationIdIn(anyCollection())).thenReturn(List.of(profileNamed(" ")));
        assertThat(reasonFor(consentGiven(),
                row("BUREAU", "PASS", "{\"noRecord\":true}", null, "FINTRIX_CRIF", null)))
                .isEqualTo(CaseFailureReason.BUREAU_MISSING_NAME);
    }

    /** The 84. The system working correctly — this must NOT read as a failure. */
    @Test
    void aGenuineThinFileIsReportedAsSuchAndIsNotRetryable() {
        when(profileRepo.findByApplicationIdIn(anyCollection()))
                .thenReturn(List.of(profileNamed("Sample Person")));
        CaseFailureReason reason = reasonFor(consentGiven(),
                row("BUREAU", "PASS", "{\"noRecord\":true}", null, "FINTRIX_CRIF", null));
        assertThat(reason).isEqualTo(CaseFailureReason.BUREAU_NO_RECORD);
        assertThat(reason.retryable()).isFalse();
    }

    @Test
    void aKeptReportWithNoUsableScoreIsInformationalNotAFailure() {
        assertThat(reasonFor(consentGiven(),
                row("BUREAU", "PASS", "{\"noRecord\":false}", null, "FINTRIX_CRIF", "CCR-1")))
                .isEqualTo(CaseFailureReason.BUREAU_NO_SCORE);
    }

    @Test
    void aScoredPullHasNothingOutstanding() {
        assertThat(reasonFor(consentGiven(),
                row("BUREAU", "PASS", "{\"noRecord\":false}", 742L, "FINTRIX_CRIF", "CCR-2")))
                .isEqualTo(CaseFailureReason.NONE);
    }

    // ---------------------------------------------------------------- PAN, consent, assignment

    @Test
    void aPanNoProviderRecognisesIsNotASystemFault() {
        CaseFailureReason reason = reasonFor(consentGiven(), row("PAN", "FAIL", null, null, "SIGNZY", null));
        assertThat(reason).isEqualTo(CaseFailureReason.PAN_INVALID);
        assertThat(reason.retryable()).isFalse();
    }

    @Test
    void aPanTheProvidersCouldNotAnswerIsWorthRetrying() {
        CaseFailureReason reason = reasonFor(consentGiven(),
                row("PAN", "REVIEW", "{\"providerError\":true}", null, null, null));
        assertThat(reason).isEqualTo(CaseFailureReason.PAN_UNVERIFIED);
        assertThat(reason.retryable()).isTrue();
    }

    /**
     * Once the bureau has come back with a score the decision is unblocked, so a stale PAN problem
     * must stop being reported — otherwise "PAN not recognised" would sit on a sanctioned, disbursed
     * file for the rest of that customer's life.
     */
    @Test
    void aPanProblemStopsBeingReportedOnceTheBureauHasAnswered() {
        assertThat(reasonFor(consentGiven(),
                row("PAN", "FAIL", null, null, "SIGNZY", null),
                row("BUREAU", "PASS", "{\"noRecord\":false}", 700L, "FINTRIX_CRIF", "CCR-3")))
                .isEqualTo(CaseFailureReason.NONE);
    }

    @Test
    void withoutConsentNothingHasBeenAttemptedYet() {
        assertThat(reasonFor(row("PAN", "PASS", null, null, "SIGNZY", null)))
                .isEqualTo(CaseFailureReason.BUREAU_CONSENT_PENDING);
    }

    @Test
    void consentGivenButNoBureauRowMeansThePullNeverRan() {
        assertThat(reasonFor(consentGiven())).isEqualTo(CaseFailureReason.BUREAU_NOT_RUN);
    }

    @Test
    void anUnassignedFileWithNothingWrongSaysSo() {
        LoanApplication app = new LoanApplication();
        app.setId(APP);
        app.setStatus(ApplicationStatus.KYC_PENDING);
        when(applicationRepo.findAllById(any())).thenReturn(List.of(app));
        assertThat(reasonFor(consentGiven(),
                row("BUREAU", "PASS", "{\"noRecord\":false}", 780L, "FINTRIX_CRIF", "CCR-4")))
                .isEqualTo(CaseFailureReason.NONE);
    }

    // ---------------------------------------------------------------- precedence + retryability

    /**
     * A missing name and an unanswered security question can both be true. The name is what an admin
     * can fix in seconds, so it must win — chasing the borrower for an answer to a question the bureau
     * could never have matched would waste everybody's time.
     */
    @Test
    void aMissingNameOutranksAPendingSecurityQuestion() {
        assertThat(reasonFor(consentGiven(),
                bureau("REVIEW", "{\"missingProfileField\":\"name\",\"bureauChallenge\":true}")))
                .isEqualTo(CaseFailureReason.BUREAU_MISSING_NAME);
    }

    @Test
    void aSecurityQuestionOutranksAProviderErrorCodeOnTheSameRow() {
        assertThat(reasonFor(consentGiven(),
                bureau("REVIEW", "{\"bureauChallenge\":true,\"providerError\":true,"
                        + "\"providerErrorCode\":\"HTTP_503\"}")))
                .isEqualTo(CaseFailureReason.BUREAU_KBA_PENDING);
    }

    /**
     * Every bureau pull is billable and is a real credit inquiry on a real person's file, so a re-run
     * is offered ONLY where it could plausibly change the answer.
     */
    @Test
    void reRunIsNeverOfferedWhereItCouldOnlySpendMoney() {
        assertThat(CaseFailureReason.BUREAU_NO_RECORD.retryable()).isFalse();
        assertThat(CaseFailureReason.PAN_INVALID.retryable()).isFalse();
        assertThat(CaseFailureReason.BUREAU_PROVIDER_PLAN_LIMIT.retryable()).isFalse();
        assertThat(CaseFailureReason.BUREAU_MASKED_MOBILE_FOLLOW_UP.retryable()).isFalse();
        assertThat(CaseFailureReason.BUREAU_NO_SCORE.retryable()).isFalse();
        assertThat(CaseFailureReason.NONE.retryable()).isFalse();

        assertThat(CaseFailureReason.BUREAU_MISSING_NAME.retryable()).isTrue();
        assertThat(CaseFailureReason.BUREAU_MISSING_DOB.retryable()).isTrue();
        assertThat(CaseFailureReason.BUREAU_REPORT_DISCARDED.retryable()).isTrue();
        assertThat(CaseFailureReason.BUREAU_PROVIDER_UNAVAILABLE.retryable()).isTrue();
    }

    /** Unreadable stored JSON must never be read as "this file is fine". */
    @Test
    void malformedDerivedJsonDegradesRatherThanClaimingACleanFile() {
        assertThat(reasonFor(consentGiven(), bureau("REVIEW", "{not json")))
                .isEqualTo(CaseFailureReason.BUREAU_PROVIDER_UNAVAILABLE);
    }
}
