package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.loan.LoanDirectory;
import com.navix.common.loan.LoanSummary;
import com.navix.loan.domain.LoanStatus;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.repository.LoanRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LoanDirectoryAdapterTest {

    @Mock
    private LoanRepository loanRepository;
    @Mock
    private LoanApplicationRepository applicationRepository;
    @Mock
    private CustomerProfileRepository profileRepository;
    @Mock
    private RepaymentService repaymentService;

    private LoanDirectoryAdapter adapter;

    /**
     * The service reasons in IST (the ledger's calendar), so the fixtures must too. Anchoring them
     * to the JVM default instead made this suite pass in India and fail on a UTC CI runner every
     * evening after 18:30 IST — the two clocks are on different dates in that window, which is the
     * exact drift the IST switch exists to remove.
     */
    private static final java.time.ZoneId IST = java.time.ZoneId.of("Asia/Kolkata");

    private static LocalDate today() {
        return LocalDate.now(IST);
    }

    @BeforeEach
    void setUp() {
        adapter = new LoanDirectoryAdapter(loanRepository, applicationRepository, profileRepository,
                repaymentService);
    }

    private Loan loan(long id, LoanStatus status) {
        Loan loan = new Loan();
        loan.setId(id);
        loan.setCustomerId(7L);
        loan.setPrincipal(800_000L);
        loan.setProcessingFee(80_000L);
        loan.setGst(14_400L);
        loan.setNetDisbursed(705_600L);
        loan.setTotalRepayable(1_040_000L);
        loan.setOutstanding(1_040_000L);
        loan.setDisbursedOn(today().minusDays(30));
        loan.setDueDate(today());
        loan.setStatus(status);
        return loan;
    }

    @Test
    void findLoanResolvesBorrowerAndShowsFullPan() {
        LoanApplication app = new LoanApplication();
        app.setId(1L);
        app.setCustomerId(7L);
        app.setLoanId(2L);
        CustomerProfile profile = new CustomerProfile();
        profile.setApplicationId(1L);
        profile.setFullName("Asha Verma");
        profile.setPan("ABCDE1234F");
        profile.setEmployer("Acme Corp");
        profile.setEmploymentStatus("SALARIED");
        profile.setMonthlySalaryPaise(3_200_000L);
        profile.setSalaryBank("HDFC");

        when(loanRepository.findById(2L)).thenReturn(Optional.of(loan(2L, LoanStatus.ACTIVE)));
        when(applicationRepository.findByLoanId(2L)).thenReturn(Optional.of(app));
        when(profileRepository.findByApplicationId(1L)).thenReturn(Optional.of(profile));
        // Outstanding is now the compute-on-read penalty/prepayment-aware balance.
        when(repaymentService.outstandingAsOf(2L, null)).thenReturn(1_040_000L);

        LoanSummary s = adapter.findLoan(2L).orElseThrow();

        assertThat(s.loanId()).isEqualTo(2L);
        assertThat(s.applicationId()).isEqualTo(1L);
        assertThat(s.status()).isEqualTo("ACTIVE");
        assertThat(s.principalPaise()).isEqualTo(800_000L);
        assertThat(s.netDisbursedPaise()).isEqualTo(705_600L);
        assertThat(s.outstandingPaise()).isEqualTo(1_040_000L);
        assertThat(s.borrowerName()).isEqualTo("Asha Verma");
        assertThat(s.panMasked()).isEqualTo("ABCDE1234F"); // collections now sees the full PAN
        assertThat(s.employer()).isEqualTo("Acme Corp");
    }

    @Test
    void findLoanWithNoApplicationStillReturnsLoanFigures() {
        when(loanRepository.findById(2L)).thenReturn(Optional.of(loan(2L, LoanStatus.OVERDUE)));
        when(applicationRepository.findByLoanId(2L)).thenReturn(Optional.empty());

        LoanSummary s = adapter.findLoan(2L).orElseThrow();

        assertThat(s.borrowerName()).isNull();
        assertThat(s.panMasked()).isNull();
        assertThat(s.applicationId()).isNull();
        assertThat(s.principalPaise()).isEqualTo(800_000L);
        assertThat(s.status()).isEqualTo("OVERDUE");
    }

    @Test
    void findLoanMissingReturnsEmpty() {
        when(loanRepository.findById(99L)).thenReturn(Optional.empty());

        assertThat(adapter.findLoan(99L)).isEmpty();
    }

    @Test
    void markInCollectionsFlipsPastDueActiveLoan() {
        Loan loan = loan(2L, LoanStatus.ACTIVE);
        loan.setDueDate(today().minusDays(1));
        when(loanRepository.findById(2L)).thenReturn(Optional.of(loan));

        adapter.markInCollections(2L);

        assertThat(loan.getStatus()).isEqualTo(LoanStatus.IN_COLLECTIONS);
        verify(loanRepository).save(loan);
    }

    /**
     * A case can now be opened pre-emptively, days before the due date, to take an update from the
     * borrower. That must not brand an on-time borrower as in-collections — every overdue segment,
     * queue and dashboard count keys off this status.
     */
    @Test
    void markInCollectionsLeavesANotYetDueLoanActive() {
        Loan loan = loan(2L, LoanStatus.ACTIVE);
        loan.setDueDate(today().plusDays(5));
        when(loanRepository.findById(2L)).thenReturn(Optional.of(loan));

        adapter.markInCollections(2L);

        assertThat(loan.getStatus()).isEqualTo(LoanStatus.ACTIVE);
        verify(loanRepository, never()).save(any());
    }

    @Test
    void markInCollectionsNoOpOnClosedLoan() {
        Loan loan = loan(2L, LoanStatus.CLOSED);
        when(loanRepository.findById(2L)).thenReturn(Optional.of(loan));

        adapter.markInCollections(2L);

        assertThat(loan.getStatus()).isEqualTo(LoanStatus.CLOSED);
        verify(loanRepository, never()).save(any());
    }

    @Test
    void listCollectibleMapsTheFinder() {
        when(loanRepository.findByStatusInAndDueDateLessThanEqualOrderByDueDateAsc(any(), any()))
                .thenReturn(List.of(loan(2L, LoanStatus.ACTIVE)));
        when(applicationRepository.findByLoanIdIn(List.of(2L))).thenReturn(List.of());
        when(repaymentService.outstandingForAll(any(), any())).thenReturn(java.util.Map.of(2L, 999L));

        List<LoanSummary> result = adapter.listCollectible(today());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).loanId()).isEqualTo(2L);
    }

    @Test
    void listUpcomingMapsTheNotYetDueFinder() {
        when(loanRepository.findByStatusInAndDueDateGreaterThanOrderByDueDateAsc(any(), any()))
                .thenReturn(List.of(loan(2L, LoanStatus.ACTIVE)));
        when(applicationRepository.findByLoanIdIn(List.of(2L))).thenReturn(List.of());
        when(repaymentService.outstandingForAll(any(), any())).thenReturn(java.util.Map.of(2L, 999L));

        List<LoanSummary> result = adapter.listUpcoming(today());

        assertThat(result).singleElement().satisfies(s -> {
            assertThat(s.loanId()).isEqualTo(2L);
            assertThat(s.outstandingPaise()).isEqualTo(999L);
        });
    }

    @Test
    void listUpcomingBatchesInsteadOfResolvingPerLoan() {
        // The guard against the N+1 this method exists to avoid: "not yet due" is effectively the
        // whole live book, so the per-loan finders must never be touched however many rows come back.
        when(loanRepository.findByStatusInAndDueDateGreaterThanOrderByDueDateAsc(any(), any()))
                .thenReturn(List.of(loan(2L, LoanStatus.ACTIVE), loan(3L, LoanStatus.ACTIVE),
                        loan(4L, LoanStatus.ACTIVE)));
        LoanApplication app = new LoanApplication();
        app.setId(11L);
        app.setLoanId(2L);
        when(applicationRepository.findByLoanIdIn(List.of(2L, 3L, 4L))).thenReturn(List.of(app));
        CustomerProfile profile = new CustomerProfile();
        profile.setApplicationId(11L);
        profile.setFullName("Asha Verma");
        when(profileRepository.findByApplicationIdIn(List.of(11L))).thenReturn(List.of(profile));
        when(repaymentService.outstandingForAll(any(), any()))
                .thenReturn(java.util.Map.of(2L, 1L, 3L, 2L, 4L, 3L));

        List<LoanSummary> result = adapter.listUpcoming(today());

        assertThat(result).hasSize(3);
        assertThat(result.get(0).borrowerName()).isEqualTo("Asha Verma");
        // Loans 3 and 4 have no application, so no profile -- and still no per-row query.
        assertThat(result.get(1).borrowerName()).isNull();
        verify(applicationRepository, never()).findByLoanId(any());
        verify(profileRepository, never()).findByApplicationId(any());
        verify(repaymentService, never()).outstandingAsOf(any(), any());
    }

    @Test
    void listUpcomingSkipsTheBulkFindersWhenThereAreNoLoans() {
        when(loanRepository.findByStatusInAndDueDateGreaterThanOrderByDueDateAsc(any(), any()))
                .thenReturn(List.of());

        assertThat(adapter.listUpcoming(today())).isEmpty();
        verify(applicationRepository, never()).findByLoanIdIn(any());
    }

    /** The worklist reaches a week past today, so an officer can chase before salary day. */
    @Test
    void listCollectibleLooksAheadByThePreDueWindow() {
        LocalDate asOf = LocalDate.of(2026, 8, 29);
        when(loanRepository.findByStatusInAndDueDateLessThanEqualOrderByDueDateAsc(any(), any()))
                .thenReturn(List.of());

        adapter.listCollectible(asOf);

        ArgumentCaptor<LocalDate> horizon = ArgumentCaptor.forClass(LocalDate.class);
        verify(loanRepository)
                .findByStatusInAndDueDateLessThanEqualOrderByDueDateAsc(any(), horizon.capture());
        assertThat(horizon.getValue()).isEqualTo(asOf.plusDays(LoanDirectory.PRE_DUE_WINDOW_DAYS));
    }

    /** A loan still running to term is workable but must not be reported as a delinquency. */
    @Test
    void listCollectibleFlagsNotYetDueLoansAsPreDue() {
        Loan loan = loan(2L, LoanStatus.ACTIVE);
        loan.setDueDate(today().plusDays(3));
        when(loanRepository.findByStatusInAndDueDateLessThanEqualOrderByDueDateAsc(any(), any()))
                .thenReturn(List.of(loan));
        when(applicationRepository.findByLoanIdIn(List.of(2L))).thenReturn(List.of());
        when(repaymentService.outstandingForAll(any(), any())).thenReturn(java.util.Map.of());

        assertThat(adapter.listCollectible(today()).get(0).preDue()).isTrue();
    }
}
