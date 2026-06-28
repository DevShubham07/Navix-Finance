package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.collections.SettlementDirectory;
import com.navix.common.exception.BusinessException;
import com.navix.loan.domain.LoanStatus;
import com.navix.loan.domain.PaymentMethod;
import com.navix.loan.domain.PaymentStatus;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.Payment;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.repository.PaymentRepository;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RepaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private LoanRepository loanRepository;
    @Mock
    private ApplicationFlowService applicationFlowService;
    @Mock
    private SettlementDirectory settlementDirectory;

    private RepaymentService repaymentService;

    @BeforeEach
    void setUp() {
        repaymentService = new RepaymentService(paymentRepository, loanRepository, new LoanMath(),
                applicationFlowService, settlementDirectory, event -> {});
    }

    private Loan activeLoan() {
        Loan loan = new Loan();
        loan.setApplicantId(7L);
        loan.setPrincipal(1_000_000L);
        loan.setDisbursedOn(LocalDate.of(2026, 6, 3));
        loan.setDueDate(LocalDate.of(2026, 6, 30)); // 27-day tenure
        loan.setTotalRepayable(1_270_000L);
        loan.setOutstanding(1_270_000L);
        loan.setStatus(LoanStatus.ACTIVE);
        return loan;
    }

    @Test
    void recordsPendingPartialPayment() {
        when(loanRepository.findById(1L)).thenReturn(Optional.of(activeLoan()));
        when(paymentRepository.sumAmountByLoanIdAndStatus(1L, PaymentStatus.VERIFIED)).thenReturn(0L);
        when(paymentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Payment p = repaymentService.recordPayment(1L, 500_000L, PaymentMethod.UPI, "TXN1", null,
                LocalDate.of(2026, 6, 30));

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING_VERIFICATION);
        assertThat(p.getAmount()).isEqualTo(500_000L);
        assertThat(p.isPartial()).isTrue(); // 500k < 1.27m remaining
    }

    @Test
    void recordIsIdempotentOnTxnRef() {
        Payment existing = new Payment();
        existing.setLoanId(1L);
        existing.setTxnRef("DUP");
        when(loanRepository.findById(1L)).thenReturn(Optional.of(activeLoan()));
        when(paymentRepository.findFirstByLoanIdAndTxnRef(1L, "DUP")).thenReturn(Optional.of(existing));

        Payment p = repaymentService.recordPayment(1L, 500_000L, PaymentMethod.UPI, "DUP", null, null);

        assertThat(p).isSameAs(existing);
    }

    @Test
    void rejectsPaymentOnSettledLoan() {
        Loan closed = activeLoan();
        closed.setStatus(LoanStatus.CLOSED);
        when(loanRepository.findById(1L)).thenReturn(Optional.of(closed));

        assertThatThrownBy(() -> repaymentService.recordPayment(1L, 100_000L, PaymentMethod.UPI, "T", null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("settled");
    }

    @Test
    void verifyClosesLoanWhenFullyPaid() {
        Loan loan = activeLoan();
        Payment payment = new Payment();
        payment.setLoanId(1L);
        payment.setAmount(1_270_000L);
        payment.setStatus(PaymentStatus.PENDING_VERIFICATION);
        when(paymentRepository.findById(99L)).thenReturn(Optional.of(payment));
        when(loanRepository.findById(1L)).thenReturn(Optional.of(loan));
        when(paymentRepository.sumAmountByLoanIdAndStatus(1L, PaymentStatus.VERIFIED)).thenReturn(1_270_000L);
        lenient().when(paymentRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(loanRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        repaymentService.verifyPayment(99L);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.VERIFIED);
        assertThat(loan.getOutstanding()).isZero();
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.CLOSED);
        // Full repayment also closes the application aggregate (ACTIVE → CLOSED).
        verify(applicationFlowService).closeForLoan(1L);
    }

    @Test
    void outstandingAsOfReflectsPrepaymentAndPenalty() {
        when(loanRepository.findById(1L)).thenReturn(Optional.of(activeLoan()));
        when(paymentRepository.sumAmountByLoanIdAndStatus(eq(1L), eq(PaymentStatus.VERIFIED))).thenReturn(0L);

        // Prepay on day 10 (13 Jun): interest only to day 10 → ₹11,000
        assertThat(repaymentService.outstandingAsOf(1L, LocalDate.of(2026, 6, 13))).isEqualTo(1_100_000L);

        // Overdue on 5 Jul: full 27d interest + penalty for (5 − 1 grace) = 4 days
        assertThat(repaymentService.outstandingAsOf(1L, LocalDate.of(2026, 7, 5))).isEqualTo(1_350_000L);
    }

    @Test
    void approvedSettlementCapsOutstanding() {
        when(loanRepository.findById(1L)).thenReturn(Optional.of(activeLoan()));
        when(paymentRepository.sumAmountByLoanIdAndStatus(eq(1L), eq(PaymentStatus.VERIFIED))).thenReturn(0L);
        // Head approved a ₹7,000 full-and-final on a ₹12,700 due loan → borrower owes the settled amount.
        when(settlementDirectory.approvedSettlementAmount(1L)).thenReturn(Optional.of(700_000L));

        assertThat(repaymentService.outstandingAsOf(1L, LocalDate.of(2026, 6, 30))).isEqualTo(700_000L);
    }

    @Test
    void settlementNeverIncreasesPayable() {
        when(loanRepository.findById(1L)).thenReturn(Optional.of(activeLoan()));
        when(paymentRepository.sumAmountByLoanIdAndStatus(eq(1L), eq(PaymentStatus.VERIFIED))).thenReturn(0L);
        // A settlement above the real balance is a no-op: the borrower still owes only the lower formula amount.
        when(settlementDirectory.approvedSettlementAmount(1L)).thenReturn(Optional.of(2_000_000L));

        assertThat(repaymentService.outstandingAsOf(1L, LocalDate.of(2026, 6, 30))).isEqualTo(1_270_000L);
    }

    @Test
    void payingTheSettledAmountClosesTheLoan() {
        Loan loan = activeLoan();
        Payment payment = new Payment();
        payment.setLoanId(1L);
        payment.setAmount(700_000L);
        payment.setStatus(PaymentStatus.PENDING_VERIFICATION);
        when(paymentRepository.findById(55L)).thenReturn(Optional.of(payment));
        when(loanRepository.findById(1L)).thenReturn(Optional.of(loan));
        // Verified payments now equal the approved settlement → settled balance is 0, independent of the clock.
        when(paymentRepository.sumAmountByLoanIdAndStatus(1L, PaymentStatus.VERIFIED)).thenReturn(700_000L);
        when(settlementDirectory.approvedSettlementAmount(1L)).thenReturn(Optional.of(700_000L));
        lenient().when(paymentRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(loanRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        repaymentService.verifyPayment(55L);

        assertThat(loan.getOutstanding()).isZero();
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.CLOSED);
        verify(applicationFlowService).closeForLoan(1L);
    }
}
