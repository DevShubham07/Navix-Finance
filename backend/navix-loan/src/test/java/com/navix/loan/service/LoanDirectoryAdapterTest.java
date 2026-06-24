package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.loan.LoanSummary;
import com.navix.loan.domain.LoanStatus;
import com.navix.loan.entity.ApplicantProfile;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicantProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.repository.LoanRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    private ApplicantProfileRepository profileRepository;

    private LoanDirectoryAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new LoanDirectoryAdapter(loanRepository, applicationRepository, profileRepository);
    }

    private Loan loan(long id, LoanStatus status) {
        Loan loan = new Loan();
        loan.setId(id);
        loan.setApplicantId(7L);
        loan.setPrincipal(800_000L);
        loan.setProcessingFee(80_000L);
        loan.setGst(14_400L);
        loan.setNetDisbursed(705_600L);
        loan.setTotalRepayable(1_040_000L);
        loan.setOutstanding(1_040_000L);
        loan.setDisbursedOn(LocalDate.now().minusDays(30));
        loan.setDueDate(LocalDate.now());
        loan.setStatus(status);
        return loan;
    }

    @Test
    void findLoanResolvesBorrowerAndMasksPan() {
        LoanApplication app = new LoanApplication();
        app.setId(1L);
        app.setApplicantId(7L);
        app.setLoanId(2L);
        ApplicantProfile profile = new ApplicantProfile();
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

        LoanSummary s = adapter.findLoan(2L).orElseThrow();

        assertThat(s.loanId()).isEqualTo(2L);
        assertThat(s.applicationId()).isEqualTo(1L);
        assertThat(s.status()).isEqualTo("ACTIVE");
        assertThat(s.principalPaise()).isEqualTo(800_000L);
        assertThat(s.netDisbursedPaise()).isEqualTo(705_600L);
        assertThat(s.outstandingPaise()).isEqualTo(1_040_000L);
        assertThat(s.borrowerName()).isEqualTo("Asha Verma");
        assertThat(s.panMasked()).isEqualTo("ABXXXXX34F");
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
    void markInCollectionsFlipsActiveLoan() {
        Loan loan = loan(2L, LoanStatus.ACTIVE);
        when(loanRepository.findById(2L)).thenReturn(Optional.of(loan));

        adapter.markInCollections(2L);

        assertThat(loan.getStatus()).isEqualTo(LoanStatus.IN_COLLECTIONS);
        verify(loanRepository).save(loan);
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
        when(applicationRepository.findByLoanId(2L)).thenReturn(Optional.empty());

        List<LoanSummary> result = adapter.listCollectible(LocalDate.now());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).loanId()).isEqualTo(2L);
    }
}
