package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.domain.BureauBackfillCohort;
import com.navix.loan.domain.BureauBackfillOutcome;
import com.navix.loan.dto.BureauBackfillDtos.BackfillPreview;
import com.navix.loan.dto.BureauBackfillDtos.BackfillRunSummary;
import com.navix.loan.entity.ApplicationDocument;
import com.navix.loan.entity.ApplicationRejection;
import com.navix.loan.entity.ApplicationVerification;
import com.navix.loan.entity.BureauBackfillRow;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicationDocumentRepository;
import com.navix.loan.repository.ApplicationRejectionRepository;
import com.navix.loan.repository.ApplicationVerificationRepository;
import com.navix.loan.repository.BureauBackfillRowRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.time.Instant;
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
 * The bureau rescore backfill (plana.md Part B). {@link ApplicationVerificationService} and
 * {@link ApplicationFlowService} are mocked — this class exercises the backfill's own orchestration
 * (cohort selection, outcome classification, resumability, the max-rows cap), not the pull/reopen
 * logic those two already cover in their own test classes.
 */
@ExtendWith(MockitoExtension.class)
class BureauBackfillServiceTest {

    @Mock private LoanApplicationRepository applicationRepo;
    @Mock private ApplicationRejectionRepository rejectionRepo;
    @Mock private CustomerProfileRepository profileRepo;
    @Mock private ApplicationVerificationRepository verificationRepo;
    @Mock private ApplicationDocumentRepository documentRepo;
    @Mock private BureauBackfillRowRepository backfillRepo;
    @Mock private ApplicationVerificationService verificationService;
    @Mock private ApplicationFlowService flow;

    private BureauBackfillService service;

    @BeforeEach
    void setUp() {
        service = new BureauBackfillService(applicationRepo, rejectionRepo, profileRepo, verificationRepo,
                documentRepo, backfillRepo, verificationService, flow, new ObjectMapper());
        ActorContext.set(new CurrentActor("admin-1", "Admin", "ADMIN"));
    }

    @AfterEach
    void clearActor() {
        ActorContext.clear();
    }

    private static LoanApplication app(long id, long customerId, ApplicationStatus status) {
        LoanApplication a = new LoanApplication();
        a.setId(id);
        a.setCustomerId(customerId);
        a.setStatus(status);
        return a;
    }

    private static ApplicationVerification bureauRow(String status, Long score, String derivedJson,
                                                      String message) {
        ApplicationVerification v = new ApplicationVerification();
        v.setCheckType("BUREAU");
        v.setStatus(status);
        v.setScore(score);
        v.setDerived(derivedJson);
        v.setMessage(message);
        return v;
    }

    private static CustomerProfile profile(Long score, Instant briefAt) {
        CustomerProfile p = new CustomerProfile();
        p.setBureauScore(score);
        p.setCreditBriefGeneratedAt(briefAt);
        return p;
    }

    private static ApplicationDocument doc(long id) {
        ApplicationDocument d = new ApplicationDocument();
        d.setId(id);
        return d;
    }

    private ArgumentCaptor<BureauBackfillRow> savedRow() {
        ArgumentCaptor<BureauBackfillRow> captor = ArgumentCaptor.forClass(BureauBackfillRow.class);
        verify(backfillRepo).save(captor.capture());
        return captor;
    }

    @Test
    void reject560_reopens() {
        LoanApplication a = app(1L, 7L, ApplicationStatus.REJECTED);
        ApplicationRejection rejection = new ApplicationRejection();
        rejection.setApplicationId(1L);
        rejection.setReasonCode(ApplicationRejection.LOW_BUREAU_SCORE);
        when(rejectionRepo.findByReasonCodeOrderByIdDesc(ApplicationRejection.LOW_BUREAU_SCORE))
                .thenReturn(List.of(rejection));
        when(applicationRepo.findById(1L)).thenReturn(Optional.of(a));
        when(profileRepo.findByApplicationId(1L))
                .thenReturn(Optional.of(profile(540L, null)))          // before the pull
                .thenReturn(Optional.of(profile(560L, Instant.now()))); // after: brief regenerated
        when(verificationRepo.findByApplicationIdAndCheckType(1L, "BUREAU"))
                .thenReturn(Optional.of(bureauRow("PASS", 560L, "{}", "Bureau pulled")));
        when(documentRepo.findFirstByApplicationIdAndDocTypeOrderByIdDesc(1L, "BUREAU_REPORT"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(doc(9L)));
        when(verificationService.allRequiredPassed(1L)).thenReturn(true);
        when(flow.reopenAfterRescore(eq(1L), any(), eq(560L), any()))
                .thenReturn(ApplicationFlowService.ReopenOutcome.REOPENED);

        BackfillRunSummary summary = service.execute(BureauBackfillCohort.REJECTS, 10);

        assertThat(summary.processed()).isEqualTo(1);
        verify(verificationService).pullBureau(1L, null, true, false);
        BureauBackfillRow row = savedRow().getValue();
        assertThat(row.getOutcome()).isEqualTo(BureauBackfillOutcome.REOPENED.name());
        assertThat(row.getOldScore()).isEqualTo(540L);
        assertThat(row.getNewScore()).isEqualTo(560L);
        assertThat(row.getFailedStep()).isNull();
        assertThat(row.getCohort()).isEqualTo(BureauBackfillCohort.REJECTS.name());
    }

    @Test
    void reject540_staysStillBelow_neverReopened() {
        LoanApplication a = app(1L, 7L, ApplicationStatus.REJECTED);
        ApplicationRejection rejection = new ApplicationRejection();
        rejection.setApplicationId(1L);
        rejection.setReasonCode(ApplicationRejection.LOW_BUREAU_SCORE);
        Instant originalBlock = Instant.now().plus(java.time.Duration.ofDays(60));
        rejection.setBlockedUntil(originalBlock);
        when(rejectionRepo.findByReasonCodeOrderByIdDesc(ApplicationRejection.LOW_BUREAU_SCORE))
                .thenReturn(List.of(rejection));
        when(applicationRepo.findById(1L)).thenReturn(Optional.of(a));
        when(profileRepo.findByApplicationId(1L))
                .thenReturn(Optional.of(profile(540L, null)))
                .thenReturn(Optional.of(profile(540L, Instant.now())));
        when(verificationRepo.findByApplicationIdAndCheckType(1L, "BUREAU"))
                .thenReturn(Optional.of(bureauRow("PASS", 540L, "{}", "Bureau pulled")));
        when(documentRepo.findFirstByApplicationIdAndDocTypeOrderByIdDesc(1L, "BUREAU_REPORT"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(doc(9L)));

        service.execute(BureauBackfillCohort.REJECTS, 10);

        verify(flow, never()).reopenAfterRescore(any(), any(), any(), any());
        BureauBackfillRow row = savedRow().getValue();
        assertThat(row.getOutcome()).isEqualTo(BureauBackfillOutcome.STILL_BELOW.name());
        // The row itself is the untouched-clock evidence: the backfill never calls reopenAfterRescore
        // (the only place blocked_until is ever cleared), so the block's own instant is unchanged.
        assertThat(rejection.getBlockedUntil()).isEqualTo(originalBlock);
    }

    @Test
    void liveCohortRefresh_neverAutoRejects() {
        LoanApplication a = app(2L, 8L, ApplicationStatus.CREDIT_EXEC_PENDING);
        when(applicationRepo.findByStatusOrderByCreatedAtDescIdDesc(ApplicationStatus.CREDIT_EXEC_PENDING))
                .thenReturn(List.of(a));
        when(profileRepo.findByApplicationId(2L))
                .thenReturn(Optional.of(profile(540L, null)))
                .thenReturn(Optional.of(profile(540L, Instant.now())));
        when(verificationRepo.findByApplicationIdAndCheckType(2L, "BUREAU"))
                .thenReturn(Optional.of(bureauRow("PASS", 540L, "{}", "Bureau pulled")));
        when(documentRepo.findFirstByApplicationIdAndDocTypeOrderByIdDesc(2L, "BUREAU_REPORT"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(doc(11L)));

        service.execute(BureauBackfillCohort.CREDIT_REVIEW, 10);

        // The contract: a refresh always passes allowAutoReject=false, and the backfill never itself
        // calls reopen/reject for a live cohort.
        verify(verificationService).pullBureau(2L, null, true, false);
        verify(flow, never()).reopenAfterRescore(any(), any(), any(), any());
        BureauBackfillRow row = savedRow().getValue();
        assertThat(row.getOutcome()).isEqualTo(BureauBackfillOutcome.REFRESHED.name());
    }

    @Test
    void thinFile_recordsNoBrief_notSuccess() {
        LoanApplication a = app(3L, 9L, ApplicationStatus.KYC_APPROVED);
        when(applicationRepo.findByStatusOrderByCreatedAtDescIdDesc(ApplicationStatus.KYC_APPROVED))
                .thenReturn(List.of(a));
        when(applicationRepo.findByStatusOrderByCreatedAtDescIdDesc(ApplicationStatus.PRE_APPROVED))
                .thenReturn(List.of());
        when(applicationRepo.findByStatusOrderByCreatedAtDescIdDesc(ApplicationStatus.SANCTIONED))
                .thenReturn(List.of());
        Instant sameBriefAt = Instant.now();
        when(profileRepo.findByApplicationId(3L))
                .thenReturn(Optional.of(profile(null, sameBriefAt)))
                .thenReturn(Optional.of(profile(null, sameBriefAt))); // unchanged: generate() no-op'd
        when(verificationRepo.findByApplicationIdAndCheckType(3L, "BUREAU"))
                .thenReturn(Optional.of(bureauRow("PASS", null, "{\"noRecord\":true}", "Thin-file")));

        service.execute(BureauBackfillCohort.APPROVED, 10);

        BureauBackfillRow row = savedRow().getValue();
        assertThat(row.getOutcome()).isEqualTo(BureauBackfillOutcome.NO_BRIEF.name());
    }

    @Test
    void failedPdfIngest_stillRefreshed_withFailedStep() {
        LoanApplication a = app(4L, 10L, ApplicationStatus.CREDIT_EXEC_PENDING);
        when(applicationRepo.findByStatusOrderByCreatedAtDescIdDesc(ApplicationStatus.CREDIT_EXEC_PENDING))
                .thenReturn(List.of(a));
        when(profileRepo.findByApplicationId(4L))
                .thenReturn(Optional.of(profile(700L, null)))
                .thenReturn(Optional.of(profile(700L, Instant.now()))); // brief DID regenerate
        when(verificationRepo.findByApplicationIdAndCheckType(4L, "BUREAU"))
                .thenReturn(Optional.of(bureauRow("PASS", 700L, "{}", "Bureau pulled")));
        // Same doc id before/after — the PDF ingest never produced a new document.
        when(documentRepo.findFirstByApplicationIdAndDocTypeOrderByIdDesc(4L, "BUREAU_REPORT"))
                .thenReturn(Optional.of(doc(5L)))
                .thenReturn(Optional.of(doc(5L)));

        service.execute(BureauBackfillCohort.CREDIT_REVIEW, 10);

        BureauBackfillRow row = savedRow().getValue();
        assertThat(row.getOutcome()).isEqualTo(BureauBackfillOutcome.REFRESHED.name());
        assertThat(row.getFailedStep()).isEqualTo("PDF_INGEST");
    }

    @Test
    void rerun_skipsAlreadyProcessed_retriesOnlyFailed() {
        LoanApplication alreadyRefreshed = app(1L, 1L, ApplicationStatus.CREDIT_EXEC_PENDING);
        LoanApplication failed = app(2L, 2L, ApplicationStatus.CREDIT_EXEC_PENDING);
        when(applicationRepo.findByStatusOrderByCreatedAtDescIdDesc(ApplicationStatus.CREDIT_EXEC_PENDING))
                .thenReturn(List.of(alreadyRefreshed, failed));
        BureauBackfillRow priorSuccess = new BureauBackfillRow();
        priorSuccess.setOutcome(BureauBackfillOutcome.REFRESHED.name());
        when(backfillRepo.findFirstByApplicationIdOrderByIdDesc(1L)).thenReturn(Optional.of(priorSuccess));
        BureauBackfillRow priorFailure = new BureauBackfillRow();
        priorFailure.setOutcome(BureauBackfillOutcome.FAILED.name());
        when(backfillRepo.findFirstByApplicationIdOrderByIdDesc(2L)).thenReturn(Optional.of(priorFailure));
        when(profileRepo.findByApplicationId(2L))
                .thenReturn(Optional.of(profile(700L, null)))
                .thenReturn(Optional.of(profile(700L, Instant.now())));
        when(verificationRepo.findByApplicationIdAndCheckType(2L, "BUREAU"))
                .thenReturn(Optional.of(bureauRow("PASS", 700L, "{}", "Bureau pulled")));
        when(documentRepo.findFirstByApplicationIdAndDocTypeOrderByIdDesc(2L, "BUREAU_REPORT"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(doc(1L)));

        BackfillRunSummary summary = service.execute(BureauBackfillCohort.CREDIT_REVIEW, 10);

        assertThat(summary.processed()).isEqualTo(1);
        verify(verificationService).pullBureau(2L, null, true, false);
        verify(verificationService, never()).pullBureau(eq(1L), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void execute_rejectsALimitAboveTheHardCap() {
        assertThatThrownBy(() -> service.execute(BureauBackfillCohort.CREDIT_REVIEW,
                BureauBackfillService.MAX_ROWS_PER_RUN + 1))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(verificationService);
    }

    @Test
    void execute_rejectsANonPositiveLimit() {
        assertThatThrownBy(() -> service.execute(BureauBackfillCohort.CREDIT_REVIEW, 0))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void preview_makesZeroProviderCalls() {
        when(rejectionRepo.findByReasonCodeOrderByIdDesc(any())).thenReturn(List.of());
        when(applicationRepo.findByStatusOrderByCreatedAtDescIdDesc(any())).thenReturn(List.of());

        BackfillPreview preview = service.preview();

        assertThat(preview.counts().get(BureauBackfillCohort.REJECTS)).isZero();
        verifyNoInteractions(verificationService);
        verify(applicationRepo, never()).findById(anyLong());
    }
}
