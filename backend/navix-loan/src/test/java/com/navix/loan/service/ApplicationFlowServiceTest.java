package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.staff.StaffDirectory;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.entity.ApplicationEvent;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicationEventRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    private ApplicationFlowService flow;
    private final List<ApplicationEvent> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        flow = new ApplicationFlowService(applicationRepository, eventRepository,
                new EligibilityService(), loanService, staffDirectory);
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
        app.setApplicantId(7L);
        app.setAmountRequested(1_000_000L);
        app.setStatus(status);
        lenient().when(applicationRepository.findById(1L)).thenReturn(Optional.of(app));
        return app;
    }

    @Test
    void creditToActiveHappyPathWithDistinctActors() {
        LoanApplication app = appAt(ApplicationStatus.KYC_APPROVED);
        Loan loan = new Loan();
        loan.setId(99L);
        when(loanService.disburse(any(), any(), any())).thenReturn(loan);

        actor("head1", "CREDIT_HEAD");
        flow.assignExecutive(1L, 55L);
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.CREDIT_EXEC_PENDING);

        actor("exec1", "CREDIT_EXECUTIVE");
        flow.execDecision(1L, true, "looks good");
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.CREDIT_HEAD_PENDING);

        actor("head1", "CREDIT_HEAD");
        flow.headDecision(1L, true, null, "approved");
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.DISBURSEMENT_PENDING);

        // No transaction id at disbursement → hand off to the accountant.
        actor("disb1", "DISBURSEMENT_HEAD");
        flow.disbursementDecision(1L, true, null, null);
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.ACCOUNTANT_PENDING);

        actor("acct1", "ACCOUNTANT");
        flow.accountantValidate(1L, true, "UTR123", "UTR ok");
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.ACTIVE);
        assertThat(app.getLoanId()).isEqualTo(99L);
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

    @Test
    void disbursementWithoutTxnRefGoesToAccountant() {
        LoanApplication app = appAt(ApplicationStatus.DISBURSEMENT_PENDING);
        actor("disb1", "DISBURSEMENT_HEAD");
        flow.disbursementDecision(1L, true, "   ", null); // blank txn id counts as none

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.ACCOUNTANT_PENDING);
    }

    @Test
    void separationOfDutiesBlocksHeadEqualsExec() {
        appAt(ApplicationStatus.KYC_APPROVED);
        actor("same", "CREDIT_HEAD");
        flow.assignExecutive(1L, 55L);
        actor("same", "CREDIT_EXECUTIVE");
        flow.execDecision(1L, true, "recommend");

        actor("same", "CREDIT_HEAD"); // same human as the recommender
        assertThatThrownBy(() -> flow.headDecision(1L, true, null, "approve"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Credit Executive cannot also give final approval");
    }

    @Test
    void assignRejectsNonActiveOrNonExecutiveAssignee() {
        appAt(ApplicationStatus.KYC_APPROVED);
        when(staffDirectory.isActiveWithRole(55L, "CREDIT_EXECUTIVE")).thenReturn(false);
        actor("head1", "CREDIT_HEAD");
        assertThatThrownBy(() -> flow.assignExecutive(1L, 55L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("active Credit Executive");
    }

    @Test
    void illegalTransitionIsRejected() {
        appAt(ApplicationStatus.DRAFT);
        actor("exec1", "CREDIT_EXECUTIVE");
        assertThatThrownBy(() -> flow.execDecision(1L, true, null))
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
        actor("kyc1", "KYC_APPROVER");
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
}
