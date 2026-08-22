package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.navix.common.collections.CollectionActivityDirectory;
import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.staff.StaffDirectory;
import com.navix.common.staff.StaffSummary;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.domain.PaymentMethod;
import com.navix.loan.domain.PaymentStatus;
import com.navix.loan.entity.ApplicationEvent;
import com.navix.loan.entity.Payment;
import com.navix.loan.repository.ApplicationEventRepository;
import com.navix.loan.repository.CustomerCallLogRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.repository.PaymentRepository;
import com.navix.loan.service.DecisionHistoryService.StaffPerformanceRow;
import com.navix.loan.service.DecisionHistoryService.StaffPerformanceSummary;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
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
    private CustomerCallLogRepository callLogRepository;
    @Mock
    private CollectionActivityDirectory collectionActivity;
    @Mock
    private StaffDirectory staffDirectory;
    @Mock
    private PaymentRepository paymentRepository;

    private DecisionHistoryService service;

    @BeforeEach
    void setUp() {
        service = new DecisionHistoryService(eventRepository, profileRepository,
                applicationRepository, callLogRepository, collectionActivity, staffDirectory, paymentRepository);
        lenient().when(applicationRepository.countGroupByAssignedExecutive(any())).thenReturn(List.of());
        lenient().when(callLogRepository.countByStaffInWindow(anyCollection(), any(), any()))
                .thenReturn(List.of());
        lenient().when(collectionActivity.callCountsByStaff(anyCollection(), any(), any()))
                .thenReturn(Map.of());
        lenient().when(paymentRepository.findDecidedByInWindow(anyCollection(), any(), any()))
                .thenReturn(List.of());
        ActorContext.set(ADMIN);
    }

    /** A decided repayment attributed to {@code decidedBy} for the payment-attribution columns. */
    private static Payment payment(long decidedBy, PaymentStatus status, long amountPaise) {
        Payment p = new Payment();
        p.setLoanId(1L);
        p.setAmount(amountPaise);
        p.setMethod(PaymentMethod.UPI);
        p.setStatus(status);
        p.setDecidedBy(decidedBy);
        p.setDecidedAt(Instant.now());
        return p;
    }

    /** A telecaller/collections call-count projection stub. */
    private static CustomerCallLogRepository.StaffCallCount callCount(long staffId, long count) {
        return new CustomerCallLogRepository.StaffCallCount() {
            @Override
            public Long getStaffId() {
                return staffId;
            }

            @Override
            public Long getCount() {
                return count;
            }
        };
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

        StaffPerformanceRow row = rowFor(service.summary(null, null, null), 41L);

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

        StaffPerformanceSummary summary = service.summary(null, null, null);
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

        StaffPerformanceRow row = rowFor(service.summary(null, null, null), 41L);

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

        StaffPerformanceRow row = rowFor(service.summary(null, null, null), 41L);

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

        StaffPerformanceRow row = rowFor(service.summary(null, null, null), 41L);

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

        StaffPerformanceRow row = rowFor(service.summary(null, null, null), 41L);

        assertThat(row.avgTurnaroundMinutes()).isEqualTo(180L);
    }

    @Test
    void reportsUnknownTurnaroundAsNullRatherThanZero() {
        rosterIsEveryone(RAVI);
        // Decided, but the assignment happened before the window — there is no clock-start to measure
        // from. Zero would read as "instant", which is a different and flattering claim.
        when(eventRepository.findForActorsInWindow(anyCollection(), any(), any())).thenReturn(List.of(
                event("41", "SANCTION", ist("2026-08-10", 11), 1L, null)));

        StaffPerformanceRow row = rowFor(service.summary(null, null, null), 41L);

        assertThat(row.avgTurnaroundMinutes()).isNull();
    }

    @Test
    void keepsAnEmployeeWithNoActivityOnTheRoster() {
        rosterIsEveryone(RAVI, NEHA);
        when(eventRepository.findForActorsInWindow(anyCollection(), any(), any())).thenReturn(List.of(
                event("41", "SANCTION", ist("2026-08-10", 10), 1L, null)));

        StaffPerformanceSummary summary = service.summary(null, null, null);

        assertThat(summary.rows()).hasSize(2);
        assertThat(rowFor(summary, 42L).totalActions()).isZero();
    }

    @Test
    void convertsTheDateWindowToAHalfOpenIstRange() {
        rosterIsEveryone(RAVI);
        when(eventRepository.findForActorsInWindow(anyCollection(), any(), any())).thenReturn(List.of());
        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);

        service.summary(LocalDate.parse("2026-08-10"), LocalDate.parse("2026-08-10"), null);

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

        service.summary(null, null, null);

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

        StaffPerformanceSummary summary = service.summary(null, null, null);

        assertThat(summary.daily()).containsExactly(
                new DecisionHistoryService.ActivityPoint(LocalDate.parse("2026-08-10"), 2L),
                new DecisionHistoryService.ActivityPoint(LocalDate.parse("2026-08-12"), 1L));
    }

    @Test
    void adminSeesTheWholeCompany() {
        rosterIsEveryone(RAVI, NEHA);
        when(eventRepository.findForActorsInWindow(anyCollection(), any(), any())).thenReturn(List.of());

        assertThat(service.summary(null, null, null).rows()).hasSize(2);
    }

    @Test
    void headSeesOnlyTheirOwnTeam() {
        ActorContext.set(CREDIT_HEAD);
        when(staffDirectory.listActive("CREDIT_HEAD")).thenReturn(List.of(NEHA));
        when(staffDirectory.listActive("CREDIT_EXECUTIVE")).thenReturn(List.of(RAVI));
        when(eventRepository.findForActorsInWindow(anyCollection(), any(), any())).thenReturn(List.of());

        StaffPerformanceSummary summary = service.summary(null, null, null);

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

        StaffPerformanceSummary summary = service.summary(null, null, null);

        assertThat(summary.rows()).extracting(StaffPerformanceRow::staffId).containsExactly(41L);
    }

    // ---- calls ------------------------------------------------------------

    @Test
    void sumsTelecallerAndCollectionsCallsIntoOneFigure() {
        rosterIsEveryone(RAVI);
        when(eventRepository.findForActorsInWindow(anyCollection(), any(), any())).thenReturn(List.of());
        when(callLogRepository.countByStaffInWindow(anyCollection(), any(), any()))
                .thenReturn(List.of(callCount(41L, 4L)));
        when(collectionActivity.callCountsByStaff(anyCollection(), any(), any()))
                .thenReturn(Map.of(41L, 3L));

        StaffPerformanceRow row = rowFor(service.summary(null, null, null), 41L);

        assertThat(row.callsMade()).as("both call logs count toward the one figure").isEqualTo(7);
    }

    @Test
    void doesNotAttributeOnePersonsCallsToAnother() {
        rosterIsEveryone(RAVI, NEHA);
        when(eventRepository.findForActorsInWindow(anyCollection(), any(), any())).thenReturn(List.of());
        when(callLogRepository.countByStaffInWindow(anyCollection(), any(), any()))
                .thenReturn(List.of(callCount(41L, 5L)));

        StaffPerformanceSummary summary = service.summary(null, null, null);

        assertThat(rowFor(summary, 41L).callsMade()).isEqualTo(5);
        assertThat(rowFor(summary, 42L).callsMade()).isZero();
    }

    @Test
    void reportsWhenCallAttributionBegan() {
        rosterIsEveryone(RAVI);
        when(eventRepository.findForActorsInWindow(anyCollection(), any(), any())).thenReturn(List.of());

        // Calls before V59 recorded only a mutable display name, so a 0 for an earlier window means
        // "not tracked", not "made none" — the UI needs this date to tell the two apart.
        assertThat(service.summary(null, null, null).callTrackingSince()).isNotNull();
    }

    // ---- single-staff narrowing (the decisions page) ----------------------

    @Test
    void staffIdNarrowsTheRosterToThatOnePerson() {
        // No listEveryone() stub on purpose: if narrowing works it is never called, and Mockito's
        // strict stubbing turns an unused stub here into a failure — which is the assertion.
        when(staffDirectory.findStaff(41L)).thenReturn(Optional.of(RAVI));
        when(eventRepository.findForActorsInWindow(anyCollection(), any(), any())).thenReturn(List.of());

        StaffPerformanceSummary summary = service.summary(null, null, 41L);

        assertThat(summary.rows()).extracting(StaffPerformanceRow::staffId).containsExactly(41L);
        // The whole-company roster must not be loaded just to render one person.
        org.mockito.Mockito.verify(staffDirectory, org.mockito.Mockito.never()).listEveryone();
    }

    @Test
    void aHeadCannotNarrowToSomeoneOutsideTheirTeam() {
        ActorContext.set(CREDIT_HEAD);
        StaffSummary outsider = new StaffSummary(9L, "Sana Khan", "COLLECTION_EXECUTIVE", true);
        when(staffDirectory.findStaff(9L)).thenReturn(Optional.of(outsider));

        // Asking for a specific id must not become a way around the team scoping.
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> service.summary(null, null, 9L))
                .isInstanceOf(BusinessException.class);
    }

    // ---- accountant repayment-verification attribution (V59 decided_by/decided_at) ---------------

    @Test
    void attributesVerifiedAndRejectedPaymentsToTheDecidingStaffer() {
        rosterIsEveryone(RAVI);
        when(eventRepository.findForActorsInWindow(anyCollection(), any(), any())).thenReturn(List.of());
        when(paymentRepository.findDecidedByInWindow(anyCollection(), any(), any())).thenReturn(List.of(
                payment(41L, PaymentStatus.VERIFIED, 500_000L),
                payment(41L, PaymentStatus.REJECTED, 250_000L)));

        StaffPerformanceRow row = rowFor(service.summary(null, null, null), 41L);

        assertThat(row.verifiedCount()).isEqualTo(1L);
        assertThat(row.verifiedPaise()).isEqualTo(500_000L);
        assertThat(row.rejectedPaymentCount()).isEqualTo(1L);
    }

    @Test
    void reportsNoAttributablePaymentsAsNullRatherThanZero() {
        rosterIsEveryone(RAVI);
        when(eventRepository.findForActorsInWindow(anyCollection(), any(), any())).thenReturn(List.of());
        // No stub for findDecidedByInWindow beyond the default empty list from setUp.

        StaffPerformanceRow row = rowFor(service.summary(null, null, null), 41L);

        assertThat(row.verifiedCount()).isNull();
        assertThat(row.verifiedPaise()).isNull();
        assertThat(row.rejectedPaymentCount()).isNull();
    }
}
