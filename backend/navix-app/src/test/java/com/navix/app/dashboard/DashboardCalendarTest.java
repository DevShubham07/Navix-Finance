package com.navix.app.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.navix.app.dashboard.DashboardDtos.Calendar;
import com.navix.app.dashboard.MetricQueries.Agg;
import com.navix.app.dashboard.MetricSql.Metric;
import com.navix.common.exception.BusinessException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Month totals: a sum of the days for DUE/DISBURSED/BIRTHDAY, the last day's snapshot for ACTIVE. */
@ExtendWith(MockitoExtension.class)
class DashboardCalendarTest {

    @Mock private MetricQueries metrics;
    @Mock private BusinessQueries business;
    @Mock private CollectionQueries collection;
    @Mock private TeamQueries team;
    @Mock private com.navix.common.staff.StaffDirectory staffDirectory;
    @Mock private com.navix.loan.service.RepaymentService repayments;
    @Mock private com.navix.loan.repository.LoanRepository loanRepository;
    @InjectMocks private DashboardService service;

    private final DashboardScope admin = new DashboardScope("ADMIN", List.of(), true, "ADMIN", 1L);

    private static Agg agg(long count, long amount) {
        return new Agg(count, count, 0, amount, amount, 0);
    }

    @Test
    void activeTotalsAreTheLastDayOfAPastMonthNotTheSumOfItsDays() {
        when(metrics.aggregateByGrp(eq(Metric.CALENDAR_ACTIVE), eq(admin), eq(LocalDate.of(2026, 9, 1)),
                eq(LocalDate.of(2026, 9, 30)))).thenReturn(Map.of(
                "2026-09-01", agg(3, 3_000_000),
                "2026-09-15", agg(5, 5_000_000),
                "2026-09-30", agg(2, 1_500_000)));
        Calendar c = service.calendar(admin, "2026-09", "ACTIVE");
        assertThat(c.mode()).isEqualTo("ACTIVE");
        assertThat(c.snapshot()).isTrue();
        assertThat(c.daysWithData()).isEqualTo(3);
        assertThat(c.totalCount()).isEqualTo(2);
        assertThat(c.totalAmountPaise()).isEqualTo(1_500_000L);
        assertThat(c.days()).hasSize(30);
        assertThat(c.days().get(14).count()).isEqualTo(5);
        assertThat(c.days().get(14).amountPaise()).isEqualTo(5_000_000L);
    }

    @Test
    void activeTotalsForTheCurrentMonthAreTodays() {
        LocalDate today = DashboardService.today();
        YearMonth ym = YearMonth.from(today);
        when(metrics.aggregateByGrp(eq(Metric.CALENDAR_ACTIVE), eq(admin), eq(ym.atDay(1)), eq(ym.atEndOfMonth())))
                .thenReturn(Map.of(
                        ym.atDay(1).toString(), agg(9, 9_000_000),
                        today.toString(), agg(4, 4_000_000),
                        ym.atEndOfMonth().toString(), agg(1, 1_000_000)));
        Calendar c = service.calendar(admin, ym.toString(), "ACTIVE");
        assertThat(c.snapshot()).isTrue();
        assertThat(c.totalCount()).isEqualTo(4);
        assertThat(c.totalAmountPaise()).isEqualTo(4_000_000L);
    }

    @Test
    void sumModesStillAddUpTheMonth() {
        when(metrics.aggregateByGrp(eq(Metric.CALENDAR_DISBURSED), eq(admin), any(), any())).thenReturn(Map.of(
                "2026-09-01", agg(3, 3_000_000),
                "2026-09-30", agg(2, 1_500_000)));
        Calendar c = service.calendar(admin, "2026-09", "DISBURSED");
        assertThat(c.snapshot()).isFalse();
        assertThat(c.totalCount()).isEqualTo(5);
        assertThat(c.totalAmountPaise()).isEqualTo(4_500_000L);
    }

    @Test
    void unknownModeIsRejected() {
        assertThatThrownBy(() -> service.calendar(admin, "2026-09", "OPEN"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ACTIVE");
    }
}
