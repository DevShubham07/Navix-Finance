package com.navix.app.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.app.dashboard.DashboardDtos.Position;
import com.navix.app.dashboard.DashboardDtos.Records;
import com.navix.app.dashboard.MetricQueries.GroupLoan;
import com.navix.app.dashboard.MetricQueries.Page;
import com.navix.app.dashboard.MetricQueries.RowKey;
import com.navix.app.dashboard.MetricSql.Metric;
import com.navix.common.exception.BusinessException;
import com.navix.loan.entity.Loan;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.service.RepaymentService;
import com.navix.loan.service.RepaymentService.OutstandingBreakdown;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DashboardPositionTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);

    @Mock private MetricQueries metrics;
    @Mock private BusinessQueries business;
    @Mock private CollectionQueries collection;
    @Mock private TeamQueries team;
    @Mock private com.navix.common.staff.StaffDirectory staffDirectory;
    @Mock private RepaymentService repayments;
    @Mock private LoanRepository loanRepository;
    @InjectMocks private DashboardService service;

    private final DashboardScope admin = new DashboardScope("ADMIN", List.of(), true, "ADMIN", 1L);

    private static Loan loan(long id, long principal, long net) {
        Loan l = new Loan();
        l.setId(id);
        l.setPrincipal(principal);
        l.setNetDisbursed(net);
        return l;
    }

    private static OutstandingBreakdown bd(long out, long interest, long penalty, long verified) {
        return new OutstandingBreakdown(out, interest, penalty, verified, null, 0, 0);
    }

    private static OutstandingBreakdown settled(long out, long interest, long penalty, long verified) {
        return new OutstandingBreakdown(out, interest, penalty, verified, 40_000L, 0, 0);
    }

    /** active (1), overdue with penalty (2), closed (3), settled/capped (4). */
    private void cohort() {
        when(metrics.groupLoans(Metric.DISBURSED, admin, FROM, TO, null)).thenReturn(
                List.of(new GroupLoan("a", 1), new GroupLoan("a", 2), new GroupLoan("b", 3), new GroupLoan("b", 4)));
        pricing();
    }

    private void pricing() {
        List<Loan> loans = List.of(loan(1, 1_000_000, 882_000), loan(2, 500_000, 441_000),
                loan(3, 200_000, 176_400), loan(4, 300_000, 264_600));
        when(loanRepository.findAllById(any())).thenReturn(loans);
        when(repayments.outstandingBreakdownsForAll(any(), any())).thenReturn(Map.of(
                1L, bd(1_270_000, 270_000, 0, 0),
                2L, bd(700_000, 150_000, 50_000, 0),
                3L, bd(0, 60_000, 0, 260_000),
                4L, settled(40_000, 90_000, 0, 290_000)));
    }

    @Test
    void sumsAcrossActiveOverdueClosedAndSettled() {
        cohort();
        Position p = service.position(admin, FROM, TO);
        assertThat(p.loans()).isEqualTo(4);
        assertThat(p.principalPaise()).isEqualTo(2_000_000);
        assertThat(p.netDisbursedPaise()).isEqualTo(1_764_000);
        assertThat(p.receivablePaise()).isEqualTo(2_000_000 + 570_000); // principal + interest, no penalty
        assertThat(p.penaltyPaise()).isEqualTo(50_000);
        assertThat(p.receivedPaise()).isEqualTo(550_000);
        assertThat(p.waivedPaise()).isEqualTo(60_000); // loan 4: 300k + 90k - 290k received - 40k still owed
        assertThat(p.pendingPaise()).isEqualTo(2_010_000); // server figure, never recomputed
        assertThat(p.receivablePaise() + p.penaltyPaise() - p.receivedPaise() - p.waivedPaise())
                .isEqualTo(p.pendingPaise());
        assertThat(p.overpaidPaise()).isZero();
    }

    @Test
    void overpaidLoanContributesItsExcessAndTheIdentityHolds() {
        when(metrics.groupLoans(Metric.DISBURSED, admin, FROM, TO, null)).thenReturn(List.of(new GroupLoan("a", 5)));
        when(loanRepository.findAllById(any())).thenReturn(List.of(loan(5, 100_000, 88_200)));
        // owed 100k + 27k interest = 127k, paid 130k, outstanding floored at 0
        when(repayments.outstandingBreakdownsForAll(any(), any())).thenReturn(Map.of(5L, bd(0, 27_000, 0, 130_000)));
        Position p = service.position(admin, FROM, TO);
        assertThat(p.overpaidPaise()).isEqualTo(3_000);
        assertThat(p.receivablePaise() + p.penaltyPaise() - p.receivedPaise() - p.waivedPaise() + p.overpaidPaise())
                .isEqualTo(p.pendingPaise());
    }

    @Test
    void emptyPeriodIsAllZeros() {
        when(metrics.groupLoans(Metric.DISBURSED, admin, FROM, TO, null)).thenReturn(List.of());
        assertThat(service.position(admin, FROM, TO))
                .isEqualTo(new Position(0, 0, 0, 0, 0, 0, 0, 0, 0));
    }

    @Test
    void cohortIsTheDisbursedMetricInThePeriod() {
        when(metrics.groupLoans(Metric.DISBURSED, admin, FROM, TO, null)).thenReturn(List.of());
        service.position(admin, FROM, TO);
        verify(metrics).groupLoans(Metric.DISBURSED, admin, FROM, TO, null);
        verify(repayments, never()).outstandingBreakdownsForAll(any(), any());
    }

    @Test
    void snapshotIsAdminOnly() {
        DashboardScope credit = new DashboardScope("CREDIT_HEAD", List.of(1L), false, "CREDIT_HEAD", 1L);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.snapshot(credit, FROM, TO))
                .isInstanceOf(BusinessException.class);
    }

    private Records drill(Metric m) {
        pricing();
        when(metrics.records(eq(m), eq(admin), eq(FROM), eq(TO), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new Page(List.of(new RowKey(11, 1L, "FRESH", 7L, null), new RowKey(12, 2L, "FRESH", 7L, null),
                        new RowKey(13, 3L, "FRESH", 7L, null), new RowKey(14, 4L, "FRESH", 7L, null)),
                        4, 4, 0, 28, List.of(1L, 2L, 3L, 4L)));
        return service.records(admin, m.name(), null, null, null, 0, 50, FROM, TO);
    }

    private static List<Long> amounts(Records r) {
        return r.rows().stream().map(DashboardDtos.RecordRow::amountPaise).toList();
    }

    @Test
    void drillReturnsPerLoanFigureForEachKey() {
        assertThat(amounts(drill(Metric.POSITION_RECEIVABLE))).containsExactly(1_270_000L, 650_000L, 260_000L, 390_000L);
        assertThat(drill(Metric.POSITION_RECEIVABLE).sumPaise()).isEqualTo(2_570_000L);
        assertThat(amounts(drill(Metric.POSITION_PENALTY))).containsExactly(0L, 50_000L, 0L, 0L);
        assertThat(amounts(drill(Metric.POSITION_RECEIVED))).containsExactly(0L, 0L, 260_000L, 290_000L);
        assertThat(drill(Metric.POSITION_RECEIVED).sumPaise()).isEqualTo(550_000L);
        Records pending = drill(Metric.POSITION_PENDING);
        assertThat(amounts(pending)).containsExactly(1_270_000L, 700_000L, 0L, 40_000L);
        assertThat(pending.sumPaise()).isEqualTo(2_010_000L);
    }

    @Test
    void principalAndNetComeFromSql() {
        // amount is the SQL column (7 here); no owed-sum, so the page sum is the SQL sum
        Records r = drill(Metric.POSITION_PRINCIPAL);
        assertThat(amounts(r)).containsOnly(7L);
        assertThat(r.sumPaise()).isEqualTo(28L);
    }
}
