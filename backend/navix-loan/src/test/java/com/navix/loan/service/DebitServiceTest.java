package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
import com.navix.common.loan.LoanDirectory;
import com.navix.common.loan.LoanSummary;
import com.navix.common.verification.MandatePort;
import com.navix.common.verification.MandatePort.DebitOutcome;
import com.navix.common.verification.MandatePort.DebitPlan;
import com.navix.common.verification.MandatePort.DebitRequest;
import com.navix.loan.entity.Mandate;
import com.navix.loan.entity.MandateDebit;
import com.navix.loan.entity.MandateDebit.Status;
import com.navix.loan.entity.MandateDebit.Trigger;
import com.navix.loan.repository.MandateDebitRepository;
import com.navix.loan.repository.PaymentRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The double-charge guards. Everything the provider would do is mocked: no real Digio call is possible. */
class DebitServiceTest {

    private static final long LOAN = 7L;
    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    private MandateDebitRepository debits;
    private MandateService mandateService;
    private LoanDirectory loans;
    private RepaymentService repayments;
    private MandatePort port;
    private PaymentRepository payments;
    private PlatformTransactionManager ptm;
    private DebitService service;
    private Mandate mandate;

    @BeforeEach
    void setUp() {
        debits = mock(MandateDebitRepository.class);
        mandateService = mock(MandateService.class);
        loans = mock(LoanDirectory.class);
        repayments = mock(RepaymentService.class);
        port = mock(MandatePort.class);
        payments = mock(PaymentRepository.class);
        ptm = mock(PlatformTransactionManager.class);
        service = new DebitService(debits, mandateService, loans, repayments, port, payments,
                new TransactionTemplate(ptm));
        ReflectionTestUtils.setField(service, "corporateAccountNumber", "0123456789");

        mandate = new Mandate();
        mandate.setId(3L);
        mandate.setUmrn("UMRN1");
        mandate.setMaximumAmountPaise(2_000_000L);
        mandate.setFirstCollectionDate(today.minusDays(30));
        mandate.setFinalCollectionDate(today.plusYears(1));

        when(loans.findLoan(LOAN)).thenReturn(Optional.of(loan("OVERDUE", today.minusDays(3))));
        when(mandateService.usableMandateForLoan(LOAN)).thenReturn(Optional.of(mandate));
        when(repayments.outstandingAsOf(LOAN, today)).thenReturn(500_000L);
        when(debits.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(debits.save(any())).thenAnswer(i -> i.getArgument(0));
        when(port.presentDebit(any())).thenReturn(new DebitPlan("NSP1", "SCHEDULED", null, null));
    }

    private static LoanSummary loan(String status, LocalDate due) {
        return new LoanSummary(LOAN, 1L, 2L, status, 0L, 0L, 0L, 500_000L, null, due,
                null, null, null, null, null, null, null, false);
    }

    private static void assertCode(Runnable r, String code) {
        assertThatThrownBy(r::run).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode()).isEqualTo(code);
    }

    private MandateDebit row(Status status, String code) {
        MandateDebit d = new MandateDebit();
        d.setId(11L);
        d.setLoanId(LOAN);
        d.setUniqueKey("DBM7-1");
        d.setAttemptNo(1);
        d.setAmountPaise(500_000L);
        d.setSettlementDate(today.minusDays(1));
        d.setStatus(status);
        d.setFailureCode(code);
        return d;
    }

    @Test
    void defaultsToFullOutstandingAsOfToday() {
        service.present(LOAN, null, Trigger.MANUAL, 9L);
        ArgumentCaptor<DebitRequest> req = ArgumentCaptor.forClass(DebitRequest.class);
        verify(port).presentDebit(req.capture());
        assertThat(req.getValue().amountPaise()).isEqualTo(500_000L);
        assertThat(req.getValue().umrn()).isEqualTo("UMRN1");
        assertThat(req.getValue().settlementDate()).isEqualTo(today.plusDays(1));
        verify(repayments).outstandingAsOf(LOAN, today);
    }

    @Test
    void refusesAnAmountAboveOutstanding() {
        assertCode(() -> service.present(LOAN, 500_001L, Trigger.MANUAL, 9L), "AMOUNT_EXCEEDS_OUTSTANDING");
        verify(port, never()).presentDebit(any());
    }

    @Test
    void refusesAnAmountAboveTheMandateCeiling() {
        mandate.setMaximumAmountPaise(300_000L);
        assertCode(() -> service.present(LOAN, 400_000L, Trigger.MANUAL, 9L), "AMOUNT_EXCEEDS_MANDATE");
        verify(port, never()).presentDebit(any());
    }

    @Test
    void refusesASettlementDateOutsideTheMandateWindow() {
        mandate.setFinalCollectionDate(today); // tomorrow's settlement is past the end
        assertCode(() -> service.present(LOAN, null, Trigger.MANUAL, 9L), "OUTSIDE_MANDATE_WINDOW");
        mandate.setFinalCollectionDate(today.plusYears(1));
        mandate.setFirstCollectionDate(today.plusDays(5)); // and before the start
        assertCode(() -> service.present(LOAN, null, Trigger.MANUAL, 9L), "OUTSIDE_MANDATE_WINDOW");
        verify(port, never()).presentDebit(any());
    }

    @Test
    void refusesWhenNoUsableMandateExists() {
        when(mandateService.usableMandateForLoan(LOAN)).thenReturn(Optional.empty());
        assertCode(() -> service.present(LOAN, null, Trigger.MANUAL, 9L), "NO_USABLE_MANDATE");
        verify(port, never()).presentDebit(any());
    }

    @Test
    void refusesWhenADebitIsAlreadyInFlightForTheLoan() {
        when(debits.existsByLoanIdAndStatusIn(eq(LOAN), anyCollection())).thenReturn(true);
        assertCode(() -> service.present(LOAN, null, Trigger.SCHEDULED, null), "DEBIT_IN_FLIGHT");
        verify(port, never()).presentDebit(any());
        verify(debits, never()).saveAndFlush(any());
    }

    @Test
    void takesTheLoanAdvisoryLockBeforeCheckingInFlight() {
        service.present(LOAN, null, Trigger.MANUAL, 9L);
        var order = inOrder(debits);
        order.verify(debits).lockLoan(anyLong());
        order.verify(debits).existsByLoanIdAndStatusIn(eq(LOAN), anyCollection());
        order.verify(debits).saveAndFlush(any());
    }

    @Test
    void commitsTheRowBeforeCallingTheProvider() {
        service.present(LOAN, null, Trigger.MANUAL, 9L);
        var order = inOrder(debits, ptm, port);
        order.verify(debits).saveAndFlush(any());
        order.verify(ptm).commit(any());
        order.verify(port).presentDebit(any());
    }

    @Test
    void recoversByUniqueKeyInsteadOfPresentingTwiceOnTimeout() {
        when(port.presentDebit(any())).thenThrow(new IllegalStateException("read timed out"));
        when(port.findDebitByRef(anyString())).thenReturn(
                Optional.of(new DebitPlan("NSP9", "SCHEDULED", "NPT9", today.plusDays(1))));
        var view = service.present(LOAN, null, Trigger.MANUAL, 9L);
        verify(port, times(1)).presentDebit(any());
        verify(port).findDebitByRef("DBM7-1");
        assertThat(view.status()).isEqualTo(Status.SCHEDULED);
    }

    @Test
    void anUnconfirmedTimeoutParksTheRowAsOutcomeUnknownAndNeverRetriesBlind() {
        when(port.presentDebit(any())).thenThrow(new IllegalStateException("read timed out"));
        when(port.findDebitByRef(anyString())).thenReturn(Optional.empty());
        assertCode(() -> service.present(LOAN, null, Trigger.MANUAL, 9L), "DEBIT_UNCONFIRMED");
        verify(port, times(1)).presentDebit(any());
        ArgumentCaptor<MandateDebit> saved = ArgumentCaptor.forClass(MandateDebit.class);
        verify(debits).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(Status.FAILED_TECHNICAL);
    }

    @Test
    void anOutcomeUnknownRowIsRecoveredByKeyBeforeAnythingIsPresented() {
        MandateDebit unknown = row(Status.FAILED_TECHNICAL, null);
        when(debits.findFirstByLoanIdOrderByAttemptNoDesc(LOAN)).thenReturn(Optional.of(unknown));
        when(port.findDebitByRef("DBM7-1")).thenReturn(
                Optional.of(new DebitPlan("NSP1", "SCHEDULED", "NPT1", null)));
        var view = service.present(LOAN, null, Trigger.SCHEDULED, null);
        verify(port, never()).presentDebit(any());
        assertThat(view.status()).isEqualTo(Status.SCHEDULED);
        assertThat(unknown.getDigioTxnId()).isEqualTo("NPT1");
    }

    @Test
    void anOutcomeUnknownRowWithNothingAtTheProviderIsRepresentedWithTheIdenticalKey() {
        MandateDebit unknown = row(Status.FAILED_TECHNICAL, null);
        when(debits.findFirstByLoanIdOrderByAttemptNoDesc(LOAN)).thenReturn(Optional.of(unknown));
        when(port.findDebitByRef("DBM7-1")).thenReturn(Optional.empty());
        service.present(LOAN, null, Trigger.SCHEDULED, null);
        ArgumentCaptor<DebitRequest> req = ArgumentCaptor.forClass(DebitRequest.class);
        verify(port).presentDebit(req.capture());
        assertThat(req.getValue().uniqueKey()).isEqualTo("DBM7-1");
    }

    @Test
    void scheduledAndManualUniqueKeysDoNotCollide() {
        service.present(LOAN, null, Trigger.SCHEDULED, null);
        service.present(LOAN, null, Trigger.MANUAL, 9L);
        ArgumentCaptor<DebitRequest> req = ArgumentCaptor.forClass(DebitRequest.class);
        verify(port, times(2)).presentDebit(req.capture());
        assertThat(req.getAllValues().get(0).uniqueKey()).isNotEqualTo(req.getAllValues().get(1).uniqueKey());
        assertThat(req.getAllValues()).allSatisfy(r -> assertThat(r.uniqueKey()).matches("^[A-Za-z0-9_-]{1,30}$"));
        assertThat(req.getValue().narration()).matches("^[0-9A-Za-z& .]{1,20}$");
    }

    private MandateDebit inProgress() {
        MandateDebit d = row(Status.IN_PROGRESS, null);
        d.setDigioTxnId("NPT1");
        d.setSettlementDate(today);
        when(debits.findById(11L)).thenReturn(Optional.of(d));
        return d;
    }

    @Test
    void successCreditsThroughLoanDirectoryWithTheUtrAsTxnRef() {
        MandateDebit d = inProgress();
        when(port.debitStatus("NPT1")).thenReturn(new DebitOutcome("NPT1", "PAYMENT_SUCCESS", "UTR123", null, null, 500_000L));
        when(loans.creditCollectionPayment(eq(LOAN), eq(500_000L), eq("UTR123"), anyString(), any())).thenReturn(55L);
        var view = service.reconcile(11L);
        verify(loans).creditCollectionPayment(eq(LOAN), eq(500_000L), eq("UTR123"), anyString(), eq(today));
        assertThat(view.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(d.getPaymentId()).isEqualTo(55L);
        assertThat(d.isOverpayment()).isFalse();
    }

    @Test
    void aReplayedSuccessDoesNotCreditTwice() {
        inProgress();
        when(port.debitStatus("NPT1")).thenReturn(new DebitOutcome("NPT1", "PAYMENT_SUCCESS", "UTR123", null, null, 500_000L));
        var existing = new com.navix.loan.entity.Payment();
        existing.setId(55L);
        when(payments.findFirstByLoanIdAndTxnRef(LOAN, "UTR123")).thenReturn(Optional.of(existing));
        service.reconcile(11L);
        verify(loans, never()).creditCollectionPayment(anyLong(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    void successOnASettledLoanIsParkedAsAFlaggedOverpayment() {
        MandateDebit d = inProgress();
        when(port.debitStatus("NPT1")).thenReturn(new DebitOutcome("NPT1", "PAYMENT_SUCCESS", "UTR123", null, null, 500_000L));
        when(loans.creditCollectionPayment(anyLong(), anyLong(), anyString(), anyString(), any()))
                .thenThrow(new BusinessException("LOAN_SETTLED", "Loan is already settled"));
        var view = service.reconcile(11L);
        assertThat(view.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(view.overpayment()).isTrue();
        assertThat(d.getPaymentId()).isNull();
    }

    @Test
    void refusesAZeroOrNegativeAmount() {
        assertCode(() -> service.present(LOAN, 0L, Trigger.MANUAL, 9L), "INVALID_AMOUNT");
        assertCode(() -> service.present(LOAN, -5L, Trigger.MANUAL, 9L), "INVALID_AMOUNT");
        verify(port, never()).presentDebit(any());
    }

    @Test
    void aLostRaceOnTheInFlightIndexIsReportedAsInFlight() {
        when(debits.saveAndFlush(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq"));
        assertCode(() -> service.present(LOAN, null, Trigger.MANUAL, 9L), "DEBIT_IN_FLIGHT");
        verify(port, never()).presentDebit(any());
    }

    @Test
    void doesNotRepresentAfterATerminalRejectCode() {
        for (String code : new String[] {"ACCOUNT_CLOSED", "SOMETHING_NEW_99", null}) {
            when(debits.findFirstByLoanIdOrderByAttemptNoDesc(LOAN)).thenReturn(Optional.of(row(Status.FAILED, code)));
            assertCode(() -> service.present(LOAN, null, Trigger.MANUAL, 9L), "DEBIT_NOT_REPRESENTABLE");
        }
        verify(port, never()).presentDebit(any());
    }

    @Test
    void representsAfterBalanceInsufficientButStopsAtTheAttemptCap() {
        MandateDebit failed = row(Status.FAILED, "BALANCE_INSUFFICIENT");
        when(debits.findFirstByLoanIdOrderByAttemptNoDesc(LOAN)).thenReturn(Optional.of(failed));
        service.present(LOAN, null, Trigger.SCHEDULED, null);
        verify(port, times(1)).presentDebit(any());
        when(debits.countByLoanIdAndStatusIn(eq(LOAN), anyCollection())).thenReturn(4L);
        assertCode(() -> service.present(LOAN, null, Trigger.SCHEDULED, null), "DEBIT_ATTEMPTS_EXHAUSTED");
        verify(port, times(1)).presentDebit(any());
        assertCapCountsOnlyFruitlessAttempts();
    }

    @SuppressWarnings("unchecked")
    private void assertCapCountsOnlyFruitlessAttempts() {
        ArgumentCaptor<java.util.Collection<Status>> statuses = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(debits, org.mockito.Mockito.atLeastOnce()).countByLoanIdAndStatusIn(eq(LOAN), statuses.capture());
        assertThat(statuses.getAllValues()).allSatisfy(c -> assertThat(java.util.Set.copyOf(c))
                .isEqualTo(java.util.Set.of(Status.FAILED, Status.FAILED_TECHNICAL)));
    }

    @Test
    void succeededAttemptsDoNotConsumeTheRetryCap() {
        MandateDebit paid = row(Status.SUCCEEDED, null);
        paid.setAttemptNo(5);
        when(debits.findFirstByLoanIdOrderByAttemptNoDesc(LOAN)).thenReturn(Optional.of(paid));
        when(debits.countByLoanIdAndStatusIn(eq(LOAN), anyCollection())).thenReturn(0L);
        service.present(LOAN, 100_000L, Trigger.MANUAL, 9L);
        verify(port, times(1)).presentDebit(any());
        assertCapCountsOnlyFruitlessAttempts();
    }

    @Test
    void reconcileTakesTheLoanLockBeforeCrediting() {
        inProgress();
        when(port.debitStatus("NPT1")).thenReturn(new DebitOutcome("NPT1", "PAYMENT_SUCCESS", "UTR123", null, null, 500_000L));
        when(loans.creditCollectionPayment(anyLong(), anyLong(), anyString(), anyString(), any())).thenReturn(55L);
        service.reconcile(11L);
        var order = inOrder(debits, loans);
        order.verify(debits).lockLoan(anyLong());
        order.verify(loans).creditCollectionPayment(anyLong(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    void insufficientBalanceIsEligibleForRepresentation() {
        assertThat(service.eligibleForRepresentation(row(Status.FAILED, "BALANCE_INSUFFICIENT"))).isTrue();
        inProgress();
        when(port.debitStatus("NPT1")).thenReturn(
                new DebitOutcome("NPT1", "PAYMENT_FAILED", null, "balance insufficient", "raw bank text", 500_000L));
        assertThat(service.reconcile(11L).representable()).isTrue();
    }

    @Test
    void accountClosedIsTerminalAndNotRepresented() {
        assertThat(service.eligibleForRepresentation(row(Status.FAILED, "ACCOUNT_CLOSED"))).isFalse();
    }

    @Test
    void unknownRejectCodeIsTreatedAsTerminal() {
        assertThat(service.eligibleForRepresentation(row(Status.FAILED, "SOMETHING_NEW_99"))).isFalse();
        assertThat(service.eligibleForRepresentation(row(Status.FAILED, null))).isFalse();
        assertThat(service.eligibleForRepresentation(row(Status.FAILED_TECHNICAL, "BALANCE_INSUFFICIENT"))).isFalse();
    }
}
