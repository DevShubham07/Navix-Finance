package com.navix.app.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.app.dashboard.CollectionQueries.Collected;
import com.navix.app.dashboard.CollectionQueries.CountAmount;
import com.navix.app.dashboard.DashboardDtos.TeamDay;
import com.navix.app.dashboard.DashboardDtos.TeamDayRow;
import com.navix.common.exception.BusinessException;
import com.navix.common.staff.StaffDirectory;
import com.navix.common.staff.StaffSummary;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.service.RepaymentService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Assembly of the Collection Head's "team today" tracker from the per-officer day queries: every
 * active executive always has a row, a head only when they hold figures, totals sum the rows, and
 * the view gate is the Head's desk (ADMIN or COLLECTION_HEAD).
 */
@ExtendWith(MockitoExtension.class)
class DashboardTeamDayTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 17);

    @Mock private MetricQueries metrics;
    @Mock private BusinessQueries business;
    @Mock private CollectionQueries collection;
    @Mock private TeamQueries team;
    @Mock private StaffDirectory staffDirectory;
    @Mock private RepaymentService repayments;
    @Mock private LoanRepository loanRepository;
    @InjectMocks private DashboardService service;

    private final DashboardScope admin = new DashboardScope("ADMIN", List.of(), true, "ADMIN", 1L);
    private final DashboardScope head = new DashboardScope("COLLECTION_HEAD", List.of(9L, 7L, 8L), false,
            "COLLECTION_HEAD", 9L);

    private static StaffSummary staff(long id, String name, String role) {
        return new StaffSummary(id, name, role, true);
    }

    private void roster() {
        when(staffDirectory.listActive("COLLECTION_EXECUTIVE"))
                .thenReturn(List.of(staff(7, "Rahul", "COLLECTION_EXECUTIVE"), staff(8, "Asha", "COLLECTION_EXECUTIVE")));
        when(staffDirectory.listActive("COLLECTION_HEAD")).thenReturn(List.of(staff(9, "Meera", "COLLECTION_HEAD")));
    }

    private void figures(DashboardScope scope) {
        when(collection.callsLoggedByOfficer(scope, DAY)).thenReturn(Map.of(7L, 12L, 9L, 1L));
        when(collection.promisesDueByOfficer(scope, DAY)).thenReturn(Map.of(7L, new CountAmount(3, 300_000)));
        when(collection.partPaymentsByOfficer(scope, DAY)).thenReturn(Map.of(8L, new CountAmount(2, 50_000)));
        when(collection.collectedByOfficer(scope, DAY, DAY)).thenReturn(List.of(
                new Collected(7, "FRESH", 1, 100_000), new Collected(7, "RELOAN", 1, 25_000),
                new Collected(8, "FRESH", 2, 50_000)));
        when(collection.closedByOfficer(scope, DAY, DAY)).thenReturn(Map.of(7L, 1L));
        when(collection.dueOnByOfficer(scope, DAY)).thenReturn(Map.of(8L, 4L));
    }

    @Test
    void oneRowPerExecutiveWithTheHeadOnlyWhenTheyHoldFigures() {
        roster();
        figures(head);
        TeamDay day = service.collectionsTeamDay(head, DAY);

        assertThat(day.date()).isEqualTo("2026-10-17");
        assertThat(day.rows()).extracting(TeamDayRow::name).containsExactly("Asha", "Meera", "Rahul");
        TeamDayRow rahul = day.rows().get(2);
        assertThat(rahul.calls()).isEqualTo(12);
        assertThat(rahul.promisesDue()).isEqualTo(3);
        assertThat(rahul.promisesDuePaise()).isEqualTo(300_000);
        assertThat(rahul.collectedPaise()).isEqualTo(125_000); // both segments summed
        assertThat(rahul.closed()).isEqualTo(1);
        assertThat(rahul.role()).isEqualTo("COLLECTION_EXECUTIVE");
        TeamDayRow asha = day.rows().get(0);
        assertThat(asha.partPayments()).isEqualTo(2);
        assertThat(asha.partPaymentsPaise()).isEqualTo(50_000);
        assertThat(asha.dueCount()).isEqualTo(4);
        TeamDayRow meera = day.rows().get(1);
        assertThat(meera.role()).isEqualTo("COLLECTION_HEAD");
        assertThat(meera.calls()).isEqualTo(1);
    }

    @Test
    void totalsSumEveryRow() {
        roster();
        figures(head);
        TeamDayRow t = service.collectionsTeamDay(head, DAY).totals();
        assertThat(t.staffId()).isNull();
        assertThat(t.name()).isEqualTo("Total");
        assertThat(t.calls()).isEqualTo(13);
        assertThat(t.promisesDue()).isEqualTo(3);
        assertThat(t.promisesDuePaise()).isEqualTo(300_000);
        assertThat(t.partPayments()).isEqualTo(2);
        assertThat(t.partPaymentsPaise()).isEqualTo(50_000);
        assertThat(t.collectedPaise()).isEqualTo(175_000);
        assertThat(t.closed()).isEqualTo(1);
        assertThat(t.dueCount()).isEqualTo(4);
    }

    @Test
    void aHeadWithNothingThatDayIsLeftOutButAnIdleExecutiveStays() {
        roster();
        when(collection.collectedByOfficer(admin, DAY, DAY)).thenReturn(List.of());
        TeamDay day = service.collectionsTeamDay(admin, DAY);
        assertThat(day.rows()).extracting(TeamDayRow::name).containsExactly("Asha", "Rahul");
        assertThat(day.rows()).allSatisfy(r -> assertThat(r.calls() + r.collectedPaise() + r.dueCount()).isZero());
        assertThat(day.totals()).isEqualTo(new TeamDayRow(null, "Total", null, 0, 0, 0, 0, 0, 0, 0, 0));
    }

    @Test
    void anExplicitStaffRequestNarrowsTheRoster() {
        roster();
        DashboardScope one = new DashboardScope("COLLECTION_HEAD", List.of(8L), false, "COLLECTION_HEAD", 9L);
        when(collection.collectedByOfficer(one, DAY, DAY)).thenReturn(List.of());
        assertThat(service.collectionsTeamDay(one, DAY).rows()).extracting(TeamDayRow::staffId).containsExactly(8L);
    }

    @Test
    void onlyTheHeadsDeskMayRead() {
        DashboardScope exec = new DashboardScope("COLLECTION_EXECUTIVE", List.of(7L), false, "COLLECTION_EXECUTIVE", 7L);
        assertThatThrownBy(() -> service.collectionsTeamDay(exec, DAY)).isInstanceOf(BusinessException.class);
        DashboardScope credit = new DashboardScope("CREDIT_HEAD", List.of(1L), false, "CREDIT_HEAD", 1L);
        assertThatThrownBy(() -> service.collectionsTeamDay(credit, DAY)).isInstanceOf(BusinessException.class);
        verify(collection, never()).callsLoggedByOfficer(any(), any());
    }
}
