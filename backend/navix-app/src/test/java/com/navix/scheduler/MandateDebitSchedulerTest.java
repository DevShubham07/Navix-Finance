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
        when(loans.listCollectible(any())).thenReturn(List.of(loan(1, 1)));
        sweep.sweep();
        verify(debitService, never()).present(anyLong(), any(), any(), any());
        verify(debitService, never()).reconcile(anyLong());
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
        when(debits.findByStatusIn(any())).thenReturn(List.of(
                debit(11, 1, 1, Status.IN_PROGRESS, null, today),
                debit(12, 2, 1, Status.SCHEDULED, null, today.minusDays(1)),
                debit(13, 3, 1, Status.SCHEDULED, null, today.plusDays(1))));
        sweep.sweep();
        verify(debitService).reconcile(11L);
        verify(debitService).reconcile(12L);
        verify(debitService, never()).reconcile(13L);
    }

    @Test
    void oneFailingLoanDoesNotStopTheSweep() {
        when(loans.listCollectible(any())).thenReturn(List.of(loan(1, 1), loan(2, 1)));
        when(debitService.present(1L, null, Trigger.SCHEDULED, null)).thenThrow(new RuntimeException("boom"));
        when(debits.findByStatusIn(any())).thenReturn(List.of(debit(11, 9, 1, Status.IN_PROGRESS, null, LocalDate.now(IST))));
        when(debitService.reconcile(11L)).thenThrow(new RuntimeException("boom"));
        sweep.sweep();
        verify(debitService).present(2L, null, Trigger.SCHEDULED, null);
    }
}
