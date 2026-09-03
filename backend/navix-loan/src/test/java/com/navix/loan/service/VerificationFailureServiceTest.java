package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.dto.CaseFailureReason;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicationVerificationRepository;
import com.navix.loan.repository.ApplicationVerificationRepository.CaseFailureRow;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.service.VerificationFailureService.CaseFailure;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link VerificationFailureService#classify} walks {@link CaseFailureReason} in declaration order
 * and returns on the first match, so an application that trips two conditions at once must still
 * report only the one that has to be solved first. One test below exercises every reason the service
 * can actually produce; the precedence tests near the bottom exist because "two things are true at
 * once" is exactly the scenario the ordering was written to resolve, and a reordering that silently
 * changes an outcome would sail straight through the single-condition tests alone.
 */
@ExtendWith(MockitoExtension.class)
class VerificationFailureServiceTest {

    private static final Long APP = 42L;

    @Mock private ApplicationVerificationRepository verificationRepo;
    @Mock private CustomerProfileRepository profileRepo;
    @Mock private LoanApplicationRepository applicationRepo;

    private VerificationFailureService service;

    @BeforeEach
    void setUp() {
        service = new VerificationFailureService(verificationRepo, profileRepo, applicationRepo, new ObjectMapper());
    }

    // ---- fixture builders -----------------------------------------------------------

    private static CaseFailureRow row(String checkType, String status, String derivedJson) {
        return row(checkType, status, derivedJson, null, null, null);
    }

    private static CaseFailureRow row(String checkType, String status, String derivedJson, Long score,
                                      String provider, String providerTxnId) {
        CaseFailureRow r = mock(CaseFailureRow.class);
        lenient().when(r.getApplicationId()).thenReturn(APP);
        lenient().when(r.getCheckType()).thenReturn(checkType);
        lenient().when(r.getStatus()).thenReturn(status);
        lenient().when(r.getDerived()).thenReturn(derivedJson);
        lenient().when(r.getScore()).thenReturn(score);
        lenient().when(r.getProvider()).thenReturn(provider);
        lenient().when(r.getProviderTxnId()).thenReturn(providerTxnId);
        return r;
    }

    private void stubRows(CaseFailureRow... rows) {
        lenient().when(verificationRepo.findByApplicationIdInAndCheckTypeIn(any(), any()))
                .thenReturn(List.of(rows));
    }

    private void stubProfile(String fullName) {
        CustomerProfile p = new CustomerProfile();
        p.setApplicationId(APP);
        p.setFullName(fullName);
        lenient().when(profileRepo.findByApplicationIdIn(any())).thenReturn(List.of(p));
    }

    private void stubApplication(ApplicationStatus status, Long assignedExecutiveId) {
        LoanApplication a = new LoanApplication();
        a.setId(APP);
        a.setStatus(status);
        a.setAssignedExecutiveId(assignedExecutiveId);
        lenient().when(applicationRepo.findAllById(any())).thenReturn(List.of(a));
    }

    private CaseFailure classify() {
        return service.failure(APP);
    }

    // ---- one test per reason ---------------------------------------------------------

    @Test
    void bureauReview_identityMismatch_reportsBureauIdentityMismatch() {
        stubRows(row("BUREAU", "REVIEW", "{\"identityMismatch\":\"PAN mismatch: report has ZZZZZ9999Z\"}"));

        assertThat(classify()).isEqualTo(new CaseFailure(CaseFailureReason.BUREAU_IDENTITY_MISMATCH, "BUREAU"));
    }

    @Test
    void bureauReview_missingName_reportsBureauMissingName() {
        stubRows(row("BUREAU", "REVIEW", "{\"missingProfileField\":\"name\"}"));

        assertThat(classify()).isEqualTo(new CaseFailure(CaseFailureReason.BUREAU_MISSING_NAME, "BUREAU"));
    }

    @Test
    void bureauReview_missingDob_reportsBureauMissingDob() {
        stubRows(row("BUREAU", "REVIEW", "{\"missingProfileField\":\"dob\"}"));

        assertThat(classify()).isEqualTo(new CaseFailure(CaseFailureReason.BUREAU_MISSING_DOB, "BUREAU"));
    }

    @Test
    void bureauReview_kbaChallengePending_reportsBureauKbaPending() {
        stubRows(row("BUREAU", "REVIEW", "{\"bureauChallenge\":true}"));

        assertThat(classify()).isEqualTo(new CaseFailure(CaseFailureReason.BUREAU_KBA_PENDING, "BUREAU"));
    }

    @Test
    void bureauReview_kbaChallengeSkipped_reportsBureauKbaSkipped() {
        stubRows(row("BUREAU", "REVIEW", "{\"bureauChallenge\":true,\"bureauChallengeSkipped\":true}"));

        assertThat(classify()).isEqualTo(new CaseFailure(CaseFailureReason.BUREAU_KBA_SKIPPED, "BUREAU"));
    }

    @Test
    void bureauReview_maskedMobileRequired_reportsBureauMaskedMobileFollowUp() {
        stubRows(row("BUREAU", "REVIEW", "{\"bureauMaskedMobileRequired\":true}"));

        assertThat(classify())
                .isEqualTo(new CaseFailure(CaseFailureReason.BUREAU_MASKED_MOBILE_FOLLOW_UP, "BUREAU"));
    }

    @Test
    void bureauReview_providerErrorHttp402_reportsProviderNoBalance() {
        stubRows(row("BUREAU", "REVIEW", "{\"providerErrorCode\":\"HTTP_402\"}"));

        assertThat(classify())
                .isEqualTo(new CaseFailure(CaseFailureReason.BUREAU_PROVIDER_NO_BALANCE, "BUREAU"));
    }

    @Test
    void bureauReview_providerErrorHttp400_reportsProviderRejectedRequest() {
        stubRows(row("BUREAU", "REVIEW", "{\"providerErrorCode\":\"HTTP_400\"}"));

        assertThat(classify())
                .isEqualTo(new CaseFailure(CaseFailureReason.BUREAU_PROVIDER_REJECTED_REQUEST, "BUREAU"));
    }

    @Test
    void bureauReview_providerErrorHttp422_reportsProviderPlanLimit() {
        stubRows(row("BUREAU", "REVIEW", "{\"providerErrorCode\":\"HTTP_422\"}"));

        assertThat(classify())
                .isEqualTo(new CaseFailure(CaseFailureReason.BUREAU_PROVIDER_PLAN_LIMIT, "BUREAU"));
    }

    @Test
    void bureauReview_providerErrorHttp503_reportsProviderUnavailable() {
        stubRows(row("BUREAU", "REVIEW", "{\"providerErrorCode\":\"HTTP_503\"}"));

        assertThat(classify())
                .isEqualTo(new CaseFailure(CaseFailureReason.BUREAU_PROVIDER_UNAVAILABLE, "BUREAU"));
    }

    /**
     * The 47-case historical fingerprint: a real report was returned and the pre-fix rule threw it away
     * for carrying a score outside CRIF's band, but recorded it as "no record". On Fintrix the
     * provider's own transaction id is the report's {@code HEADER.REPORT-ID}, so its presence is what
     * proves a report actually existed — a genuine thin file never has one.
     */
    @Test
    void bureauPass_noRecordWithFintrixProviderTxnId_reportsBureauReportDiscarded() {
        stubRows(row("BUREAU", "PASS", "{\"noRecord\":true}", null, "FINTRIX_CRIF", "RPT-2026-00147"));

        assertThat(classify()).isEqualTo(new CaseFailure(CaseFailureReason.BUREAU_REPORT_DISCARDED, "BUREAU"));
    }

    /** The 44-case historical signature: no report id to salvage, and the profile never got a name. */
    @Test
    void bureauPass_noRecordNoTxnId_blankProfileName_reportsBureauMissingName() {
        stubRows(row("BUREAU", "PASS", "{\"noRecord\":true}", null, "DIGITAP_EXPERIAN", null));
        stubProfile("");

        assertThat(classify()).isEqualTo(new CaseFailure(CaseFailureReason.BUREAU_MISSING_NAME, "BUREAU"));
    }

    /** The 84 genuine no-hits — the bureau really has nothing on this identity. Not a bug. */
    @Test
    void bureauPass_noRecordNoTxnId_profileHasAName_reportsBureauNoRecord() {
        stubRows(row("BUREAU", "PASS", "{\"noRecord\":true}", null, "DIGITAP_EXPERIAN", null));
        stubProfile("SHUBHAM");

        assertThat(classify()).isEqualTo(new CaseFailure(CaseFailureReason.BUREAU_NO_RECORD, "BUREAU"));
    }

    @Test
    void bureauPass_noNoRecordFlag_nullScore_reportsBureauNoScore() {
        stubRows(row("BUREAU", "PASS", "{\"noRecord\":false}", null, "FINTRIX_CRIF", null));

        assertThat(classify()).isEqualTo(new CaseFailure(CaseFailureReason.BUREAU_NO_SCORE, "BUREAU"));
    }

    @Test
    void bureauPass_withScore_reportsNone() {
        stubRows(row("BUREAU", "PASS", "{\"noRecord\":false}", 778L, "DIGITAP_EXPERIAN", "TXN-1"));

        assertThat(classify()).isEqualTo(CaseFailure.none());
    }

    @Test
    void panFail_withNoBureauPass_reportsPanInvalid() {
        stubRows(row("PAN", "FAIL", null));

        assertThat(classify()).isEqualTo(new CaseFailure(CaseFailureReason.PAN_INVALID, "PAN"));
    }

    @Test
    void panReview_providerError_withNoBureauPass_reportsPanUnverified() {
        stubRows(row("PAN", "REVIEW", "{\"providerError\":true}"));

        assertThat(classify()).isEqualTo(new CaseFailure(CaseFailureReason.PAN_UNVERIFIED, "PAN"));
    }

    @Test
    void noRows_reportsBureauConsentPending() {
        stubRows();

        assertThat(classify()).isEqualTo(new CaseFailure(CaseFailureReason.BUREAU_CONSENT_PENDING, "BUREAU_CONSENT"));
    }

    @Test
    void consentPassed_noBureauRowAtAll_reportsBureauNotRun() {
        stubRows(row("BUREAU_CONSENT", "PASS", null));

        assertThat(classify()).isEqualTo(new CaseFailure(CaseFailureReason.BUREAU_NOT_RUN, "BUREAU"));
    }

    /**
     * A real bureau pull only ever records {@code PASS} or {@code REVIEW} ({@code ApplicationVerificationService
     * .finishBureauPull}), both of which classify above and never fall through to here. The only way a
     * BUREAU row reaches this branch with neither status is a manual override
     * ({@code manualDecision(..., pass=false)}), which writes {@code FAIL} on any check type — an admin
     * having failed the check by hand for a reason unrelated to any marker this service reads. With
     * consent given and nothing else standing in the way, "unassigned" is genuinely the only open item.
     */
    @Test
    void consentPassed_bureauRowInAnUnclassifiedStatus_unassignedKycPending_reportsAwaitingAssignment() {
        stubRows(row("BUREAU_CONSENT", "PASS", null), row("BUREAU", "FAIL", null));
        stubApplication(ApplicationStatus.KYC_PENDING, null);

        assertThat(classify()).isEqualTo(new CaseFailure(CaseFailureReason.AWAITING_ASSIGNMENT, null));
    }

    // ---- precedence: two problems true at once, the earlier declaration wins --------

    /** An identity mismatch AND a provider error code both present — the mismatch is reported. */
    @Test
    void precedence_identityMismatchBeatsProviderError() {
        stubRows(row("BUREAU", "REVIEW",
                "{\"identityMismatch\":\"PAN mismatch\",\"providerErrorCode\":\"HTTP_402\"}"));

        assertThat(classify()).isEqualTo(new CaseFailure(CaseFailureReason.BUREAU_IDENTITY_MISMATCH, "BUREAU"));
    }

    /** A missing name AND a pending KBA challenge both present — the missing name is reported. */
    @Test
    void precedence_missingNameBeatsKbaChallenge() {
        stubRows(row("BUREAU", "REVIEW", "{\"missingProfileField\":\"name\",\"bureauChallenge\":true}"));

        assertThat(classify()).isEqualTo(new CaseFailure(CaseFailureReason.BUREAU_MISSING_NAME, "BUREAU"));
    }

    // ---- retryable() ------------------------------------------------------------------

    /**
     * Every bureau pull is billable and a real credit inquiry, so offering a re-run on these would
     * only spend money to reach the same answer: a genuine no-hit stays a no-hit, an invalid PAN stays
     * invalid, a plan limit needs a bigger plan (not another call), and the masked-mobile case needs a
     * vendor endpoint we don't implement.
     */
    @Test
    void retryable_isFalseForReasonsWhereARerunCannotChangeTheAnswer() {
        assertThat(CaseFailureReason.BUREAU_NO_RECORD.retryable()).isFalse();
        assertThat(CaseFailureReason.PAN_INVALID.retryable()).isFalse();
        assertThat(CaseFailureReason.BUREAU_PROVIDER_PLAN_LIMIT.retryable()).isFalse();
        assertThat(CaseFailureReason.BUREAU_MASKED_MOBILE_FOLLOW_UP.retryable()).isFalse();
    }
}
