package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
import com.navix.loan.repository.PaymentRepository;
import com.navix.loan.repository.RepaymentDiscountOfferRepository;

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

    @Mock
    private RepaymentDiscountOfferRepository offerRepository;

    @Mock
    private PaymentRepository paymentRepository;

    private LoanService loanService;

    @BeforeEach
    void setUp() {
        loanService = new LoanService(loanRepository, new LoanMath(),
                new RepaymentDiscountOffers(offerRepository, loanRepository, paymentRepository));
    }

    @Test
    void disbursePinnedDiscountLowersFeeAndGstAndClaimsTheOfferOnce() {
        LoanApplication app = new LoanApplication();
        app.setId(55L);
        app.setCustomerId(7L);
        app.setAmountRequested(1_000_000L);
        app.setSalaryCreditDay(30);
        app.setFeeDiscountPercent((short) 5);
        app.setFeeDiscountOfferId(9L);
        when(loanRepository.save(any())).thenAnswer(i -> {
            Loan l = i.getArgument(0);
            l.setId(77L);
            return l;
        });
        when(offerRepository.claim(eq(9L), eq(55L), eq(77L), any())).thenReturn(1);

        Loan loan = loanService.disburse(app, LocalDate.of(2026, 6, 3), "UTR1");

        assertThat(loan.getProcessingFee()).isEqualTo(50_000L);
        assertThat(loan.getGst()).isEqualTo(9_000L);
        assertThat(loan.getNetDisbursed()).isEqualTo(941_000L);
        assertThat(loan.getFeeDiscountPaise()).isEqualTo(50_000L);
        // interest and total repayable are untouched by the discount
        assertThat(loan.getTotalRepayable()).isEqualTo(1_270_000L);
        verify(offerRepository).claim(eq(9L), eq(55L), eq(77L), any());
    }

    @Test
    void disburseRefusesWhenThePinnedOfferWasAlreadyConsumed() {
        LoanApplication app = new LoanApplication();
        app.setId(56L);
        app.setCustomerId(7L);
        app.setAmountRequested(1_000_000L);
        app.setSalaryCreditDay(30);
        app.setFeeDiscountPercent((short) 5);
        app.setFeeDiscountOfferId(9L);
        when(loanRepository.save(any())).thenAnswer(i -> {
            Loan l = i.getArgument(0);
            l.setId(78L);
            return l;
        });
        when(offerRepository.claim(eq(9L), eq(56L), eq(78L), any())).thenReturn(0);

        assertThatThrownBy(() -> loanService.disburse(app, LocalDate.of(2026, 6, 3), "UTR2"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already used");
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

    @Test
    void disbursementRecomputesDueDateFromTheSanctionedSalaryDay() {
        LoanApplication app = new LoanApplication();
        app.setCustomerId(7L);
        app.setAmountRequested(1_000_000L);
        app.setSalaryCreditDay(31);
        app.setApprovedRepaymentDate(LocalDate.of(2026, 2, 28)); // sanction-time projection
        when(loanRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Loan loan = loanService.disburse(app, LocalDate.of(2028, 1, 25), "UTR-LEAP");

        assertThat(loan.getDueDate()).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(loan.getTotalRepayable()).isEqualTo(1_350_000L);
    }
}
