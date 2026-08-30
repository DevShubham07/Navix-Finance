package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.loan.domain.PaymentStatus;
import com.navix.loan.dto.DashboardDtos.TrendPoint;
import com.navix.loan.dto.DashboardDtos.TrendResponse;
import com.navix.loan.entity.ApplicationEvent;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.Payment;
import com.navix.loan.repository.ApplicationEventRepository;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.repository.PaymentRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Mock private ApplicationEventRepository eventRepository;
    @Mock private LoanRepository loanRepository;
    @Mock private PaymentRepository paymentRepository;

    private DashboardService service;

    @BeforeEach
    void setUp() {
        service = new DashboardService(eventRepository, loanRepository, paymentRepository);
    }

    @Test
    void trendsReturnsExactly7PointsInAscendingOrder() {
        LocalDate today = LocalDate.now(IST);
        LocalDate start = today.minusDays(6);

        when(eventRepository.findByActionAndAtGreaterThanEqual("CREATE", start.atStartOfDay(IST).toInstant()))
                .thenReturn(List.of(
                        event(start.atStartOfDay(IST).plusHours(8).toInstant()),
                        event(start.plusDays(3).atStartOfDay(IST).plusHours(10).toInstant())
                ));
        when(loanRepository.findByDisbursedOnGreaterThanEqual(start))
                .thenReturn(List.of(
                        loan(start.plusDays(1)),
                        loan(start.plusDays(1)),
                        loan(start.plusDays(4))
                ));
        when(paymentRepository.findByStatusAndPaidOnGreaterThanEqual(PaymentStatus.VERIFIED, start))
                .thenReturn(List.of(
                        payment(start, PaymentStatus.VERIFIED),
                        payment(start.plusDays(2), PaymentStatus.VERIFIED),
                        payment(start.plusDays(2), PaymentStatus.VERIFIED),
                        payment(start.plusDays(6), PaymentStatus.VERIFIED)
                ));

        TrendResponse response = service.trends(7);

        assertThat(response.points()).hasSize(7);
        assertThat(response.points().get(0).date()).isEqualTo(start.toString());
        assertThat(response.points().get(6).date()).isEqualTo(today.toString());
    }

    @Test
    void trendsCountsApplicationsCorrectly() {
        LocalDate today = LocalDate.now(IST);
        LocalDate start = today.minusDays(6);

        when(eventRepository.findByActionAndAtGreaterThanEqual("CREATE", start.atStartOfDay(IST).toInstant()))
                .thenReturn(List.of(
                        event(start.atStartOfDay(IST).toInstant()),
                        event(start.atStartOfDay(IST).plusHours(12).toInstant()),
                        event(start.plusDays(1).atStartOfDay(IST).toInstant())
                ));
        when(loanRepository.findByDisbursedOnGreaterThanEqual(start)).thenReturn(List.of());
        when(paymentRepository.findByStatusAndPaidOnGreaterThanEqual(PaymentStatus.VERIFIED, start)).thenReturn(List.of());

        TrendResponse response = service.trends(7);

        TrendPoint firstDay = response.points().get(0);
        assertThat(firstDay.applications()).isEqualTo(2);

        TrendPoint secondDay = response.points().get(1);
        assertThat(secondDay.applications()).isEqualTo(1);
    }

    @Test
    void trendsCountsDisbursalsCorrectly() {
        LocalDate today = LocalDate.now(IST);
        LocalDate start = today.minusDays(6);

        when(eventRepository.findByActionAndAtGreaterThanEqual("CREATE", start.atStartOfDay(IST).toInstant()))
                .thenReturn(List.of());
        when(loanRepository.findByDisbursedOnGreaterThanEqual(start))
                .thenReturn(List.of(
                        loan(start),
                        loan(start),
                        loan(start.plusDays(3))
                ));
        when(paymentRepository.findByStatusAndPaidOnGreaterThanEqual(PaymentStatus.VERIFIED, start)).thenReturn(List.of());

        TrendResponse response = service.trends(7);

        assertThat(response.points().get(0).disbursed()).isEqualTo(2);
        assertThat(response.points().get(3).disbursed()).isEqualTo(1);
    }

    @Test
    void trendsCountsVerifiedRepaymentOnly() {
        LocalDate today = LocalDate.now(IST);
        LocalDate start = today.minusDays(6);

        when(eventRepository.findByActionAndAtGreaterThanEqual("CREATE", start.atStartOfDay(IST).toInstant()))
                .thenReturn(List.of());
        when(loanRepository.findByDisbursedOnGreaterThanEqual(start)).thenReturn(List.of());
        when(paymentRepository.findByStatusAndPaidOnGreaterThanEqual(PaymentStatus.VERIFIED, start))
                .thenReturn(List.of(
                        payment(start, PaymentStatus.VERIFIED),
                        payment(start, PaymentStatus.VERIFIED)
                ));

        TrendResponse response = service.trends(7);

        assertThat(response.points().get(0).repaid()).isEqualTo(2);
    }

    @Test
    void trendsIgnoresUnverifiedPayments() {
        LocalDate today = LocalDate.now(IST);
        LocalDate start = today.minusDays(6);

        when(eventRepository.findByActionAndAtGreaterThanEqual("CREATE", start.atStartOfDay(IST).toInstant()))
                .thenReturn(List.of());
        when(loanRepository.findByDisbursedOnGreaterThanEqual(start)).thenReturn(List.of());
        when(paymentRepository.findByStatusAndPaidOnGreaterThanEqual(PaymentStatus.VERIFIED, start))
                .thenReturn(List.of(
                        payment(start, PaymentStatus.VERIFIED),
                        payment(start, PaymentStatus.PENDING_VERIFICATION)
                ));

        TrendResponse response = service.trends(7);

        assertThat(response.points().get(0).repaid()).isEqualTo(1);
    }

    @Test
    void trendsThisWeekAndLastWeekDeltasAreCorrect() {
        LocalDate today = LocalDate.now(IST);
        LocalDate start = today.minusDays(13);

        when(eventRepository.findByActionAndAtGreaterThanEqual("CREATE", start.atStartOfDay(IST).toInstant()))
                .thenReturn(List.of(
                        event(start.atStartOfDay(IST).toInstant()), // day 0 (last-week)
                        event(today.atStartOfDay(IST).toInstant()) // day 13 (this-week)
                ));
        when(loanRepository.findByDisbursedOnGreaterThanEqual(start)).thenReturn(List.of());
        when(paymentRepository.findByStatusAndPaidOnGreaterThanEqual(PaymentStatus.VERIFIED, start)).thenReturn(List.of());

        TrendResponse response = service.trends(14);

        assertThat(response.applicationsLastWeek()).isEqualTo(1);
        assertThat(response.applicationsThisWeek()).isEqualTo(1);
    }

    @Test
    void trendsUsesOnlyBoundedQueries() {
        LocalDate today = LocalDate.now(IST);
        LocalDate start = today.minusDays(6);
        Instant sinceInstant = start.atStartOfDay(IST).toInstant();

        when(eventRepository.findByActionAndAtGreaterThanEqual("CREATE", sinceInstant))
                .thenReturn(List.of());
        when(loanRepository.findByDisbursedOnGreaterThanEqual(start)).thenReturn(List.of());
        when(paymentRepository.findByStatusAndPaidOnGreaterThanEqual(PaymentStatus.VERIFIED, start)).thenReturn(List.of());

        service.trends(7);

        verify(eventRepository).findByActionAndAtGreaterThanEqual(eq("CREATE"), any());
        verify(loanRepository).findByDisbursedOnGreaterThanEqual(start);
        verify(paymentRepository).findByStatusAndPaidOnGreaterThanEqual(PaymentStatus.VERIFIED, start);

        // Verify unbounded queries are NOT called.
        verify(eventRepository, never()).findByAction("CREATE");
        verify(loanRepository, never()).findAll();
        verify(paymentRepository, never()).findAll();
    }

    private ApplicationEvent event(Instant at) {
        ApplicationEvent e = new ApplicationEvent();
        e.setAction("CREATE");
        e.setAt(at);
        return e;
    }

    private Loan loan(LocalDate disbursedOn) {
        Loan l = new Loan();
        l.setDisbursedOn(disbursedOn);
        return l;
    }

    private Payment payment(LocalDate paidOn, PaymentStatus status) {
        Payment p = new Payment();
        p.setPaidOn(paidOn);
        p.setStatus(status);
        return p;
    }
}
