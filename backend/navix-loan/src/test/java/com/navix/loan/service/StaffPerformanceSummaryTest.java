package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.staff.StaffDirectory;
import com.navix.common.staff.StaffSummary;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.entity.ApplicationEvent;
import com.navix.loan.repository.ApplicationEventRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.service.DecisionHistoryService.StaffPerformanceRow;
import com.navix.loan.service.DecisionHistoryService.StaffPerformanceSummary;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link DecisionHistoryService#summary(LocalDate, LocalDate)} — the staff-performance aggregation.
 *
 * <p>The money paths here decide what a manager believes about an employee, so the cases that matter
 * are the ones where a wrong number would be believable: a system auto-reject credited to a human, a
 * dead pre-V45 action counted as an approval, or a 0 rendered where the truth is "we cannot measure
 * this".
 */
@ExtendWith(MockitoExtension.class)
class StaffPerformanceSummaryTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final CurrentActor ADMIN = new CurrentActor("1", "Root", "ADMIN");
    private static final CurrentActor CREDIT_HEAD = new CurrentActor("42", "Neha Rao", "CREDIT_HEAD");
    private static final CurrentActor EXECUTIVE = new CurrentActor("41", "Ravi Nair", "CREDIT_EXECUTIVE");

    private static final StaffSummary RAVI = new StaffSummary(41L, "Ravi Nair", "CREDIT_EXECUTIVE", true);
    private static final StaffSummary NEHA = new StaffSummary(42L, "Neha Rao", "CREDIT_HEAD", true);

    @Mock
    private ApplicationEventRepository eventRepository;
    @Mock
    private CustomerProfileRepository profileRepository;
    @Mock
    private LoanApplicationRepository applicationRepository;
    @Mock
    private StaffDirectory staffDirectory;

    private DecisionHistoryService service;

    @BeforeEach
    void setUp() {
        service = new DecisionHistoryService(eventRepository, profileRepository,
                applicationRepository, staffDirectory);
        lenient().when(applicationRepository.countGroupByAssignedExecutive(any())).thenReturn(List.of());
        ActorContext.set(ADMIN);
    }

    @AfterEach
    void tearDown() {
        ActorContext.clear();
    }

    /** IST instant for a given date + hour, so window assertions read in the product's timezone. */
    private static Instant ist(String date, int hour) {
        return LocalDate.parse(date).atStartOfDay(IST).plusHours(hour).toInstant();
    }

    private static ApplicationEvent event(String actorId, String action, Instant at, long appId, String notes) {
        ApplicationEvent e = new ApplicationEvent();
        e.setActorId(actorId);
        e.setAction(action);
        e.setAt(at);
        e.setApplicationId(appId);
        e.setNotes(notes);
        e.setToStatus(ApplicationStatus.SANCTIONED);
        return e;
    }

    private void rosterIsEveryone(StaffSummary... staff) {
        when(staffDirectory.listEveryone()).thenReturn(List.of(staff));
    }

    private StaffPerformanceRow rowFor(StaffPerformanceSummary s, long staffId) {
        return s.rows().stream().filter(r -> r.staffId() == staffId).findFirst().orElseThrow();
    }

    @Test
    void bucketsApprovalsAndRejections() {
        rosterIsEveryone(RAVI);
        when(eventRepository.findForActorsInWindow(anyCollection(), any(), any())).thenReturn(List.of(
                event("41", "SANCTION", ist("2026-08-10", 10), 1L, "amountPaise=500000"),
                event("41", "KYC_APPROVE", ist("2026-08-10", 11), 2L, null),
                event("41", "REJECT_LEAD", ist("2026-08-10", 12), 3L, "Credit score issue"),
                event("41", "MARK_PENDING", ist("2026-08-10", 13), 4L, "Awaiting payslip")));

        StaffPerformanceRow row = rowFor(service.summary(null, null), 41L);

        assertThat(row.accepted()).isEqualTo(2);
        assertThat(row.rejected()).isEqualTo(1);
        // MARK_PENDING is neither — but it is still work, so it counts toward total actions.
        assertThat(row.totalActions()).isEqualTo(4);
    }

    @Test
    void excludesSystemAutoRejectsFromAHumansRejectionCount() {
        rosterIsEveryone(RAVI);
        when(eventRepository.findForActorsInWindow(anyCollection(), any(), any())).thenReturn(List.of(
                event("41", "REJECT_LEAD", ist("2026-08-10", 10), 1L, null),
                event("41", "AUTO_REJECT_BUREAU_THIN_FILE", ist("2026-08-10", 11), 2L, null),
                event("41", "AUTO_REJECT_BLOCKLISTED", ist("2026-08-10", 12), 3L, null)));

        StaffPerformanceSummary summary = service.summary(null, null);
        StaffPerformanceRow row = rowFor(summary, 41L);

        assertThat(row.rejected()).as("auto-rejects are the system's, not the staffer's").isEqualTo(1);
        // ...and excluded from the total too, otherwise the dashboard would credit them work they
        // never did and disagree with the per-action list on /staff/my-decisions it links into.
        assertThat(row.totalActions()).isEqualTo(1);
        assertThat(summary.daily()).extracting(DecisionHistoryService.ActivityPoint::actions)
                .containsExactly(1L);
    }

    @Test
    void doesNotCountActionsNothingHasEmittedSinceV45() {
        rosterIsEveryone(RAVI);
        when(eventRepository.findForActorsInWindow(anyCollection(), any(), any())).thenReturn(List.of(
                event("41", "EXEC_APPROVE", ist("2026-08-10", 10), 1L, null),
                event("41", "HEAD_APPROVE", ist("2026-08-10", 11), 2L, null),
                event("41", "DISB_ACCEPT", ist("2026-08-10", 12), 3L, null)));

        StaffPerformanceRow row = rowFor(service.summary(null, null), 41L);

        assertThat(row.accepted())
                .as("dead pre-V45/V48 actions must not be reported as approvals")
                .isZero();
    }

    @Test
    void sumsMoneyOnlyForApprovals() {
        rosterIsEveryone(RAVI);
        when(eventRepository.findForActorsInWindow(anyCollection(), any(), any())).thenReturn(List.of(
                event("41", "SANCTION", ist("2026-08-10", 10), 1L, "amountPaise=500000 salaryCreditDay=1"),
                event("41", "SANCTION", ist("2026-08-11", 10), 2L, "amountPaise=250000"),
                event("41", "REJECT_LEAD", ist("2026-08-12", 10), 3L, "amountPaise=999999")));

        StaffPerformanceRow row = rowFor(service.summary(null, null), 41L);

        assertThat(row.moneyPaise()).isEqualTo(750_000L);
    }

    @Test
    void countsDistinctActiveDaysAndTheDaysFirstAndLastAction() {
        rosterIsEveryone(RAVI);
        Instant early = ist("2026-08-10", 9);
        Instant late = ist("2026-08-11", 19);
        when(eventRepository.findForActorsInWindow(anyCollection(), any(), any())).thenReturn(List.of(
                event("41", "SANCTION", early, 1L, null),
                event("41", "SANCTION", ist("2026-08-10", 17), 2L, null),
                event("41", "SANCTION", late, 3L, null)));

        StaffPerformanceRow row = rowFor(service.summary(null, null), 41L);

        assertThat(row.activeDays()).isEqualTo(2);
        assertThat(row.firstActionAt()).isEqualTo(early);
        assertThat(row.lastActionAt()).isEqualTo(late);
    }

    @Test
    void averagesTurnaroundFromTheAssignmentThatPrecededTheDecision() {
        rosterIsEveryone(RAVI);
        when(eventRepository.findForActorsInWindow(anyCollection(), any(), any())).thenReturn(List.of(
                event("41", "ASSIGN", ist("2026-08-10", 9), 1L, null),
                event("41", "SANCTION", ist("2026-08-10", 11), 1L, null),   // 120 min
                event("41", "ASSIGN", ist("2026-08-11", 9), 2L, null),
                event("41", "REJECT_LEAD", ist("2026-08-11", 13), 2L, null))); // 240 min

        StaffPerformanceRow row = rowFor(service.summary(null, null), 41L);

        assertThat(row.avgTurnaroundMinutes()).isEqualTo(180L);
    }

    @Test
    void reportsUnknownTurnaroundAsNullRatherThanZero() {
        rosterIsEveryone(RAVI);
        // Decided, but the assignment happened before the window — there is no clock-start to measure
        // from. Zero would read as "instant", which is a different and flattering claim.
        when(eventRepository.findForActorsInWindow(anyCollection(), any(), any())).thenReturn(List.of(
                event("41", "SANCTION", ist("2026-08-10", 11), 1L, null)));

        StaffPerformanceRow row = rowFor(service.summary(null, null), 41L);

        assertThat(row.avgTurnaroundMinutes()).isNull();
    }

    @Test
    void keepsAnEmployeeWithNoActivityOnTheRoster() {
        rosterIsEveryone(RAVI, NEHA);
        when(eventRepository.findForActorsInWindow(anyCollection(), any(), any())).thenReturn(List.of(
                event("41", "SANCTION", ist("2026-08-10", 10), 1L, null)));

        StaffPerformanceSummary summary = service.summary(null, null);

        assertThat(summary.rows()).hasSize(2);
        assertThat(rowFor(summary, 42L).totalActions()).isZero();
    }

    @Test
    void convertsTheDateWindowToAHalfOpenIstRange() {
        rosterIsEveryone(RAVI);
        when(eventRepository.findForActorsInWindow(anyCollection(), any(), any())).thenReturn(List.of());
        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);

        service.summary(LocalDate.parse("2026-08-10"), LocalDate.parse("2026-08-10"));

        org.mockito.Mockito.verify(eventRepository)
                .findForActorsInWindow(anyCollection(), from.capture(), to.capture());
        // A single-day window must cover that whole IST day and stop at the next midnight, so an
        // 11pm action lands inside it rather than falling into the next day (or into UTC's).
        assertThat(from.getValue()).isEqualTo(ist("2026-08-10", 0));
        assertThat(to.getValue()).isEqualTo(ist("2026-08-11", 0));
    }

    @Test
    void allTimeSpansTheRealLimitsOfTheData() {
        rosterIsEveryone(RAVI);
        when(eventRepository.findForActorsInWindow(anyCollection(), any(), any())).thenReturn(List.of());
        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        Instant before = Instant.now();

        service.summary(null, null);

        org.mockito.Mockito.verify(eventRepository)
                .findForActorsInWindow(anyCollection(), from.capture(), to.capture());
        // Real bounds, not nulls: Postgres cannot type-infer a bare null bound, and the data has
        // genuine limits — nothing predates the epoch, nothing is logged in the future.
        assertThat(from.getValue()).isEqualTo(Instant.EPOCH);
        assertThat(to.getValue()).isBetween(before, Instant.now());
    }

    @Test
    void buildsTheDailyTrendAcrossTheWholeRoster() {
        rosterIsEveryone(RAVI, NEHA);
        when(eventRepository.findForActorsInWindow(anyCollection(), any(), any())).thenReturn(List.of(
                event("41", "SANCTION", ist("2026-08-10", 10), 1L, null),
                event("42", "KYC_APPROVE", ist("2026-08-10", 12), 2L, null),
                event("41", "SANCTION", ist("2026-08-12", 10), 3L, null)));

        StaffPerformanceSummary summary = service.summary(null, null);

        assertThat(summary.daily()).containsExactly(
                new DecisionHistoryService.ActivityPoint(LocalDate.parse("2026-08-10"), 2L),
                new DecisionHistoryService.ActivityPoint(LocalDate.parse("2026-08-12"), 1L));
    }

    @Test
    void adminSeesTheWholeCompany() {
        rosterIsEveryone(RAVI, NEHA);
        when(eventRepository.findForActorsInWindow(anyCollection(), any(), any())).thenReturn(List.of());

        assertThat(service.summary(null, null).rows()).hasSize(2);
    }

    @Test
    void headSeesOnlyTheirOwnTeam() {
        ActorContext.set(CREDIT_HEAD);
        when(staffDirectory.listActive("CREDIT_HEAD")).thenReturn(List.of(NEHA));
        when(staffDirectory.listActive("CREDIT_EXECUTIVE")).thenReturn(List.of(RAVI));
        when(eventRepository.findForActorsInWindow(anyCollection(), any(), any())).thenReturn(List.of());

        StaffPerformanceSummary summary = service.summary(null, null);

        assertThat(summary.rows()).extracting(StaffPerformanceRow::staffId)
                .containsExactlyInAnyOrder(41L, 42L);
        // Never the whole company — a Head must not see other teams' numbers.
        org.mockito.Mockito.verify(staffDirectory, org.mockito.Mockito.never()).listEveryone();
    }

    @Test
    void anIndividualContributorSeesOnlyThemselves() {
        ActorContext.set(EXECUTIVE);
        when(staffDirectory.findStaff(41L)).thenReturn(Optional.of(RAVI));
        when(eventRepository.findForActorsInWindow(anyCollection(), any(), any())).thenReturn(List.of());

        StaffPerformanceSummary summary = service.summary(null, null);

        assertThat(summary.rows()).extracting(StaffPerformanceRow::staffId).containsExactly(41L);
    }
}
