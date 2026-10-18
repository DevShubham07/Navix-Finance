package com.navix.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.collections.service.DpdCalculator;
import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.loan.LoanDirectory;
import com.navix.common.loan.LoanSummary;
import com.navix.loan.entity.MandateDebit;
import com.navix.loan.entity.MandateDebit.Status;
import com.navix.loan.entity.MandateDebit.Trigger;
import com.navix.loan.repository.MandateDebitRepository;
import com.navix.loan.service.DebitService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MandateDebitSchedulerTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Mock DebitService debitService;
    @Mock MandateDebitRepository debits;
    @Mock LoanDirectory loans;
    @Mock FeatureFlagService flags;

    MandateDebitScheduler sweep;

    @BeforeEach
    void setUp() {
        when(flags.isEnabled("digio-debit", false)).thenReturn(true);
        when(debits.findFirstByLoanIdOrderByAttemptNoDesc(anyLong())).thenReturn(Optional.empty());
        when(debitService.configured()).thenReturn(true);
        org.mockito.Mockito.lenient().when(debitService.hasUsableMandate(anyLong())).thenReturn(true);
        sweep = new MandateDebitScheduler(debitService, debits, loans, new DpdCalculator(), flags);
    }

    private LoanSummary loan(long id, int dpd) {
        LocalDate due = LocalDate.now(IST).minusDays(dpd);
        return new LoanSummary(id, 100L + id, 7L, null, null, null, null, null, null, due, null, null, null, null, null, null, null, false);
    }

    private MandateDebit debit(long id, long loanId, int attemptNo, Status st, String code, LocalDate settle) {
        MandateDebit d = new MandateDebit();
        d.setId(id);
        d.setLoanId(loanId);
        d.setAttemptNo(attemptNo);
        d.setStatus(st);
        d.setFailureCode(code);
        d.setSettlementDate(settle);
        return d;
    }

    @Test
    void presentsNothingWhenTheCorporateAccountIsUnset() {
        // Otherwise every loan fails DEBIT_NOT_CONFIGURED one warn at a time and the sweep looks healthy.
        when(debitService.configured()).thenReturn(false);
        when(loans.listCollectible(any())).thenReturn(List.of(loan(1, 3)));
        sweep.sweep();
        verify(debitService, never()).present(anyLong(), any(), any(), any());
    }

    @Test
    void presentsAtDpdOneThreeFiveAndNine() {
        for (int dpd : new int[] {1, 3, 5, 9}) {
            when(loans.listCollectible(any())).thenReturn(List.of(loan(dpd, dpd)));
            // previous attempts fill every earlier slot
            int made = dpd == 1 ? 0 : dpd == 3 ? 1 : dpd == 5 ? 2 : 3;
            if (made > 0) {
                when(debits.findFirstByLoanIdOrderByAttemptNoDesc((long) dpd))
                        .thenReturn(Optional.of(debit(1, dpd, made, Status.FAILED, "BALANCE_INSUFFICIENT", null)));
                when(debitService.eligibleForRepresentation(any())).thenReturn(true);
            }
            sweep.sweep();
            verify(debitService).present((long) dpd, null, Trigger.SCHEDULED, null);
        }
    }

    @Test
    void doesNotPresentAtOtherDpdValues() {
        // 2, 4, 6 already have their slot's attempt; 10 is past the schedule
        int[][] cases = {{2, 1}, {4, 2}, {6, 3}, {10, 4}};
        for (int[] c : cases) {
            when(loans.listCollectible(any())).thenReturn(List.of(loan(c[0], c[0])));
            when(debits.findFirstByLoanIdOrderByAttemptNoDesc((long) c[0]))
                    .thenReturn(Optional.of(debit(1, c[0], c[1], Status.FAILED, "BALANCE_INSUFFICIENT", null)));
            when(debitService.eligibleForRepresentation(any())).thenReturn(true);
        }
        for (int[] c : cases) {
            when(loans.listCollectible(any())).thenReturn(List.of(loan(c[0], c[0])));
            sweep.sweep();
        }
        verify(debitService, never()).present(anyLong(), any(), any(), any());
    }

    @Test
    void doesNotPresentWhenTheFlagIsOff() {
        when(flags.isEnabled("digio-debit", false)).thenReturn(false);
        when(debits.findDueForReconcile(any(), any(), any()))
                .thenReturn(List.of(debit(11, 1, 1, Status.IN_PROGRESS, null, LocalDate.now(IST))));
        when(loans.listCollectible(any())).thenReturn(List.of(loan(1, 1)));
        sweep.sweep();
        verify(debitService, never()).present(anyLong(), any(), any(), any());
        verify(debitService, never()).reconcile(anyLong());
        verify(debits, never()).findDueForReconcile(any(), any(), any());
        verify(loans, never()).listCollectible(any());
    }

    @Test
    void skipsALoanWithATerminalPreviousRejection() {
        when(loans.listCollectible(any())).thenReturn(List.of(loan(5, 3)));
        MandateDebit failed = debit(1, 5, 1, Status.FAILED, "ACCOUNT_CLOSED", null);
        when(debits.findFirstByLoanIdOrderByAttemptNoDesc(5L)).thenReturn(Optional.of(failed));
        when(debitService.eligibleForRepresentation(failed)).thenReturn(false);
        sweep.sweep();
        verify(debitService, never()).present(anyLong(), any(), any(), any());
    }

    @Test
    void catchesUpAMissedAttemptDayWhileStillEligible() {
        // DPD 2 with no attempt yet: the DPD-1 run was missed, so it is presented now.
        when(loans.listCollectible(any())).thenReturn(List.of(loan(8, 2)));
        sweep.sweep();
        verify(debitService).present(8L, null, Trigger.SCHEDULED, null);
    }

    @Test
    void reconcilesInFlightDebitsPastTheirSettlementDate() {
        LocalDate today = LocalDate.now(IST);
        when(loans.listCollectible(any())).thenReturn(List.of());
        when(debits.findDueForReconcile(any(), any(), any())).thenReturn(List.of(
                debit(11, 1, 1, Status.IN_PROGRESS, null, today),
                debit(12, 2, 1, Status.SCHEDULED, null, today.minusDays(1))));
        sweep.sweep();
        verify(debits).findDueForReconcile(any(), org.mockito.ArgumentMatchers.eq(today), any());
        verify(debitService).reconcile(11L);
        verify(debitService).reconcile(12L);
    }

    @Test
    void oneFailingLoanDoesNotStopTheSweep() {
        when(loans.listCollectible(any())).thenReturn(List.of(loan(1, 1), loan(2, 1)));
        when(debitService.present(1L, null, Trigger.SCHEDULED, null)).thenThrow(new RuntimeException("boom"));
        when(debits.findDueForReconcile(any(), any(), any())).thenReturn(List.of(debit(11, 9, 1, Status.IN_PROGRESS, null, LocalDate.now(IST))));
        when(debitService.reconcile(11L)).thenThrow(new RuntimeException("boom"));
        sweep.sweep();
        verify(debitService).present(2L, null, Trigger.SCHEDULED, null);
    }

    @Test
    void reconcileIsCappedAtFiftyPerPassAndTheNextPassTakesTheRest() {
        LocalDate today = LocalDate.now(IST);
        when(loans.listCollectible(any())).thenReturn(List.of());
        java.util.List<MandateDebit> backlog = new java.util.ArrayList<>();
        for (long i = 1; i <= 120; i++) {
            backlog.add(debit(i, i, 1, Status.IN_PROGRESS, null, today));
        }
        // Stands in for the real query: oldest first, honours the page size, drops rows already reconciled.
        when(debits.findDueForReconcile(any(), any(), any())).thenAnswer(inv -> {
            org.springframework.data.domain.Pageable page = inv.getArgument(2);
            return backlog.stream().limit(page.getPageSize()).toList();
        });
        when(debitService.reconcile(anyLong())).thenAnswer(inv -> {
            backlog.removeIf(d -> d.getId().equals(inv.getArgument(0)));
            return null;
        });
        sweep.sweep();
        verify(debitService, org.mockito.Mockito.times(50)).reconcile(anyLong());
        verify(debitService, never()).reconcile(51L);
        sweep.sweep();
        verify(debitService, org.mockito.Mockito.times(100)).reconcile(anyLong());
        verify(debitService).reconcile(51L);
        verify(debitService).reconcile(100L);
        verify(debitService, never()).reconcile(101L);
    }

    @Test
    void anUnresolvedOrFailingReconcileTouchesTheRowSoItRotatesBack() {
        LocalDate today = LocalDate.now(IST);
        when(loans.listCollectible(any())).thenReturn(List.of());
        when(debits.findDueForReconcile(any(), any(), any())).thenReturn(List.of(
                debit(21, 1, 1, Status.IN_PROGRESS, null, today),
                debit(22, 2, 1, Status.IN_PROGRESS, null, today),
                debit(23, 3, 1, Status.IN_PROGRESS, null, today)));
        when(debitService.reconcile(21L)).thenThrow(new RuntimeException("boom"));
        when(debitService.reconcile(22L)).thenReturn(view(Status.IN_PROGRESS));
        when(debitService.reconcile(23L)).thenReturn(view(Status.SUCCEEDED));
        sweep.sweep();
        verify(debits).touch(org.mockito.ArgumentMatchers.eq(21L), any());
        verify(debits).touch(org.mockito.ArgumentMatchers.eq(22L), any());
        verify(debits, never()).touch(org.mockito.ArgumentMatchers.eq(23L), any());
    }

    private static DebitService.DebitView view(Status st) {
        return new DebitService.DebitView(1L, 1L, st, 0, null, 1, Trigger.SCHEDULED, null, false, false);
    }

    /**
     * Most overdue loans hold no mandate. Before this filter the sweep called present() on every one of
     * them, logging NO_USABLE_MANDATE per loan per day and burying real failures. prepare() still
     * refuses independently — this only keeps the log readable.
     */
    @Test
    void skipsOverdueLoansWithNoUsableMandateWithoutCallingPresent() {
        when(loans.listCollectible(any())).thenReturn(List.of(loan(5L, 3)));
        when(debitService.hasUsableMandate(5L)).thenReturn(false);
        sweep.sweep();
        verify(debitService, never()).present(anyLong(), any(), any(), any());
    }

    /** ...but a loan that DOES hold one is still presented, so the filter cannot silence collection. */
    @Test
    void stillPresentsWhenTheLoanHoldsAUsableMandate() {
        when(loans.listCollectible(any())).thenReturn(List.of(loan(6L, 3)));
        when(debitService.hasUsableMandate(6L)).thenReturn(true);
        sweep.sweep();
        verify(debitService).present(6L, null, Trigger.SCHEDULED, null);
    }
}
