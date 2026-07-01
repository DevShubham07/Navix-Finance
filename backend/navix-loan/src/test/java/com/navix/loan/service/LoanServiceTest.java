package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.navix.loan.domain.LoanStatus;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.LoanRepository;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LoanServiceTest {

    @Mock
    private LoanRepository loanRepository;

    private LoanService loanService;

    @BeforeEach
    void setUp() {
        loanService = new LoanService(loanRepository, new LoanMath());
    }

    @Test
    void disburseComputesEconomicsWithSalaryLinkedDueDate() {
        LoanApplication app = new LoanApplication();
        app.setCustomerId(7L);
        app.setAmountRequested(1_000_000L); // ₹10,000
        app.setSalaryCreditDay(30);          // salary on the 30th
        when(loanRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        // Disbursed 3 Jun → latest salary credit ≤ 40d is 30 Jun (27-day tenure).
        Loan loan = loanService.disburse(app, LocalDate.of(2026, 6, 3), "UTR12345");

        assertThat(loan.getDisbursalTxnRef()).isEqualTo("UTR12345");
        assertThat(loan.getPrincipal()).isEqualTo(1_000_000L);
        assertThat(loan.getProcessingFee()).isEqualTo(100_000L);
        assertThat(loan.getGst()).isEqualTo(18_000L);
        assertThat(loan.getNetDisbursed()).isEqualTo(882_000L);
        assertThat(loan.getDueDate()).isEqualTo(LocalDate.of(2026, 6, 30)); // salary-linked ≤ 40d
        assertThat(loan.getTotalRepayable()).isEqualTo(1_270_000L); // principal + 27d interest
        assertThat(loan.getOutstanding()).isEqualTo(1_270_000L);
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.ACTIVE);
    }
}
