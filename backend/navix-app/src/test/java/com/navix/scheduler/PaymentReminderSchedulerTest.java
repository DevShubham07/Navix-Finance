package com.navix.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.notification.event.PaymentReminderEvent;
import com.navix.loan.entity.Loan;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.service.RepaymentService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The daily payment-reminder sweep: due-soon (7 days before through the grace day), overdue (the 7
 * days past grace), skip-when-paid, and skip outside both windows.
 */
@ExtendWith(MockitoExtension.class)
class PaymentReminderSchedulerTest {

    @Mock private LoanRepository loanRepository;
    @Mock private RepaymentService repaymentService;
    @Mock private ApplicationEventPublisher events;

    private final LocalDate today = LocalDate.now();

    private Loan loan(long id, long customerId, LocalDate due) {
        Loan l = new Loan();
        l.setId(id);
        l.setCustomerId(customerId);
        l.setDueDate(due);
        return l;
    }

    private PaymentReminderScheduler scheduler() {
        return new PaymentReminderScheduler(loanRepository, repaymentService, events);
    }

    @Test
    void publishesDueSoonReminderInsideTheWindow() {
        when(loanRepository.findByStatusIn(any())).thenReturn(List.of(loan(1L, 100L, today.plusDays(3))));
        when(repaymentService.outstandingAsOf(eq(1L), any())).thenReturn(500_000L);

        scheduler().sendDueAndOverdueReminders();

        ArgumentCaptor<PaymentReminderEvent> ev = ArgumentCaptor.forClass(PaymentReminderEvent.class);
        verify(events).publishEvent(ev.capture());
        PaymentReminderEvent e = ev.getValue();
        org.assertj.core.api.Assertions.assertThat(e.loanId()).isEqualTo(1L);
        org.assertj.core.api.Assertions.assertThat(e.overdue()).isFalse();
        org.assertj.core.api.Assertions.assertThat(e.days()).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(e.outstandingPaise()).isEqualTo(500_000L);
    }

    @Test
    void publishesOverdueReminderPastTheGraceDay() {
        // 4 days past due (past the 1-day grace) → overdue reminder, daysOverdue = 4.
        when(loanRepository.findByStatusIn(any())).thenReturn(List.of(loan(2L, 200L, today.minusDays(4))));
        when(repaymentService.outstandingAsOf(eq(2L), any())).thenReturn(750_000L);

        scheduler().sendDueAndOverdueReminders();

        ArgumentCaptor<PaymentReminderEvent> ev = ArgumentCaptor.forClass(PaymentReminderEvent.class);
        verify(events).publishEvent(ev.capture());
        org.assertj.core.api.Assertions.assertThat(ev.getValue().overdue()).isTrue();
        org.assertj.core.api.Assertions.assertThat(ev.getValue().days()).isEqualTo(4);
    }

    @Test
    void skipsLoansThatAreFullyPaid() {
        when(loanRepository.findByStatusIn(any())).thenReturn(List.of(loan(3L, 300L, today.plusDays(2))));
        when(repaymentService.outstandingAsOf(eq(3L), any())).thenReturn(0L);

        scheduler().sendDueAndOverdueReminders();

        verify(events, never()).publishEvent(any(PaymentReminderEvent.class));
    }

    @Test
    void skipsLoansOutsideBothWindows() {
        // 20 days away (before the 7-day window) and 20 days overdue (past the 7-day bucket).
        when(loanRepository.findByStatusIn(any()))
                .thenReturn(List.of(loan(4L, 400L, today.plusDays(20)), loan(5L, 500L, today.minusDays(20))));
        when(repaymentService.outstandingAsOf(anyLong(), any())).thenReturn(100_000L);

        scheduler().sendDueAndOverdueReminders();

        verify(events, never()).publishEvent(any(PaymentReminderEvent.class));
    }

    @Test
    void sendsOneReminderEachForAMixOfLoans() {
        when(loanRepository.findByStatusIn(any())).thenReturn(List.of(
                loan(1L, 100L, today.plusDays(7)),   // due-soon (edge of window)
                loan(2L, 200L, today.minusDays(3)),  // overdue
                loan(3L, 300L, today.plusDays(2)),   // paid → skipped
                loan(4L, 400L, today.plusDays(40)))); // far out → skipped
        when(repaymentService.outstandingAsOf(eq(1L), any())).thenReturn(100_000L);
        when(repaymentService.outstandingAsOf(eq(2L), any())).thenReturn(100_000L);
        when(repaymentService.outstandingAsOf(eq(3L), any())).thenReturn(0L);
        lenient().when(repaymentService.outstandingAsOf(eq(4L), any())).thenReturn(100_000L);

        scheduler().sendDueAndOverdueReminders();

        verify(events, times(2)).publishEvent(any(PaymentReminderEvent.class));
    }
}
