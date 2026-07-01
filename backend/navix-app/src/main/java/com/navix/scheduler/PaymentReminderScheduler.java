package com.navix.scheduler;

import com.navix.common.notification.event.PaymentReminderEvent;
import com.navix.loan.domain.LoanStatus;
import com.navix.loan.entity.Loan;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.service.RepaymentService;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily sweep that nudges borrowers to repay. For every live loan it publishes (at most) one
 * {@link PaymentReminderEvent} per run, which the notification engine turns into an in-app + SMS +
 * email reminder:
 *
 * <ul>
 *   <li><b>Due-soon</b> — from {@value #REMINDER_WINDOW_DAYS} days before the due date through the
 *       day-after-salary grace day: "your payment of ₹X is due in N day(s)" (penalty-free).</li>
 *   <li><b>Overdue</b> — for the first {@value #OVERDUE_BUCKET_DAYS} days past the grace day (the
 *       T0–T7 collections bucket): "your ₹Y is overdue, pay now or penalty + credit-score impact".</li>
 * </ul>
 *
 * <p>A loan whose penalty-aware outstanding has reached 0 is skipped, so reminders stop the moment
 * the borrower pays. The once-a-day cadence is the de-dup (one reminder per loan per day).
 */
@Component
public class PaymentReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(PaymentReminderScheduler.class);

    /** Start nudging this many days before the due date. */
    private static final int REMINDER_WINDOW_DAYS = 7;
    /** The day after salary is still penalty-free (mirrors {@code LoanMath.SALARY_GRACE_DAYS}). */
    private static final int GRACE_DAYS = 1;
    /** Keep nudging for this many days after the grace day, then collections owns it (T0–T7). */
    private static final int OVERDUE_BUCKET_DAYS = 7;

    private final LoanRepository loanRepository;
    private final RepaymentService repaymentService;
    private final ApplicationEventPublisher events;

    public PaymentReminderScheduler(LoanRepository loanRepository, RepaymentService repaymentService,
                                    ApplicationEventPublisher events) {
        this.loanRepository = loanRepository;
        this.repaymentService = repaymentService;
        this.events = events;
    }

    /** Runs daily at 09:00 (override with {@code navix.reminders.cron}). */
    @Scheduled(cron = "${navix.reminders.cron:0 0 9 * * *}")
    public void sendDueAndOverdueReminders() {
        LocalDate today = LocalDate.now();
        List<Loan> live = loanRepository.findByStatusIn(List.of(LoanStatus.ACTIVE, LoanStatus.OVERDUE));
        int sent = 0;
        for (Loan loan : live) {
            try {
                if (sendReminderFor(loan, today)) {
                    sent++;
                }
            } catch (RuntimeException ex) {
                // One bad loan must never sink the rest of the sweep.
                log.warn("Payment-reminder sweep skipped loan {}: {}", loan.getId(), ex.getMessage());
            }
        }
        log.info("Payment-reminder sweep: {} loan(s) scanned, {} reminder(s) queued (asOf={})",
                live.size(), sent, today);
    }

    /** @return true if a reminder event was published for this loan. */
    private boolean sendReminderFor(Loan loan, LocalDate today) {
        if (loan.getDueDate() == null) {
            return false;
        }
        long outstanding = repaymentService.outstandingAsOf(loan.getId(), today);
        if (outstanding <= 0) {
            return false; // fully paid (or settled to zero) — nothing to nudge
        }
        long daysToDue = ChronoUnit.DAYS.between(today, loan.getDueDate()); // >0 before due, <0 once past
        long daysOverdue = -daysToDue;

        if (daysToDue <= REMINDER_WINDOW_DAYS && daysToDue >= -GRACE_DAYS) {
            // 7 days before due → the grace day: penalty-free countdown ("due in N days").
            int days = (int) Math.max(0L, daysToDue);
            events.publishEvent(new PaymentReminderEvent(loan.getId(), loan.getCustomerId(), false, outstanding, days));
            return true;
        }
        if (daysOverdue > GRACE_DAYS && daysOverdue <= GRACE_DAYS + OVERDUE_BUCKET_DAYS) {
            // Past the grace day, for the first 7 overdue days: "overdue, pay now".
            events.publishEvent(new PaymentReminderEvent(loan.getId(), loan.getCustomerId(), true, outstanding, (int) daysOverdue));
            return true;
        }
        return false; // outside both windows (>7 days out, or >7 days overdue → collections)
    }
}
