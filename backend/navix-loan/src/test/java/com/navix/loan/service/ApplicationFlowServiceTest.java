package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.staff.StaffDirectory;
import com.navix.common.staff.StaffSummary;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.domain.LoanStatus;
import com.navix.loan.domain.PaymentStatus;
import com.navix.loan.dto.ApplicationDtos.EventView;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.ApplicationDocument;
import com.navix.loan.entity.ApplicationEvent;
import com.navix.loan.entity.ApplicationRejection;
import com.navix.loan.entity.ApplicationVerification;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.entity.Payment;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.ApplicationEventRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.repository.PaymentRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ApplicationFlowServiceTest {

    @Mock
    private LoanApplicationRepository applicationRepository;
    @Mock
    private ApplicationEventRepository eventRepository;
    @Mock
    private LoanService loanService;
    @Mock
    private StaffDirectory staffDirectory;
    @Mock
    private LoanRepository loanRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private CustomerProfileRepository profileRepository;
    @Mock
    private com.navix.loan.repository.ApplicationRejectionRepository rejectionRepository;
    @Mock
    private com.navix.loan.repository.ApplicationDocumentRepository documentRepository;
    @Mock
    private com.navix.common.risk.RiskPort riskPort;
    @Mock
    private ReferralService referralService;
    @Mock
    private DsaCommissionService dsaCommissionService;
    @Mock
    private com.navix.loan.repository.ApplicationVerificationRepository verificationRepository;
    @Mock
    private com.navix.loan.repository.ApplicationReferenceRepository referenceRepository;

    private ApplicationFlowService flow;
    private final List<ApplicationEvent> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        flow = new ApplicationFlowService(applicationRepository, eventRepository,
                new EligibilityService(applicationRepository, riskPort), loanService, staffDirectory,
                loanRepository, paymentRepository, profileRepository, rejectionRepository,
                documentRepository, verificationRepository, referenceRepository, new LoanMath(),
                event -> {}, referralService, dsaCommissionService);
        // Default: assignee passes activation gating; negative case overrides below.
        lenient().when(staffDirectory.isActiveWithRole(any(), any())).thenReturn(true);
        lenient().when(applicationRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(eventRepository.save(any())).thenAnswer(i -> {
            events.add(i.getArgument(0));
            return i.getArgument(0);
        });
        lenient().when(eventRepository.findByApplicationIdOrderByAtAsc(any())).thenReturn(events);
    }

    @AfterEach
    void clearActor() {
        ActorContext.clear();
    }

    private void actor(String id, String role) {
        ActorContext.set(new CurrentActor(id, id, role));
    }

    private LoanApplication appAt(ApplicationStatus status) {
        LoanApplication app = new LoanApplication();
        app.setId(1L);
        app.setCustomerId(7L);
        app.setAmountRequested(1_000_000L);
        app.setStatus(status);
        // The normal path: OfferService.confirmDisbursalAccount already ran (penny drop or the
        // account-unchanged fast path) before the application ever reaches disbursement, so the
        // account is verified by the time a test drives it here. The BANK_ACCOUNT_UNVERIFIED-gate
        // tests below override this explicitly to cover the unverified case.
        app.setDisbursalAccountVerified(Boolean.TRUE);
        lenient().when(applicationRepository.findById(1L)).thenReturn(Optional.of(app));
        return app;
    }

    @Test
    void autoReject_recordsTheReasonAndStartsTheCoolingOffWindow() {
        LoanApplication app = appAt(ApplicationStatus.DRAFT);
        actor("7", "BORROWER");
        CustomerProfile p = new CustomerProfile();
        p.setMobile("9876543210");
        when(profileRepository.findByApplicationId(1L)).thenReturn(Optional.of(p));

        LoanApplication out = flow.autoReject(app.getId(), ApplicationRejection.SELF_EMPLOYED,
                "Declared self-employed at intake", ApplicationFlowService.SELF_EMPLOYED_BLOCK_DAYS);

        assertThat(out.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        ArgumentCaptor<ApplicationRejection> saved = ArgumentCaptor.forClass(ApplicationRejection.class);
        verify(rejectionRepository).save(saved.capture());
        assertThat(saved.getValue().getReasonCode()).isEqualTo(ApplicationRejection.SELF_EMPLOYED);
        assertThat(saved.getValue().getMobile()).isEqualTo("9876543210");
        assertThat(saved.getValue().getBlockedUntil())
                .isAfter(Instant.now().plus(Duration.ofDays(89)));
    }

    @Test
    void blockedMobile_cannotStartANewApplication() {
        actor("7", "BORROWER");
        CustomerProfile prior = new CustomerProfile();
        prior.setApplicationId(1L);
        prior.setMobile("9876543210");
        LoanApplication rejected = new LoanApplication();
        rejected.setId(1L);
        rejected.setCustomerId(7L);
        rejected.setStatus(ApplicationStatus.REJECTED);
        when(applicationRepository.findByCustomerId(7L)).thenReturn(List.of(rejected));
        when(profileRepository.findByApplicationId(1L)).thenReturn(Optional.of(prior));
        ApplicationRejection block = new ApplicationRejection();
        block.setBlockedUntil(Instant.now().plus(Duration.ofDays(90)));
        when(rejectionRepository.findFirstByMobileAndBlockedUntilAfterOrderByBlockedUntilDesc(
                eq("9876543210"), any())).thenReturn(Optional.of(block));

        // Re-answering the employment question must not get them back in.
        assertThatThrownBy(() -> flow.createDraft(7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not eligible");
    }

    @Test
    void creditToActiveHappyPath() {
        LoanApplication app = appAt(ApplicationStatus.KYC_PENDING);
        Loan loan = new Loan();
        loan.setId(99L);
        when(loanService.disburse(any(), any(), any())).thenReturn(loan);

        actor("head1", "CREDIT_HEAD");
        flow.assignExecutive(1L, 55L);
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.CREDIT_EXEC_PENDING);

        // The executive's sanction is FINAL — no Head counter-approval (V45).
        actor("55", "CREDIT_EXECUTIVE");
        flow.sanction(1L, 2_000_000L, 28, "salary slips verified");
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.SANCTIONED);
        assertThat(app.getSanctionedAmountPaise()).isEqualTo(2_000_000L);
        assertThat(app.getSalaryCreditDay()).isEqualTo(28);
        assertThat(app.getApprovedRepaymentDate()).isAfter(LocalDate.now());

        // The borrower accepts the offer, drawing down less than the ceiling. Phase 3's offer screens
        // sit in front of this call; all it needs from them is a signed sanction letter.
        actor("7", "BORROWER");
        esigned(1L);
        flow.acceptOffer(1L, 1_500_000L);
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.DISBURSEMENT_PENDING);
        assertThat(app.getAmountRequested()).isEqualTo(1_500_000L);

        // The Disbursement Head makes the transfer and releases it directly — no accountant hop
        // behind them since V47 (decision 42).
        actor("disb1", "DISBURSEMENT_HEAD");
        flow.disbursementDecision(1L, true, "UTR123", "UTR ok");
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.ACTIVE);
        assertThat(app.getLoanId()).isEqualTo(99L);
    }

    @Test
    void acceptOfferRejectsMoreThanTheSanctionedCeiling() {
        LoanApplication app = appAt(ApplicationStatus.SANCTIONED);
        app.setSanctionedAmountPaise(2_000_000L);
        actor("7", "BORROWER");
        esigned(1L);

        assertThatThrownBy(() -> flow.acceptOffer(1L, 2_500_000L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("exceeds the sanctioned amount");
    }

    @Test
    void acceptOfferRefusesAnUnsignedSanctionLetter() {
        LoanApplication app = appAt(ApplicationStatus.SANCTIONED);
        app.setSanctionedAmountPaise(2_000_000L);
        actor("7", "BORROWER");

        assertThatThrownBy(() -> flow.acceptOffer(1L, 2_000_000L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Sign your sanction letter");
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.SANCTIONED);
    }

    /** Stand in for the borrower having eSigned their Key Fact Statement (V46). */
    private void esigned(Long appId) {
        com.navix.loan.entity.ApplicationVerification row =
                new com.navix.loan.entity.ApplicationVerification();
        row.setApplicationId(appId);
        row.setCheckType(ApplicationVerificationService.ESIGN);
        row.setStatus(ApplicationVerificationService.PASS);
        lenient().when(verificationRepository.findByApplicationIdAndCheckType(
                appId, ApplicationVerificationService.ESIGN)).thenReturn(Optional.of(row));
    }

    @Test
    void sanctionRejectsAnInvalidSalaryCreditDay() {
        LoanApplication app = appAt(ApplicationStatus.CREDIT_EXEC_PENDING);
        app.setAssignedExecutiveId(55L);
        actor("55", "CREDIT_EXECUTIVE");

        assertThatThrownBy(() -> flow.sanction(1L, 2_000_000L, 32, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("between 1 and 31");
    }

    @Test
    void rejectLeadRecordsAManualRegisterRowWithA30DayBlock() {
        LoanApplication app = appAt(ApplicationStatus.CREDIT_EXEC_PENDING);
        app.setAssignedExecutiveId(55L);
        actor("55", "CREDIT_EXECUTIVE");

        flow.rejectLead(1L, "salary slips inconsistent");

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        ArgumentCaptor<ApplicationRejection> captor = ArgumentCaptor.forClass(ApplicationRejection.class);
        verify(rejectionRepository).save(captor.capture());
        assertThat(captor.getValue().getReasonCode()).isEqualTo(ApplicationRejection.MANUAL);
        assertThat(captor.getValue().getAuto()).isFalse();
        // A reject means the door is shut for a month. Fixable paperwork should be parked, not rejected.
        assertThat(captor.getValue().getBlockedUntil())
                .isCloseTo(Instant.now().plus(Duration.ofDays(ApplicationFlowService.MANUAL_REJECT_BLOCK_DAYS)),
                        within(1, java.time.temporal.ChronoUnit.MINUTES));
    }

    /**
     * The hole this block closes: a borrower rejected by credit used to tap "Borrow again" moments
     * later and come back PRE_APPROVED, skipping both KYC and credit review.
     */
    @Test
    void reborrowIsBlockedByTheCoolingOffFromAManualReject() {
        actor("7", "BORROWER");
        LoanApplication rejected = priorApp();
        rejected.setStatus(ApplicationStatus.REJECTED);
        CustomerProfile profile = priorProfile();
        profile.setMobile("9000000000"); // the block is keyed on the mobile, not the customer id
        when(applicationRepository.findByCustomerId(7L)).thenReturn(List.of(rejected));
        when(profileRepository.findByApplicationId(10L)).thenReturn(Optional.of(profile));
        ApplicationRejection block = new ApplicationRejection();
        block.setBlockedUntil(Instant.now().plus(Duration.ofDays(29)));
        when(rejectionRepository.findFirstByMobileAndBlockedUntilAfterOrderByBlockedUntilDesc(
                eq("9000000000"), any())).thenReturn(Optional.of(block));

        assertThatThrownBy(() -> flow.reborrow())
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "NOT_ELIGIBLE");
    }

    @Test
    void markPendingTagsWithoutChangingStatus() {
        LoanApplication app = appAt(ApplicationStatus.CREDIT_EXEC_PENDING);
        app.setAssignedExecutiveId(55L);
        actor("55", "CREDIT_EXECUTIVE");

        flow.markPending(1L, "waiting on bank statement");

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.CREDIT_EXEC_PENDING);
        assertThat(app.getPendingReason()).isEqualTo("waiting on bank statement");
        assertThat(app.getMarkedPendingAt()).isNotNull();
    }

    @Test
    void disbursementWithTxnRefSkipsAccountantAndActivates() {
        LoanApplication app = appAt(ApplicationStatus.DISBURSEMENT_PENDING);
        Loan loan = new Loan();
        loan.setId(99L);
        when(loanService.disburse(any(), any(), any())).thenReturn(loan);

        actor("disb1", "DISBURSEMENT_HEAD");
        flow.disbursementDecision(1L, true, "UTR999", "released");

        // Skips ACCOUNTANT_PENDING entirely: DISBURSEMENT_PENDING → DISBURSED → ACTIVE.
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.ACTIVE);
        assertThat(app.getLoanId()).isEqualTo(99L);
    }

    /**
     * The transaction id is the evidence that the transfer happened. Accepting without one used to
     * park the file with an accountant; with that hop gone (V47) it would release money on nothing
     * but an assertion, so it is refused.
     */
    @Test
    void disbursementWithoutTxnRefIsRefused() {
        LoanApplication app = appAt(ApplicationStatus.DISBURSEMENT_PENDING);
        actor("disb1", "DISBURSEMENT_HEAD");

        assertThatThrownBy(() -> flow.disbursementDecision(1L, true, "   ", null)) // blank counts as none
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("transaction id");
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.DISBURSEMENT_PENDING);
    }

    /**
     * The hard gate on release (the bank-proof escape hatch's whole reason for existing):
     * OfferService.confirmDisbursalAccount's useBankProof branch deliberately leaves
     * disbursalAccountVerified false, and nothing may release money against an account nobody —
     * neither the automated penny drop nor a human — has confirmed.
     */
    @Test
    void disbursementIsRefusedWhenTheBankAccountIsNotVerified() {
        LoanApplication app = appAt(ApplicationStatus.DISBURSEMENT_PENDING);
        app.setDisbursalAccountVerified(Boolean.FALSE);
        actor("disb1", "DISBURSEMENT_HEAD");

        assertThatThrownBy(() -> flow.disbursementDecision(1L, true, "UTR999", "released"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "BANK_ACCOUNT_UNVERIFIED");
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.DISBURSEMENT_PENDING);
    }

    @Test
    void disbursementSucceedsOnceTheBankAccountIsVerified() {
        LoanApplication app = appAt(ApplicationStatus.DISBURSEMENT_PENDING);
        app.setDisbursalAccountVerified(Boolean.TRUE);
        Loan loan = new Loan();
        loan.setId(99L);
        when(loanService.disburse(any(), any(), any())).thenReturn(loan);
        actor("disb1", "DISBURSEMENT_HEAD");

        flow.disbursementDecision(1L, true, "UTR999", "released");

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.ACTIVE);
    }

    /** A failed transfer goes back to the Disbursement Head, who owns the release. */
    @Test
    void retryReturnsAFailedTransferToTheDisbursementHead() {
        LoanApplication app = appAt(ApplicationStatus.DISBURSEMENT_FAILED);
        actor("disb1", "DISBURSEMENT_HEAD");
        flow.retryDisbursement(1L);

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.DISBURSEMENT_PENDING);
    }

    @Test
    void assignRejectsNonActiveOrNonExecutiveAssignee() {
        appAt(ApplicationStatus.KYC_PENDING);
        when(staffDirectory.isActiveWithRole(55L, "CREDIT_EXECUTIVE")).thenReturn(false);
        actor("head1", "CREDIT_HEAD");
        assertThatThrownBy(() -> flow.assignExecutive(1L, 55L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("active Credit Executive");
    }

    @Test
    void creditHeadCanAssignTheFileToThemselves() {
        LoanApplication app = appAt(ApplicationStatus.KYC_PENDING);
        actor("12", "CREDIT_HEAD");

        flow.assignExecutive(1L, 12L);

        assertThat(app.getAssignedExecutiveId()).isEqualTo(12L);
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.CREDIT_EXEC_PENDING);
    }

    @Test
    void creditHeadCanReassignWithoutClearingPendingNotes() {
        LoanApplication app = appAt(ApplicationStatus.CREDIT_EXEC_PENDING);
        app.setAssignedExecutiveId(55L);
        app.setPendingReason("waiting on bank statement");
        app.setMarkedPendingAt(Instant.now());
        actor("12", "CREDIT_HEAD");

        flow.assignExecutive(1L, 56L);

        assertThat(app.getAssignedExecutiveId()).isEqualTo(56L);
        assertThat(app.getPendingReason()).isEqualTo("waiting on bank statement");
        assertThat(events).anySatisfy(e -> {
            assertThat(e.getAction()).isEqualTo("REASSIGN");
            assertThat(e.getNotes()).contains("previousExecutiveId=55", "newExecutiveId=56");
        });
    }

    @Test
    void creditExecutiveOnlySeesAndActsOnAssignedFiles() {
        LoanApplication mine = appAt(ApplicationStatus.CREDIT_EXEC_PENDING);
        mine.setAssignedExecutiveId(55L);
        LoanApplication somebodyElses = new LoanApplication();
        somebodyElses.setId(2L);
        somebodyElses.setStatus(ApplicationStatus.CREDIT_EXEC_PENDING);
        somebodyElses.setAssignedExecutiveId(56L);
        when(applicationRepository.findById(2L)).thenReturn(Optional.of(somebodyElses));
        // byStatus is spec-based (V53's date filter), so the executive-scoping filter is applied
        // inside the Specification rather than via a named finder — stub the generic findAll.
        when(applicationRepository.findAll(
                any(org.springframework.data.jpa.domain.Specification.class),
                any(org.springframework.data.domain.Sort.class)))
                .thenReturn(List.of(mine));
        actor("55", "CREDIT_EXECUTIVE");

        assertThat(flow.byStatus(ApplicationStatus.CREDIT_EXEC_PENDING)).containsExactly(mine);
        assertThatThrownBy(() -> flow.markPending(2L, "not mine"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("assigned");
    }

    @Test
    void createDraft_stampsCreatedAt() {
        actor("7", "BORROWER");
        LoanApplication saved = flow.createDraft(7L);
        assertThat(saved.getCreatedAt()).isNotNull().isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void byStatus_sortsNewestFirst() {
        actor("1", "ADMIN");
        ArgumentCaptor<org.springframework.data.domain.Sort> sortCaptor =
                ArgumentCaptor.forClass(org.springframework.data.domain.Sort.class);
        when(applicationRepository.findAll(
                any(org.springframework.data.jpa.domain.Specification.class), sortCaptor.capture()))
                .thenReturn(List.of());

        flow.byStatus(ApplicationStatus.KYC_PENDING);

        org.springframework.data.domain.Sort.Order order = sortCaptor.getValue().iterator().next();
        assertThat(order.getProperty()).isEqualTo("createdAt");
        assertThat(order.getDirection()).isEqualTo(org.springframework.data.domain.Sort.Direction.DESC);
    }

    @Test
    void illegalTransitionIsRejected() {
        LoanApplication app = appAt(ApplicationStatus.DRAFT);
        app.setAssignedExecutiveId(55L);
        actor("55", "CREDIT_EXECUTIVE");
        assertThatThrownBy(() -> flow.sanction(1L, 2_000_000L, 20, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    void wrongRoleIsForbidden() {
        appAt(ApplicationStatus.DRAFT);
        actor("head1", "CREDIT_HEAD"); // submitKyc requires BORROWER
        assertThatThrownBy(() -> flow.submitKyc(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("requires role BORROWER");
    }

    @Test
    void kycApprovalAdvancesFromPending() {
        LoanApplication app = appAt(ApplicationStatus.KYC_PENDING);
        actor("exec1", "CREDIT_EXECUTIVE"); // the credit team absorbed KYC approval (V45)
        flow.decideKyc(1L, true, "verified");
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.KYC_APPROVED);
    }

    @Test
    void closeForLoanClosesActiveApplication() {
        LoanApplication app = appAt(ApplicationStatus.ACTIVE);
        when(applicationRepository.findByLoanId(99L)).thenReturn(Optional.of(app));
        actor("acct1", "ACCOUNTANT");

        flow.closeForLoan(99L);

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.CLOSED);
    }

    @Test
    void closeForLoanIgnoresNonActiveApplication() {
        LoanApplication app = appAt(ApplicationStatus.DISBURSEMENT_PENDING);
        when(applicationRepository.findByLoanId(99L)).thenReturn(Optional.of(app));

        flow.closeForLoan(99L);

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.DISBURSEMENT_PENDING);
    }

    @Test
    void closeForLoanNoOpWhenNoApplicationForLoan() {
        when(applicationRepository.findByLoanId(77L)).thenReturn(Optional.empty());
        flow.closeForLoan(77L); // must not throw
    }

    // ---- returning-borrower reborrow + review gate ---------------------------------

    /** A returning borrower in good standing (no past delinquency) is pre-approved. */
    @Test
    void reborrowCleanHistoryIsPreApproved() {
        actor("7", "BORROWER");
        LoanApplication prior = priorApp(); // CLOSED, salary day 30
        when(applicationRepository.findByCustomerId(7L)).thenReturn(List.of(prior));
        when(profileRepository.findByApplicationId(10L)).thenReturn(Optional.of(priorProfile()));
        Loan closed = loanAt(50L, LoanStatus.CLOSED, LocalDate.now().minusDays(5));
        when(loanRepository.findByCustomerId(7L)).thenReturn(List.of(closed));
        when(paymentRepository.findByLoanId(50L)).thenReturn(List.of()); // paid on time

        LoanApplication result = flow.reborrow();

        assertThat(result.getStatus()).isEqualTo(ApplicationStatus.PRE_APPROVED);
        // 25%-of-salary model: priorProfile() salary is 6_000_000 paise (₹60,000).
        // 6_000_000 * 0.25 = 1_500_000, already a ₹100 (10_000-paise) multiple, well under the
        // ₹10,00,000 (100_000_000-paise) instant cap -> eligible limit = 1_500_000.
        assertThat(result.getEligibleLimit()).isEqualTo(1_500_000L);
        assertThat(result.getSalaryCreditDay()).isEqualTo(30);
    }

    /**
     * A reborrow clones the prior profile's credit-brief facts, so it must clone the CREDIT_BRIEF
     * document with them — pointing at the same S3 object, no re-render. Leaving the document behind
     * put every reborrow in the facts-without-PDF state that makes CreditBriefService try to
     * regenerate on read, which 500s any read-only caller.
     */
    @Test
    void reborrowCarriesTheCreditBriefDocumentOntoTheNewApplication() {
        actor("7", "BORROWER");
        LoanApplication prior = priorApp();
        when(applicationRepository.findByCustomerId(7L)).thenReturn(List.of(prior));
        when(profileRepository.findByApplicationId(10L)).thenReturn(Optional.of(priorProfile()));
        Loan closed = loanAt(50L, LoanStatus.CLOSED, LocalDate.now().minusDays(5));
        when(loanRepository.findByCustomerId(7L)).thenReturn(List.of(closed));
        when(paymentRepository.findByLoanId(50L)).thenReturn(List.of());
        ApplicationDocument brief = new ApplicationDocument();
        brief.setApplicationId(10L);
        brief.setDocType(CreditBriefService.DOC_TYPE);
        brief.setFileName("credit-brief.pdf");
        brief.setContentType("application/pdf");
        brief.setSizeBytes(4096L);
        brief.setS3ObjectKey("applications/10/credit_brief/credit-brief.pdf");
        when(documentRepository.findFirstByApplicationIdAndDocTypeOrderByIdDesc(
                10L, CreditBriefService.DOC_TYPE)).thenReturn(Optional.of(brief));
        // The new draft has no brief of its own yet (the copy is idempotent on that check).
        when(documentRepository.findFirstByApplicationIdAndDocTypeOrderByIdDesc(
                99L, CreditBriefService.DOC_TYPE)).thenReturn(Optional.empty());
        // The real repository assigns the identity on insert; the shared stub echoes the argument back.
        when(applicationRepository.save(any())).thenAnswer(i -> {
            LoanApplication saved = i.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(99L);
            }
            return saved;
        });

        flow.reborrow();

        ArgumentCaptor<ApplicationDocument> captor = ArgumentCaptor.forClass(ApplicationDocument.class);
        verify(documentRepository).save(captor.capture());
        ApplicationDocument copy = captor.getValue();
        assertThat(copy.getDocType()).isEqualTo(CreditBriefService.DOC_TYPE);
        // Same object in S3 — the copy is a pointer, not a re-render.
        assertThat(copy.getS3ObjectKey()).isEqualTo(brief.getS3ObjectKey());
        assertThat(copy.getSizeBytes()).isEqualTo(4096L);
        assertThat(copy.getData()).isNull();
    }

    /**
     * The Phase-4 engine rule (V47, decision 47): being a few days late is not disqualifying — the
     * product already prices those days as a penalty. Exactly at the five-day tolerance still passes.
     */
    @Test
    void reborrowForgivesARepaymentWithinTheLateTolerance() {
        actor("7", "BORROWER");
        when(applicationRepository.findByCustomerId(7L)).thenReturn(List.of(priorApp()));
        when(profileRepository.findByApplicationId(10L)).thenReturn(Optional.of(priorProfile()));
        Loan closed = loanAt(50L, LoanStatus.CLOSED, LocalDate.now().minusDays(20));
        when(loanRepository.findByCustomerId(7L)).thenReturn(List.of(closed));
        when(paymentRepository.findByLoanId(50L)).thenReturn(List.of(
                paidOn(50L, closed.getDueDate().plusDays(ApplicationFlowService.LATE_REPAYMENT_TOLERANCE_DAYS))));

        assertThat(flow.reborrow().getStatus()).isEqualTo(ApplicationStatus.PRE_APPROVED);
    }

    /** One day past the tolerance, and the borrower is auto-rejected — there is no review queue. */
    @Test
    void reborrowRejectsARepaymentBeyondTheLateTolerance() {
        actor("7", "BORROWER");
        when(applicationRepository.findByCustomerId(7L)).thenReturn(List.of(priorApp()));
        when(profileRepository.findByApplicationId(10L)).thenReturn(Optional.of(priorProfile()));
        Loan closed = loanAt(50L, LoanStatus.CLOSED, LocalDate.now().minusDays(20));
        when(loanRepository.findByCustomerId(7L)).thenReturn(List.of(closed));
        when(paymentRepository.findByLoanId(50L)).thenReturn(List.of(
                paidOn(50L, closed.getDueDate().plusDays(ApplicationFlowService.LATE_REPAYMENT_TOLERANCE_DAYS + 1))));

        assertThat(flow.reborrow().getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        ArgumentCaptor<ApplicationRejection> captor = ArgumentCaptor.forClass(ApplicationRejection.class);
        verify(rejectionRepository).save(captor.capture());
        assertThat(captor.getValue().getReasonCode()).isEqualTo(ApplicationRejection.PAST_DELINQUENCY);
    }

    /** Never fully repaid — still owed past the due date — is disqualifying regardless of days. */
    @Test
    void reborrowRejectsALoanThatWasNeverFullyRepaid() {
        actor("7", "BORROWER");
        when(applicationRepository.findByCustomerId(7L)).thenReturn(List.of(priorApp()));
        when(profileRepository.findByApplicationId(10L)).thenReturn(Optional.of(priorProfile()));
        when(loanRepository.findByCustomerId(7L)).thenReturn(List.of(
                loanAt(50L, LoanStatus.IN_COLLECTIONS, LocalDate.now().minusDays(3))));

        assertThat(flow.reborrow().getStatus()).isEqualTo(ApplicationStatus.REJECTED);
    }

    private static Payment paidOn(Long loanId, LocalDate on) {
        Payment p = new Payment();
        p.setLoanId(loanId);
        p.setStatus(PaymentStatus.VERIFIED);
        p.setPaidOn(on);
        return p;
    }

    /** Credit score does NOT gate reborrow: a clean-history borrower with a low rating is still pre-approved. */
    @Test
    void reborrowCleanHistoryLowStarStillPreApproved() {
        actor("7", "BORROWER");
        when(applicationRepository.findByCustomerId(7L)).thenReturn(List.of(priorApp()));
        CustomerProfile lowStar = priorProfile();
        lowStar.setCreditStarRating(new BigDecimal("3.5")); // below the old 4.0★ threshold — no longer gates
        when(profileRepository.findByApplicationId(10L)).thenReturn(Optional.of(lowStar));
        Loan closed = loanAt(50L, LoanStatus.CLOSED, LocalDate.now().minusDays(5));
        when(loanRepository.findByCustomerId(7L)).thenReturn(List.of(closed));
        when(paymentRepository.findByLoanId(50L)).thenReturn(List.of()); // paid on time

        assertThat(flow.reborrow().getStatus()).isEqualTo(ApplicationStatus.PRE_APPROVED);
    }

    /** A pre-loan application still in the pipeline (e.g. awaiting KYC) blocks a fresh reborrow. */
    @Test
    void reborrowBlockedWhileApplicationInPipeline() {
        actor("7", "BORROWER");
        LoanApplication pending = new LoanApplication();
        pending.setId(10L);
        pending.setCustomerId(7L);
        pending.setStatus(ApplicationStatus.KYC_PENDING); // not yet a loan
        when(applicationRepository.findByCustomerId(7L)).thenReturn(List.of(pending));

        assertThatThrownBy(() -> flow.reborrow())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("in-progress application");
    }

    /**
     * One advance at a time: a borrower holding a live (ACTIVE) loan is now <b>blocked</b> from a fresh
     * reborrow — they must fully repay first (ACTIVE_LOAN). Checked before any profile lookup.
     */
    @Test
    void reborrowBlockedWhileLoanActive() {
        actor("7", "BORROWER");
        LoanApplication activeApp = new LoanApplication();
        activeApp.setId(10L);
        activeApp.setCustomerId(7L);
        activeApp.setStatus(ApplicationStatus.ACTIVE); // a live loan blocks a fresh reborrow
        when(applicationRepository.findByCustomerId(7L)).thenReturn(List.of(activeApp));

        assertThatThrownBy(() -> flow.reborrow())
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("ACTIVE_LOAN");
    }

    @Test
    void reborrowWithoutPriorProfileFails() {
        actor("7", "BORROWER");
        LoanApplication prior = new LoanApplication();
        prior.setId(10L);
        prior.setCustomerId(7L);
        prior.setStatus(ApplicationStatus.CANCELLED); // terminal, but no profile
        when(applicationRepository.findByCustomerId(7L)).thenReturn(List.of(prior));
        when(profileRepository.findByApplicationId(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> flow.reborrow())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No previous application");
    }

    @Test
    void sanctionRequiresACreditRole() {
        LoanApplication app = appAt(ApplicationStatus.CREDIT_EXEC_PENDING);
        app.setAssignedExecutiveId(55L);
        actor("acct1", "ACCOUNTANT");
        assertThatThrownBy(() -> flow.sanction(1L, 2_000_000L, 20, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("CREDIT_EXECUTIVE or CREDIT_HEAD");
    }

    /** Pre-approved borrower applying skips the credit gates → straight to disbursement (fast-track). */
    @Test
    void applyFromPreApprovedRoutesStraightToDisbursement() {
        LoanApplication app = appAt(ApplicationStatus.PRE_APPROVED);
        actor("7", "BORROWER");
        flow.apply(1L, 1_000_000L, "medical", 1_500_000L, 30);
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.DISBURSEMENT_PENDING);
        assertThat(app.getAssignedExecutiveId()).isNull(); // the fast-track discriminator
    }

    /** Reborrow reuse: applying without a salary day keeps the one carried onto the application. */
    @Test
    void applyWithoutSalaryDayKeepsCarriedDay() {
        LoanApplication app = appAt(ApplicationStatus.PRE_APPROVED);
        app.setSalaryCreditDay(15); // carried over by reborrow() from the borrower's first loan
        actor("7", "BORROWER");
        flow.apply(1L, 1_000_000L, "medical", 1_500_000L, null);
        assertThat(app.getSalaryCreditDay()).isEqualTo(15); // not wiped by the null
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.DISBURSEMENT_PENDING);
    }

    // ---- audit trail actor-name enrichment + status counts ------------------------

    /** A BORROWER-role event resolves to the customer profile's full name. */
    @Test
    void eventViewsResolvesBorrowerNameFromProfile() {
        appAt(ApplicationStatus.KYC_PENDING); // customerId 7
        CustomerProfile p = new CustomerProfile();
        p.setApplicationId(1L);
        p.setFullName("Rakesh Kumar");
        when(profileRepository.findByApplicationId(1L)).thenReturn(Optional.of(p));
        events.add(event("7", "BORROWER"));

        List<EventView> views = flow.eventViews(1L);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).actorName()).isEqualTo("Rakesh Kumar");
    }

    /** A staff-role event resolves to the staff directory's name. */
    @Test
    void eventViewsResolvesStaffNameFromDirectory() {
        appAt(ApplicationStatus.KYC_PENDING);
        when(staffDirectory.findStaff(42L))
                .thenReturn(Optional.of(new StaffSummary(42L, "Priya Sharma", "CREDIT_EXECUTIVE", true)));
        events.add(event("42", "CREDIT_EXECUTIVE"));

        List<EventView> views = flow.eventViews(1L);

        assertThat(views.get(0).actorName()).isEqualTo("Priya Sharma");
    }

    /** Unresolvable actors — unknown staff id or a non-numeric id — yield a null name, never a throw. */
    @Test
    void eventViewsReturnsNullNameWhenUnresolvable() {
        appAt(ApplicationStatus.KYC_PENDING);
        when(staffDirectory.findStaff(any())).thenReturn(Optional.empty());
        events.add(event("99", "CREDIT_HEAD"));          // unknown staff id → null
        events.add(event("not-a-number", "CREDIT_HEAD")); // non-numeric id → null, no throw

        List<EventView> views = flow.eventViews(1L);

        assertThat(views).hasSize(2);
        assertThat(views.get(0).actorName()).isNull();
        assertThat(views.get(1).actorName()).isNull();
    }

    /** No profile row for the app/customer → a borrower event's name is null (no throw). */
    @Test
    void eventViewsBorrowerNameNullWhenNoProfile() {
        appAt(ApplicationStatus.KYC_PENDING);
        events.add(event("7", "BORROWER"));

        List<EventView> views = flow.eventViews(1L);

        assertThat(views.get(0).actorName()).isNull();
    }

    /** countsByStatus maps the repository projection and omits statuses with no rows. */
    @Test
    void countsByStatusMapsProjectionAndOmitsAbsentStatuses() {
        when(applicationRepository.countGroupByStatus()).thenReturn(List.of(
                statusCount(ApplicationStatus.KYC_PENDING, 3L),
                statusCount(ApplicationStatus.ACTIVE, 5L)));

        Map<ApplicationStatus, Long> counts = flow.countsByStatus();

        assertThat(counts).hasSize(2)
                .containsEntry(ApplicationStatus.KYC_PENDING, 3L)
                .containsEntry(ApplicationStatus.ACTIVE, 5L)
                .doesNotContainKey(ApplicationStatus.CLOSED);
    }

    private ApplicationEvent event(String actorId, String actorRole) {
        ApplicationEvent e = new ApplicationEvent();
        e.setApplicationId(1L);
        e.setActorId(actorId);
        e.setActorRole(actorRole);
        e.setToStatus(ApplicationStatus.KYC_PENDING);
        e.setAt(Instant.now());
        return e;
    }

    private LoanApplicationRepository.StatusCount statusCount(ApplicationStatus status, Long count) {
        return new LoanApplicationRepository.StatusCount() {
            @Override
            public ApplicationStatus getStatus() {
                return status;
            }

            @Override
            public Long getCount() {
                return count;
            }
        };
    }

    /**
     * A clean returning borrower whose prior file carried a credit sanction is re-sanctioned on the
     * spot (V47, decisions 45/46) — the ceiling, the account and the evidence come with them, so the
     * only thing they re-walk is the short offer journey. The repayment date is recomputed, never
     * copied: the prior one is in the past by definition.
     */
    @Test
    void reapplyCarriesTheSanctionEvidenceAndAccountForward() {
        actor("7", "BORROWER");
        LoanApplication prior = priorApp();
        prior.setSanctionedAmountPaise(2_500_000L);
        prior.setApprovedRepaymentDate(LocalDate.now().minusDays(15)); // long past
        prior.setDisbursalAccountNumber("123456789012");
        prior.setDisbursalIfsc("HDFC0001234");
        prior.setDisbursalAccountVerified(Boolean.TRUE);
        when(applicationRepository.findByCustomerId(7L)).thenReturn(List.of(prior));
        when(profileRepository.findByApplicationId(10L)).thenReturn(Optional.of(priorProfile()));
        Loan closed = loanAt(50L, LoanStatus.CLOSED, LocalDate.now().minusDays(15));
        when(loanRepository.findByCustomerId(7L)).thenReturn(List.of(closed));
        when(paymentRepository.findByLoanId(50L)).thenReturn(List.of());
        ApplicationVerification aadhaar = new ApplicationVerification();
        aadhaar.setApplicationId(10L);
        aadhaar.setCheckType(ApplicationVerificationService.AADHAAR);
        aadhaar.setStatus(ApplicationVerificationService.PASS);
        when(verificationRepository.findByApplicationIdOrderByIdAsc(10L)).thenReturn(List.of(aadhaar));

        LoanApplication out = flow.reborrow();

        assertThat(out.getStatus()).isEqualTo(ApplicationStatus.SANCTIONED);
        assertThat(out.getReappliedFrom()).isEqualTo(10L);
        assertThat(out.getSanctionedAmountPaise()).isEqualTo(2_500_000L);
        // The ceiling carries; the draw-down does not — they pick an amount again on screen one.
        assertThat(out.getAmountRequested()).isNull();
        assertThat(out.getApprovedRepaymentDate()).isAfter(LocalDate.now());
        assertThat(out.getDisbursalAccountNumber()).isEqualTo("123456789012");
        // …but they still confirm the destination, so it starts unchanged and unconfirmed.
        assertThat(out.getDisbursalAccountChanged()).isFalse();
        assertThat(out.getDisbursalConfirmedAt()).isNull();
        // The Aadhaar evidence is copied onto the new file so the journey can skip DigiLocker.
        ArgumentCaptor<ApplicationVerification> copied = ArgumentCaptor.forClass(ApplicationVerification.class);
        verify(verificationRepository).save(copied.capture());
        assertThat(copied.getValue().getCheckType()).isEqualTo(ApplicationVerificationService.AADHAAR);
        assertThat(copied.getValue().getMessage()).contains("Carried over from application 10");
    }

    private LoanApplication priorApp() {
        LoanApplication prior = new LoanApplication();
        prior.setId(10L);
        prior.setCustomerId(7L);
        prior.setStatus(ApplicationStatus.CLOSED); // terminal — reborrow allowed
        prior.setSalaryCreditDay(30);
        return prior;
    }

    private CustomerProfile priorProfile() {
        CustomerProfile p = new CustomerProfile();
        p.setApplicationId(10L);
        p.setMonthlySalaryPaise(6_000_000L); // ₹60,000
        p.setCreditStarRating(new BigDecimal("4.0")); // good standing → pre-approvable
        return p;
    }

    private Loan loanAt(Long id, LoanStatus status, LocalDate dueDate) {
        Loan loan = new Loan();
        loan.setId(id);
        loan.setCustomerId(7L);
        loan.setStatus(status);
        loan.setDueDate(dueDate);
        return loan;
    }
}
