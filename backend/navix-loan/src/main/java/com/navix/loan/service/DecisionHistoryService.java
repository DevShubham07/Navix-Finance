package com.navix.loan.service;

import com.navix.common.collections.CollectionActivityDirectory;
import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.staff.StaffDirectory;
import com.navix.common.staff.StaffSummary;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.entity.ApplicationEvent;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicationEventRepository;
import com.navix.loan.repository.CustomerCallLogRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-staffer decision history, read off the append-only {@code application_event} trail.
 *
 * <p>Scoping (revamp.md decision 32): everyone sees their own decisions; a Head may look at their
 * team's; ADMIN may look at anyone's. Anything else is {@code FORBIDDEN}.
 */
@Service
@RequiredArgsConstructor
public class DecisionHistoryService {

    /**
     * Actions that count as a decision — the rest of the trail is routing/audit noise.
     *
     * <p>Public because {@code CustomerService} scopes an executive's customer list by "applications
     * I decided on" and must use the SAME definition of a decision; duplicating the set there would
     * let the two surfaces drift apart silently.
     */
    public static final Set<String> DECISION_ACTIONS = Set.of(
            "KYC_APPROVE", "KYC_REJECT", "ASSIGN", "REASSIGN", "SANCTION", "REJECT_LEAD", "MARK_PENDING",
            "EXEC_APPROVE", "EXEC_REJECT", "HEAD_APPROVE", "HEAD_REJECT",
            "DISB_ACCEPT", "DISB_REJECT", "VALIDATE_SUCCESS", "VALIDATE_FAIL", "RETRY", "CANCEL");

    /** Which roles a Head may inspect. Heads only — everyone else sees just themselves. */
    private static final Map<String, Set<String>> TEAM_OF = Map.of(
            "CREDIT_HEAD", Set.of("CREDIT_HEAD", "CREDIT_EXECUTIVE"),
            "COLLECTION_HEAD", Set.of("COLLECTION_HEAD", "COLLECTION_EXECUTIVE"));

    private final ApplicationEventRepository eventRepository;
    private final CustomerProfileRepository profileRepository;
    private final LoanApplicationRepository applicationRepository;
    private final CustomerCallLogRepository callLogRepository;
    private final CollectionActivityDirectory collectionActivity;
    private final StaffDirectory staffDirectory;

    /**
     * One decision, with {@code application_event.notes} already parsed into typed columns by
     * {@link DecisionNotes}. The raw {@code notes} stays on the wire as the audit source of truth —
     * the UI renders it only as a hover title, never as a visible column.
     */
    public record DecisionView(Long applicationId, Long customerId, String customerName, String pan,
                               String action, String fromStatus, String toStatus, Instant at,
                               Long amountPaise, Integer salaryCreditDay, LocalDate repaymentDate,
                               Long assigneeId, String assigneeName, String txnRef, String remark,
                               String notes) {
    }

    /**
     * One staffer's decisions, newest first. {@code staffId} null → the caller's own; {@code from}
     * / {@code to} null → all time.
     *
     * <p>The window matters beyond convenience: this list is what the staff-performance dashboard
     * links into, and totals shown there would be unexplainable next to an unbounded list.
     */
    @Transactional(readOnly = true)
    public List<DecisionView> decisions(Long staffId, LocalDate from, LocalDate to) {
        String callerId = ActorContext.get().id();
        String target = staffId != null ? String.valueOf(staffId) : callerId;
        if (!target.equals(callerId)) {
            requireCanInspect(staffId);
        }
        Instant fromInst = from == null ? Instant.EPOCH : from.atStartOfDay(IST).toInstant();
        Instant toInst = to == null ? Instant.now() : to.plusDays(1).atStartOfDay(IST).toInstant();
        List<ApplicationEvent> events =
                eventRepository.findForActorsInWindow(List.of(target), fromInst, toInst).stream()
                        .filter(e -> DECISION_ACTIONS.contains(e.getAction()))
                        .toList();
        if (events.isEmpty()) {
            return List.of();
        }

        // Batch every join up front. Resolving the customer/profile per row was an N+1 that grew
        // with a staffer's career; this is a fixed 2 queries regardless of how many decisions they
        // have made.
        List<Long> appIds = events.stream().map(ApplicationEvent::getApplicationId).distinct().toList();
        Map<Long, CustomerProfile> profileByApp = profileRepository.findByApplicationIdIn(appIds).stream()
                .collect(Collectors.toMap(CustomerProfile::getApplicationId, Function.identity(), (a, b) -> a));
        Map<Long, Long> customerIdByApp = applicationRepository.findAllById(appIds).stream()
                .collect(Collectors.toMap(LoanApplication::getId, LoanApplication::getCustomerId, (a, b) -> a));

        // Assignee names are memoised over DISTINCT ids — bounded by the number of executives, not
        // the number of rows. StaffDirectory has no batch lookup, so never call it per row.
        Map<Long, String> assigneeNames = new HashMap<>();
        return events.stream()
                .map(e -> view(e, profileByApp, customerIdByApp, assigneeNames))
                .toList();
    }

    /**
     * Actions that count as *approving* something. Deliberately NOT derived from
     * {@link #DECISION_ACTIONS}: that set is about "is this row worth showing", and it both omits
     * real staff decisions ({@code ADMIN_FORCE_DISBURSE}) and still lists actions nothing has emitted
     * since V45/V48 ({@code EXEC_APPROVE}, {@code HEAD_APPROVE}, {@code DISB_ACCEPT},
     * {@code VALIDATE_FAIL}). Counting approvals off it would report work nobody did.
     */
    private static final Set<String> ACCEPT_ACTIONS =
            Set.of("KYC_APPROVE", "SANCTION", "VALIDATE_SUCCESS", "ADMIN_FORCE_DISBURSE");

    /**
     * Actions that count as *turning something down*.
     *
     * <p>{@code AUTO_REJECT_*} is excluded on purpose — it is a dynamic action prefix written by the
     * system's own auto-reject rules, not a human decision, and crediting it to whoever happened to
     * be the actor would inflate their rejection count with work they never did.
     */
    private static final Set<String> REJECT_ACTIONS =
            Set.of("KYC_REJECT", "REJECT_LEAD", "DISB_REJECT", "CANCEL");

    /** Assignment actions — the clock-start for turnaround. */
    private static final Set<String> ASSIGN_ACTIONS = Set.of("ASSIGN", "REASSIGN");

    /**
     * Prefix of the system's own auto-reject rules. The actor on these rows is whoever's request
     * happened to trip the rule, not someone who made a decision, so they are excluded from every
     * per-person count — including the total. Counting them only in the total would contradict
     * leaving them out of the rejections, and would make the totals disagree with the per-action
     * list on /staff/my-decisions that this dashboard links into.
     */
    private static final String AUTO_REJECT_PREFIX = "AUTO_REJECT_";

    /** The product's only operating timezone; "today" means today in Delhi, never UTC. */
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /**
     * One employee's work over a window. Counts are of actions actually attributable to them;
     * {@code null} means "we cannot measure this", never zero — a fabricated 0 reads as
     * "this person did nothing", which is a materially different claim.
     *
     * @param avgTurnaroundMinutes mean time from the file being assigned to them until they acted;
     *                             null when none of their decisions had a preceding assignment
     * @param pendingNow           live count of files sitting with them right now (not windowed)
     * @param moneyPaise           total sanctioned/released value they moved in the window
     * @param activeDays           distinct IST days on which they did anything
     * @param firstActionAt        earliest action in the window (their "start")
     * @param lastActionAt         latest action in the window
     * @param callsMade            telecaller + collections calls they logged in the window; see
     *                             {@link StaffPerformanceSummary#callTrackingSince()} before reading
     *                             a 0 here as "made no calls"
     */
    public record StaffPerformanceRow(Long staffId, String staffName, String role, boolean active,
                                      long accepted, long rejected, long totalActions,
                                      long activeDays, Long avgTurnaroundMinutes, long pendingNow,
                                      long moneyPaise, Instant firstActionAt, Instant lastActionAt,
                                      long callsMade) {
    }

    /** One day's total action count across the whole roster — the trend line. */
    public record ActivityPoint(LocalDate date, long actions) {
    }

    /**
     * The staff-performance payload: a row per employee plus the roster-wide daily series.
     *
     * @param callTrackingSince the date call logging started recording WHO made the call (V59).
     *                          Calls before it recorded only a mutable display name, so they cannot
     *                          be attributed and are not counted. Returned rather than folded into a
     *                          null count so the UI can say "since 21 Aug" on a partial window and
     *                          show a dash on one that ends before it — the service should not have
     *                          to guess which of those the caller wants.
     */
    public record StaffPerformanceSummary(List<StaffPerformanceRow> rows, List<ActivityPoint> daily,
                                          LocalDate callTrackingSince) {
    }

    /**
     * When V59 shipped and call rows began carrying a staff id. A constant, not a lookup: it is a
     * fact about a migration that has already run, and reading it from the Flyway history at request
     * time would couple this service to Flyway's bookkeeping for no gain.
     */
    private static final LocalDate CALL_TRACKING_SINCE = LocalDate.of(2026, 8, 21);

    /**
     * Aggregate work over {@code [from, to]} (inclusive dates, IST). Both null = all time.
     *
     * <p>ADMIN sees the whole company; a Head sees only their own team; everyone else sees only
     * themselves — the same scoping rule {@link #decisions(Long, LocalDate, LocalDate)} enforces.
     * Passing {@code staffId} narrows that to one person (after the same inspect check), so the
     * per-employee page does not make an ADMIN aggregate the entire company to render one row.
     */
    @Transactional(readOnly = true)
    public StaffPerformanceSummary summary(LocalDate from, LocalDate to, Long staffId) {
        List<StaffSummary> roster = rosterFor(staffId);
        if (roster.isEmpty()) {
            return new StaffPerformanceSummary(List.of(), List.of(), CALL_TRACKING_SINCE);
        }
        // Half-open in IST, matching every other from/to endpoint in this codebase. An absent bound
        // becomes the real limit of the data rather than a null: nothing predates the epoch and
        // nothing is logged in the future, so "all time" needs no separate query (and Postgres
        // cannot type-infer a bare null bound anyway).
        Instant fromInst = from == null ? Instant.EPOCH : from.atStartOfDay(IST).toInstant();
        Instant toInst = to == null ? Instant.now() : to.plusDays(1).atStartOfDay(IST).toInstant();

        Map<String, StaffSummary> byActorId = roster.stream().collect(Collectors.toMap(
                s -> String.valueOf(s.id()), Function.identity(), (a, b) -> a));

        // ponytail: buckets the window's events in memory rather than pushing GROUP BY into SQL —
        // one indexed query feeds counts, active days, turnaround, money AND the trend series. Swap
        // in projections if an all-time read over a large trail gets slow (DashboardService already
        // does a bare findAll(), so this is not the first place that would hurt).
        List<ApplicationEvent> events =
                eventRepository.findForActorsInWindow(byActorId.keySet(), fromInst, toInst);

        Map<Long, Long> pendingByStaff = applicationRepository
                .countGroupByAssignedExecutive(ApplicationStatus.CREDIT_EXEC_PENDING).stream()
                .collect(Collectors.toMap(c -> c.getStaffId(), c -> c.getCount(), (a, b) -> a));

        Map<Long, Long> callsByStaff = callCounts(
                roster.stream().map(StaffSummary::id).toList(), fromInst, toInst);

        Map<String, List<ApplicationEvent>> byActor =
                events.stream().collect(Collectors.groupingBy(ApplicationEvent::getActorId));
        Map<Long, Instant> assignedAt = assignmentClock(events);

        List<StaffPerformanceRow> rows = roster.stream()
                .map(s -> row(s, byActor.getOrDefault(String.valueOf(s.id()), List.of()),
                        assignedAt, pendingByStaff, callsByStaff))
                .toList();

        Map<LocalDate, Long> perDay = events.stream()
                .filter(e -> e.getAction() == null || !e.getAction().startsWith(AUTO_REJECT_PREFIX))
                .collect(Collectors.groupingBy(
                        e -> LocalDate.ofInstant(e.getAt(), IST), TreeMap::new, Collectors.counting()));
        List<ActivityPoint> daily = perDay.entrySet().stream()
                .map(e -> new ActivityPoint(e.getKey(), e.getValue()))
                .toList();

        return new StaffPerformanceSummary(rows, daily, CALL_TRACKING_SINCE);
    }

    /**
     * Calls each staffer logged in the window, telecaller and collections summed into one figure.
     *
     * <p>Most people only ever produce one kind, so two columns would be mostly dashes; a staffer
     * who does both gets a single honest total. Collections lives in another module, so its half
     * arrives through {@link CollectionActivityDirectory} rather than a direct repository read.
     */
    private Map<Long, Long> callCounts(List<Long> staffIds, Instant from, Instant to) {
        if (staffIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> merged = new HashMap<>();
        for (CustomerCallLogRepository.StaffCallCount c
                : callLogRepository.countByStaffInWindow(staffIds, from, to)) {
            merged.merge(c.getStaffId(), c.getCount(), Long::sum);
        }
        collectionActivity.callCountsByStaff(staffIds, from, to)
                .forEach((staffId, count) -> merged.merge(staffId, count, Long::sum));
        return merged;
    }

    /**
     * When each application was last handed to somebody, from the assignment events in the window.
     *
     * <p>Only assignments that fall inside the window are visible here, so a decision on a file
     * assigned before it simply has no clock-start and is left out of the turnaround average rather
     * than being timed from an invented origin.
     */
    private Map<Long, Instant> assignmentClock(List<ApplicationEvent> events) {
        Map<Long, Instant> latest = new HashMap<>();
        for (ApplicationEvent e : events) {
            if (ASSIGN_ACTIONS.contains(e.getAction())) {
                latest.merge(e.getApplicationId(), e.getAt(),
                        (a, b) -> a.isAfter(b) ? a : b);
            }
        }
        return latest;
    }

    private StaffPerformanceRow row(StaffSummary staff, List<ApplicationEvent> mine,
                                    Map<Long, Instant> assignedAt, Map<Long, Long> pendingByStaff,
                                    Map<Long, Long> callsByStaff) {
        long accepted = 0;
        long rejected = 0;
        long moneyPaise = 0;
        long turnaroundTotalMinutes = 0;
        long turnaroundSamples = 0;
        Instant first = null;
        Instant last = null;
        Set<LocalDate> days = new HashSet<>();

        long humanActions = 0;
        for (ApplicationEvent e : mine) {
            String action = e.getAction();
            if (action != null && action.startsWith(AUTO_REJECT_PREFIX)) {
                continue; // the system's, not theirs — see AUTO_REJECT_PREFIX
            }
            humanActions++;
            boolean isAccept = ACCEPT_ACTIONS.contains(action);
            if (isAccept) {
                accepted++;
            } else if (REJECT_ACTIONS.contains(action)) {
                rejected++;
            }
            days.add(LocalDate.ofInstant(e.getAt(), IST));
            if (first == null || e.getAt().isBefore(first)) {
                first = e.getAt();
            }
            if (last == null || e.getAt().isAfter(last)) {
                last = e.getAt();
            }
            // Money and the assignee id are already parsed out of the free-text notes column for
            // /staff/my-decisions — reuse that parser rather than re-reading the string here.
            DecisionNotes.Parsed parsed = DecisionNotes.parse(e.getNotes());
            if (isAccept && parsed.amountPaise() != null) {
                moneyPaise += parsed.amountPaise();
            }
            Instant handedOver = assignedAt.get(e.getApplicationId());
            if (handedOver != null && (isAccept || REJECT_ACTIONS.contains(action))
                    && !e.getAt().isBefore(handedOver)) {
                turnaroundTotalMinutes += ChronoUnit.MINUTES.between(handedOver, e.getAt());
                turnaroundSamples++;
            }
        }

        Long avgTurnaround = turnaroundSamples == 0 ? null : turnaroundTotalMinutes / turnaroundSamples;
        return new StaffPerformanceRow(staff.id(), staff.name(), staff.role(), staff.active(),
                accepted, rejected, humanActions, days.size(), avgTurnaround,
                pendingByStaff.getOrDefault(staff.id(), 0L), moneyPaise, first, last,
                callsByStaff.getOrDefault(staff.id(), 0L));
    }

    /**
     * The roster to aggregate: one named person when {@code staffId} is given, otherwise everyone the
     * caller may see. Narrowing still goes through the same inspect check, so asking for a specific
     * id can never widen what a caller is entitled to.
     */
    private List<StaffSummary> rosterFor(Long staffId) {
        if (staffId == null) {
            return rosterForCaller();
        }
        if (!String.valueOf(staffId).equals(ActorContext.get().id())) {
            requireCanInspect(staffId);
        }
        return staffDirectory.findStaff(staffId).map(List::of).orElseGet(List::of);
    }

    /** Who the caller is allowed to see: ADMIN everyone, a Head their team, anyone else themselves. */
    private List<StaffSummary> rosterForCaller() {
        String role = ActorContext.get().role();
        if ("ADMIN".equals(role)) {
            return staffDirectory.listEveryone();
        }
        Set<String> team = TEAM_OF.get(role);
        if (team != null) {
            return team.stream().flatMap(r -> staffDirectory.listActive(r).stream()).toList();
        }
        try {
            return staffDirectory.findStaff(Long.valueOf(ActorContext.get().id()))
                    .map(List::of).orElseGet(List::of);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** Staff whose history the caller may open — for the Head's team switcher. */
    @Transactional(readOnly = true)
    public List<StaffSummary> inspectable() {
        String role = ActorContext.get().role();
        if ("ADMIN".equals(role)) {
            return TEAM_OF.values().stream().flatMap(Set::stream).distinct()
                    .flatMap(r -> staffDirectory.listActive(r).stream())
                    .toList();
        }
        return TEAM_OF.getOrDefault(role, Set.of()).stream()
                .flatMap(r -> staffDirectory.listActive(r).stream())
                .toList();
    }

    private void requireCanInspect(Long staffId) {
        String role = ActorContext.get().role();
        if ("ADMIN".equals(role)) {
            return;
        }
        Set<String> team = TEAM_OF.getOrDefault(role, Set.of());
        boolean inTeam = staffDirectory.findStaff(staffId)
                .map(s -> team.contains(s.role()))
                .orElse(false);
        if (!inTeam) {
            throw new BusinessException("FORBIDDEN", "You can only view your own team's decisions");
        }
    }

    private DecisionView view(ApplicationEvent e, Map<Long, CustomerProfile> profileByApp,
                             Map<Long, Long> customerIdByApp, Map<Long, String> assigneeNames) {
        CustomerProfile profile = profileByApp.get(e.getApplicationId());
        DecisionNotes.Parsed parsed = DecisionNotes.parse(e.getNotes());
        String assigneeName = parsed.assigneeId() == null ? null
                : assigneeNames.computeIfAbsent(parsed.assigneeId(),
                        id -> staffDirectory.findStaff(id).map(StaffSummary::name).orElse(null));
        return new DecisionView(
                e.getApplicationId(),
                customerIdByApp.get(e.getApplicationId()),
                profile != null ? profile.getFullName() : null,
                profile != null ? profile.getPan() : null,
                e.getAction(),
                e.getFromStatus() != null ? e.getFromStatus().name() : null,
                e.getToStatus().name(),
                e.getAt(),
                parsed.amountPaise(),
                parsed.salaryCreditDay(),
                parsed.repaymentDate(),
                parsed.assigneeId(),
                assigneeName,
                parsed.txnRef(),
                parsed.remark(),
                e.getNotes());
    }
}
