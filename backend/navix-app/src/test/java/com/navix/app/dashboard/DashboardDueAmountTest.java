package com.navix.app.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.navix.app.dashboard.BusinessQueries.MonthCollection;
import com.navix.app.dashboard.DashboardDtos.MonthRow;
import com.navix.app.dashboard.DashboardDtos.TargetRow;
import com.navix.app.dashboard.MetricQueries.GroupLoan;
import com.navix.app.dashboard.MetricSql.Metric;
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

/**
 * The collection widgets' denominator. ₹10,000 principal, 30-day tenure, 1%/day: the contractual
 * total_repayable stored at disbursal is ₹13,000. What the borrower actually had to pay depends on
 * when they paid — and a collection % must be measured against that, not the ₹13,000.
 */
@ExtendWith(MockitoExtension.class)
class DashboardDueAmountTest {

    private static final long P = 1_000_000;          // ₹10,000
    private static final long CONTRACT = 1_300_000;   // ₹13,000 = P + 30 days × 1%

    private static Loan loan(long id, LocalDate closedOn) {
        Loan l = new Loan();
        l.setId(id);
        l.setPrincipal(P);
        l.setTotalRepayable(CONTRACT);
        l.setClosedOn(closedOn);
        return l;
    }

    private static OutstandingBreakdown bd(long outstanding, long interest, long penalty, long verified, Long settled) {
        return new OutstandingBreakdown(outstanding, interest, penalty, verified, settled, 0, 0);
    }

    @Test
    void prepaidOnDayTenOwedOnlyTenDaysOfInterest() {
        // Repaid ₹11,000 on day 10 and closed: fully collected, so the due amount is ₹11,000, not ₹13,000.
        assertThat(DashboardService.dueAmount(loan(1, LocalDate.of(2026, 9, 11)), bd(0, 100_000, 0, 1_100_000, null)))
                .isEqualTo(1_100_000);
    }

    @Test
    void paidFiveDaysLateOwedGraceInterestAndPenalty() {
        // 31 days interest (incl. the grace day) + 4 penalty days at 2%: ₹13,900.
        assertThat(DashboardService.dueAmount(loan(2, LocalDate.of(2026, 10, 6)), bd(0, 310_000, 80_000, 1_390_000, null)))
                .isEqualTo(1_390_000);
    }

    @Test
    void openLoanBeforeItsDueDateIsStillExpectedToPayTheContract() {
        // Day 12 of 30, nothing paid: ₹11,200 has accrued, but ₹13,000 falls due on the due date.
        assertThat(DashboardService.dueAmount(loan(3, null), bd(1_120_000, 120_000, 0, 0, null))).isEqualTo(CONTRACT);
    }

    @Test
    void openOverdueLoanOwesMoreThanTheContract() {
        assertThat(DashboardService.dueAmount(loan(4, null), bd(1_490_000, 310_000, 180_000, 0, null)))
                .isEqualTo(1_490_000);
    }

    @Test
    void settledLoanKeepsTheWaiverVisibleAsAShortfall() {
        // Owed ₹13,100, settled for ₹9,000: due stays ₹13,100 so the collection % shows the ₹4,100 written off.
        assertThat(DashboardService.dueAmount(loan(5, LocalDate.of(2026, 10, 20)), bd(0, 310_000, 0, 900_000, 900_000L)))
                .isEqualTo(1_310_000);
    }

    // ---- through the monthly widget ---------------------------------------------------------

    @Mock private MetricQueries metrics;
    @Mock private BusinessQueries business;
    @Mock private CollectionQueries collection;
    @Mock private TeamQueries team;
    @Mock private com.navix.common.staff.StaffDirectory staffDirectory;
    @Mock private RepaymentService repayments;
    @Mock private LoanRepository loanRepository;
    @InjectMocks private DashboardService service;

    private final DashboardScope admin = new DashboardScope("ADMIN", List.of(), true, "ADMIN", 1L);

    @Test
    void monthlyCollectionIsAHundredPercentWhenAPrepaidLoanIsFullyRepaid() {
        LocalDate first = LocalDate.of(2026, 9, 1);
        LocalDate last = LocalDate.of(2026, 9, 30);
        // The SQL still reports the contractual ₹13,000 as "repayable"; ₹11,000 was collected.
        when(business.monthlyCollection(admin, first, last))
                .thenReturn(Map.of("2026-09", new MonthCollection(1, CONTRACT, 1_100_000, 1)));
        when(business.targets(any(), any())).thenReturn(List.of(new TargetRow("2026-09", null, 8800)));
        when(business.disbursedByMonth(first, last)).thenReturn(Map.of());
        when(business.marketing(any(), any())).thenReturn(Map.of());
        when(metrics.aggregateByGrp(eq(Metric.RELOAN_RETENTION), any(), any(), any())).thenReturn(Map.of());
        when(metrics.groupLoans(Metric.COLLECTION_MONTH, admin, first, last, null))
                .thenReturn(List.of(new GroupLoan("2026-09", 1)));
        Loan prepaid = loan(1, LocalDate.of(2026, 9, 11));
        when(loanRepository.findAllById(any())).thenReturn(List.of(prepaid));
        when(repayments.outstandingBreakdownsForAll(any(), any()))
                .thenReturn(Map.of(1L, bd(0, 100_000, 0, 1_100_000, null)));

        MonthRow row = service.monthly(admin, first, last, 1).months().get(0);

        assertThat(row.totalRepayablePaise()).isEqualTo(1_100_000);
        assertThat(row.collectionPct()).isEqualTo(1.0);          // was 84.6% against ₹13,000
        assertThat(row.deficitAmountPaise()).isZero();
    }
}
