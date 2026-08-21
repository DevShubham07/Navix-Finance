package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.domain.LoanStatus;
import com.navix.loan.domain.PaymentStatus;
import com.navix.loan.dto.LoanRegisterDtos.LoanRegisterRow;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.repository.PaymentRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LoanRegisterServiceTest {

    @Mock private LoanRepository loanRepository;
    @Mock private LoanApplicationRepository applicationRepository;
    @Mock private CustomerProfileRepository profileRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private com.navix.common.collections.SettlementDirectory settlementDirectory;
    @Mock private com.navix.common.collections.CollectionCaseDirectory collectionCaseDirectory;
    @Mock private com.navix.common.staff.StaffDirectory staffDirectory;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Mock private ApplicationFlowService applicationFlowService;
    @Mock private DsaCommissionService dsaCommissionService;
    @Mock private com.navix.common.storage.DocumentStoragePort storage;

    private LoanMath loanMath;
    private RepaymentService repaymentService;
    private LoanRegisterService service;

    @BeforeEach
    void setUp() {
        loanMath = new LoanMath();
        repaymentService = new RepaymentService(paymentRepository, loanRepository, loanMath,
                applicationFlowService, settlementDirectory, eventPublisher, dsaCommissionService, storage);
        service = new LoanRegisterService(loanRepository, applicationRepository, profileRepository,
                repaymentService, loanMath, collectionCaseDirectory, staffDirectory);
        lenient().when(settlementDirectory.approvedSettlementAmount(any())).thenReturn(java.util.Optional.empty());
        lenient().when(paymentRepository.sumAmountByLoanIdInAndStatus(anyList(), any())).thenReturn(List.of());
        lenient().when(collectionCaseDirectory.assignedOfficerByLoanId(anyList())).thenReturn(java.util.Map.of());
    }

    @AfterEach
    void clearActor() {
        ActorContext.clear();
    }

    private Loan loan(long id, long customerId, LocalDate disbursedOn, LocalDate dueDate, LoanStatus status) {
        Loan l = new Loan();
        l.setId(id);
        l.setCustomerId(customerId);
        l.setPrincipal(1_000_000L);
        l.setNetDisbursed(882_000L);
        l.setTotalRepayable(1_300_000L);
        l.setDisbursedOn(disbursedOn);
        l.setDueDate(dueDate);
        l.setStatus(status);
        return l;
    }

    private LoanApplication app(long id, long loanId, long customerId) {
        LoanApplication a = new LoanApplication();
        a.setId(id);
        a.setLoanId(loanId);
        a.setCustomerId(customerId);
        a.setSanctionedAmountPaise(1_000_000L);
        a.setSalaryCreditDay(5);
        return a;
    }

    private CustomerProfile profile(long applicationId, String name, String pan, String mobile) {
        CustomerProfile p = new CustomerProfile();
        p.setApplicationId(applicationId);
        p.setFullName(name);
        p.setPan(pan);
        p.setMobile(mobile);
        return p;
    }

    // ---------------------------------------------------------------- role gate

    @Test
    void list_asAdmin_isAllowed() {
        ActorContext.set(new CurrentActor("1", "Admin", "ADMIN"));
        // No loans -> the service short-circuits before touching applicationRepository at all.
        when(loanRepository.findAllForRegister(null, null)).thenReturn(List.of());

        assertThat(service.list(null, null, null, null)).isEmpty();
    }

    @Test
    void list_asCollectionHead_isAllowed() {
        ActorContext.set(new CurrentActor("2", "Coll Head", "COLLECTION_HEAD"));
        when(loanRepository.findAllForRegister(null, null)).thenReturn(List.of());

        assertThat(service.list(null, null, null, null)).isEmpty();
    }

    @Test
    void list_asCreditExecutive_isRejected() {
        ActorContext.set(new CurrentActor("3", "Exec", "CREDIT_EXECUTIVE"));

        assertThatThrownBy(() -> service.list(null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("COLLECTION_HEAD");
    }

    @Test
    void list_asBorrower_isRejected() {
        ActorContext.set(new CurrentActor("9000001", "Asha", "BORROWER"));

        assertThatThrownBy(() -> service.list(null, null, null, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void list_asDsa_isRejectedByTheFirewallBeforeTheRoleCheck() {
        ActorContext.set(new CurrentActor("4", "Agent", "DSA"));

        assertThatThrownBy(() -> service.list(null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("DSA");
    }

    // ---------------------------------------------------------------- filtering

    @Test
    void list_filtersByEffectiveStatus() {
        ActorContext.set(new CurrentActor("1", "Admin", "ADMIN"));
        // ACTIVE but past due -> effective OVERDUE.
        Loan overdue = loan(1L, 9000001L, LocalDate.now().minusDays(60), LocalDate.now().minusDays(10), LoanStatus.ACTIVE);
        Loan active = loan(2L, 9000002L, LocalDate.now().minusDays(5), LocalDate.now().plusDays(20), LoanStatus.ACTIVE);
        when(loanRepository.findAllForRegister(null, null)).thenReturn(List.of(overdue, active));
        when(applicationRepository.findByLoanIdIn(anyList())).thenReturn(List.of());

        List<LoanRegisterRow> overdueRows = service.list("OVERDUE", null, null, null);
        assertThat(overdueRows).extracting(LoanRegisterRow::loanId).containsExactly(1L);

        List<LoanRegisterRow> activeRows = service.list("ACTIVE", null, null, null);
        assertThat(activeRows).extracting(LoanRegisterRow::loanId).containsExactly(2L);
    }

    @Test
    void list_filtersByDateRange_delegatedToTheRepository() {
        ActorContext.set(new CurrentActor("1", "Admin", "ADMIN"));
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 1, 31);
        when(loanRepository.findAllForRegister(from, to)).thenReturn(List.of());

        service.list(null, null, from, to);

        org.mockito.Mockito.verify(loanRepository).findAllForRegister(from, to);
    }

    @Test
    void list_filtersByFreeTextSearch_nameOrPanOrMobileOrLoanId() {
        ActorContext.set(new CurrentActor("1", "Admin", "ADMIN"));
        // Loan ids deliberately don't overlap any digit sequence in either mobile number, so the
        // loan-id assertion below can't accidentally pass via a mobile-number match.
        Loan l1 = loan(501L, 9000001L, LocalDate.now().minusDays(10), LocalDate.now().plusDays(10), LoanStatus.ACTIVE);
        Loan l2 = loan(777L, 9000002L, LocalDate.now().minusDays(10), LocalDate.now().plusDays(10), LoanStatus.ACTIVE);
        LoanApplication a1 = app(101L, 501L, 9000001L);
        LoanApplication a2 = app(102L, 777L, 9000002L);
        when(loanRepository.findAllForRegister(null, null)).thenReturn(List.of(l1, l2));
        when(applicationRepository.findByLoanIdIn(anyList())).thenReturn(List.of(a1, a2));
        when(profileRepository.findByApplicationIdIn(anyList())).thenReturn(List.of(
                profile(101L, "Asha Rao", "ABCDE1234F", "9876543210"),
                profile(102L, "Bhavya Reddy", "BBBBB2222B", "9123456780")));

        assertThat(service.list(null, "asha", null, null))
                .extracting(LoanRegisterRow::loanId).containsExactly(501L);
        assertThat(service.list(null, "9123456780", null, null))
                .extracting(LoanRegisterRow::loanId).containsExactly(777L);
        assertThat(service.list(null, "777", null, null))
                .extracting(LoanRegisterRow::loanId).containsExactly(777L);
    }

    // ---------------------------------------------------------------- loan cycle

    @Test
    void list_numbersLoanCycleOneBasedPerCustomerOrderedByDisbursedOn() {
        ActorContext.set(new CurrentActor("1", "Admin", "ADMIN"));
        LocalDate base = LocalDate.now().minusDays(200);
        // Deliberately out of disbursedOn order in the input list, to prove sorting drives numbering.
        Loan third = loan(3L, 9000001L, base.plusDays(100), base.plusDays(130), LoanStatus.CLOSED);
        Loan first = loan(1L, 9000001L, base, base.plusDays(30), LoanStatus.CLOSED);
        Loan second = loan(2L, 9000001L, base.plusDays(50), base.plusDays(80), LoanStatus.CLOSED);
        when(loanRepository.findAllForRegister(null, null)).thenReturn(List.of(third, first, second));
        when(applicationRepository.findByLoanIdIn(anyList())).thenReturn(List.of());

        List<LoanRegisterRow> rows = service.list(null, null, null, null);

        assertThat(rows).filteredOn(r -> r.loanId() == 1L).extracting(LoanRegisterRow::loanCycle).containsExactly(1);
        assertThat(rows).filteredOn(r -> r.loanId() == 2L).extracting(LoanRegisterRow::loanCycle).containsExactly(2);
        assertThat(rows).filteredOn(r -> r.loanId() == 3L).extracting(LoanRegisterRow::loanCycle).containsExactly(3);
    }

    // ---------------------------------------------------------------- outstandingForAll equivalence

    /**
     * The whole point of the batched path: {@code outstandingForAll} must return the exact same
     * figure {@code outstandingAsOf} would compute one loan at a time — otherwise the register would
     * disagree with the repay page / collections.
     */
    @Test
    void outstandingForAll_agreesWithOutstandingAsOfForEachLoan() {
        Loan onTime = loan(10L, 9000001L, LocalDate.now().minusDays(10), LocalDate.now().plusDays(10), LoanStatus.ACTIVE);
        Loan overdue = loan(11L, 9000002L, LocalDate.now().minusDays(60), LocalDate.now().minusDays(15), LoanStatus.ACTIVE);
        Loan partiallyPaid = loan(12L, 9000003L, LocalDate.now().minusDays(5), LocalDate.now().plusDays(25), LoanStatus.ACTIVE);

        when(loanRepository.findById(10L)).thenReturn(java.util.Optional.of(onTime));
        when(loanRepository.findById(11L)).thenReturn(java.util.Optional.of(overdue));
        when(loanRepository.findById(12L)).thenReturn(java.util.Optional.of(partiallyPaid));
        when(paymentRepository.sumAmountByLoanIdAndStatus(10L, PaymentStatus.VERIFIED)).thenReturn(0L);
        when(paymentRepository.sumAmountByLoanIdAndStatus(11L, PaymentStatus.VERIFIED)).thenReturn(0L);
        when(paymentRepository.sumAmountByLoanIdAndStatus(12L, PaymentStatus.VERIFIED)).thenReturn(200_000L);

        long expectedOnTime = repaymentService.outstandingAsOf(10L, null);
        long expectedOverdue = repaymentService.outstandingAsOf(11L, null);
        long expectedPartial = repaymentService.outstandingAsOf(12L, null);

        // Batched path: verified sums come back through the grouped projection instead.
        when(paymentRepository.sumAmountByLoanIdInAndStatus(anyList(), any())).thenReturn(List.of(
                loanAmount(12L, 200_000L)));

        var batched = repaymentService.outstandingForAll(List.of(onTime, overdue, partiallyPaid), null);

        assertThat(batched.get(10L)).isEqualTo(expectedOnTime);
        assertThat(batched.get(11L)).isEqualTo(expectedOverdue);
        assertThat(batched.get(12L)).isEqualTo(expectedPartial);
    }

    // ---------------------------------------------------------------- collections officer

    @Test
    void list_resolvesAssignedOfficer_forALoanWithACollectionCase() {
        ActorContext.set(new CurrentActor("1", "Admin", "ADMIN"));
        Loan overdue = loan(21L, 9000001L, LocalDate.now().minusDays(60), LocalDate.now().minusDays(10), LoanStatus.ACTIVE);
        when(loanRepository.findAllForRegister(null, null)).thenReturn(List.of(overdue));
        when(applicationRepository.findByLoanIdIn(anyList())).thenReturn(List.of());
        when(collectionCaseDirectory.assignedOfficerByLoanId(anyList())).thenReturn(java.util.Map.of(21L, 77L));
        when(staffDirectory.findStaff(77L)).thenReturn(java.util.Optional.of(
                new com.navix.common.staff.StaffSummary(77L, "Priya Officer", "COLLECTION_EXECUTIVE", true)));

        List<LoanRegisterRow> rows = service.list(null, null, null, null);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).assignedOfficerId()).isEqualTo(77L);
        assertThat(rows.get(0).assignedOfficerName()).isEqualTo("Priya Officer");
    }

    @Test
    void list_leavesAssignedOfficerNull_forALoanWithNoCollectionCase() {
        ActorContext.set(new CurrentActor("1", "Admin", "ADMIN"));
        Loan onTime = loan(22L, 9000002L, LocalDate.now().minusDays(5), LocalDate.now().plusDays(20), LoanStatus.ACTIVE);
        when(loanRepository.findAllForRegister(null, null)).thenReturn(List.of(onTime));
        when(applicationRepository.findByLoanIdIn(anyList())).thenReturn(List.of());
        // No stub for loan 22 in collectionCaseDirectory's result -> absent from the map (no case).

        List<LoanRegisterRow> rows = service.list(null, null, null, null);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).assignedOfficerId()).isNull();
        assertThat(rows.get(0).assignedOfficerName()).isNull();
        org.mockito.Mockito.verifyNoInteractions(staffDirectory);
    }

    private static PaymentRepository.LoanAmount loanAmount(long loanId, long total) {
        return new PaymentRepository.LoanAmount() {
            @Override
            public Long getLoanId() {
                return loanId;
            }

            @Override
            public Long getTotal() {
                return total;
            }
        };
    }
}
