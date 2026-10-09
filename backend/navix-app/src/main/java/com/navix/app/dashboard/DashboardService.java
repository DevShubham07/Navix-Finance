package com.navix.app.dashboard;

import com.navix.app.dashboard.BusinessQueries.CompanyUsersAgg;
import com.navix.app.dashboard.BusinessQueries.GeoAgg;
import com.navix.app.dashboard.BusinessQueries.MonthCollection;
import com.navix.app.dashboard.CollectionQueries.AnalysisAgg;
import com.navix.app.dashboard.CollectionQueries.CaseRow;
import com.navix.app.dashboard.CollectionQueries.Collected;
import com.navix.app.dashboard.CollectionQueries.PtpTotals;
import com.navix.app.dashboard.DashboardDtos.*;
import com.navix.app.dashboard.MetricQueries.Agg;
import com.navix.app.dashboard.MetricQueries.AppDetail;
import com.navix.app.dashboard.MetricQueries.GroupLoan;
import com.navix.app.dashboard.MetricQueries.Page;
import com.navix.app.dashboard.MetricQueries.RowKey;
import com.navix.app.dashboard.MetricSql.Metric;
import com.navix.app.dashboard.ScoreFormulas.Input;
import com.navix.app.dashboard.ScoreFormulas.Ranked;
import com.navix.app.dashboard.TeamQueries.CreditRaw;
import com.navix.app.dashboard.TeamQueries.TelecallerRaw;
import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.staff.StaffDirectory;
import com.navix.common.staff.StaffSummary;
import com.navix.loan.entity.Loan;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.service.RepaymentService;
import com.navix.loan.service.RepaymentService.OutstandingBreakdown;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the staff dashboard: resolves who the numbers are about ({@link DashboardScope}), asks
 * the query classes, prices what is owed through {@link RepaymentService} (the one formula for
 * interest and penalty -- never re-derived in SQL) and assembles the wire shapes.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int PRICING_CHUNK = 2000;
    private static final int MAX_RECORDS_PAGE = 500;
    private static final int GEO_PINCODE_LIMIT = 300;
    private static final int TOP_COMPANIES = 10;
    private static final long MAX_SPAN_DAYS = 1100;
    private static final List<String> BUCKETS = List.of("RUNNING", "D1_30", "D31_60", "D61_90", "D90_PLUS");

    private final MetricQueries metrics;
    private final BusinessQueries business;
    private final CollectionQueries collection;
    private final TeamQueries team;
    private final StaffDirectory staffDirectory;
    private final RepaymentService repayments;
    private final LoanRepository loanRepository;

    // ---- scope & period ---------------------------------------------------------------------

    public DashboardScope scope(String view, List<Long> staffIds) {
        return DashboardScope.resolve(ActorContext.get(), view, staffIds, staffDirectory);
    }

    /** Any staff member but a DSA. */
    public void requireStaff() {
        DashboardScope.requireStaff(ActorContext.get());
    }

    public static LocalDate today() {
        return LocalDate.now(IST);
    }

    /** Default period is the current month to date; the span is capped so a daily series stays sane. */
    public static LocalDate[] period(String from, String to) {
        LocalDate t = parseDate(to, today());
        LocalDate f = parseDate(from, t.withDayOfMonth(1));
        if (f.isAfter(t) || java.time.temporal.ChronoUnit.DAYS.between(f, t) > MAX_SPAN_DAYS) {
            throw new BusinessException("INVALID_RANGE", "from must not be after to, and the span at most "
                    + MAX_SPAN_DAYS + " days");
        }
        return new LocalDate[] {f, t};
    }

    public static LocalDate parseDate(String s, LocalDate fallback) {
        if (s == null || s.isBlank()) return fallback;
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException("INVALID_DATE", "Dates must be yyyy-MM-dd");
        }
    }

    private static YearMonth parseMonth(String s, YearMonth fallback) {
        if (s == null || s.isBlank()) return fallback;
        try {
            return YearMonth.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException("INVALID_DATE", "Months must be yyyy-MM");
        }
    }

    // ---- pricing helpers --------------------------------------------------------------------

    private List<Loan> loadLoans(Collection<Long> ids) {
        List<Long> distinct = new ArrayList<>(new LinkedHashSet<>(ids));
        List<Loan> out = new ArrayList<>();
        for (int i = 0; i < distinct.size(); i += PRICING_CHUNK) {
            out.addAll(loanRepository.findAllById(distinct.subList(i, Math.min(distinct.size(), i + PRICING_CHUNK))));
        }
        return out;
    }

    private Map<Long, OutstandingBreakdown> price(List<Loan> loans, LocalDate asOf) {
        Map<Long, OutstandingBreakdown> out = new HashMap<>();
        for (int i = 0; i < loans.size(); i += PRICING_CHUNK) {
            out.putAll(repayments.outstandingBreakdownsForAll(
                    loans.subList(i, Math.min(loans.size(), i + PRICING_CHUNK)), asOf));
        }
        return out;
    }

    private static long owed(Map<Long, OutstandingBreakdown> priced, long loanId) {
        OutstandingBreakdown b = priced.get(loanId);
        return b == null ? 0 : b.outstandingPaise();
    }

    // ---- snapshot ---------------------------------------------------------------------------

    private Kpi kpi(Metric m, DashboardScope scope, LocalDate from, LocalDate to) {
        Agg a = metrics.aggregate(m, scope, from, to, null);
        LocalDate[] prev = MetricQueries.previousWindow(from, to);
        Agg b = metrics.aggregate(m, scope, prev[0], prev[1], null);
        return new Kpi(m, a.count(), a.amount(), a.fresh(), a.reloan(), a.freshAmount(), a.reloanAmount(),
                b.count(), b.amount());
    }

    private static Long average(long amount, long count) {
        return count == 0 ? null : (amount + count / 2) / count;
    }

    public Snapshot snapshot(DashboardScope scope, LocalDate from, LocalDate to) {
        scope.require("ADMIN");
        Kpi applications = kpi(Metric.APPLICATIONS, scope, from, to);
        Kpi disbursed = kpi(Metric.DISBURSED, scope, from, to);
        Kpi pending = kpi(Metric.PENDING, scope, from, to);
        Kpi rejected = kpi(Metric.REJECTED, scope, from, to);
        Kpi pendingSanctioned = kpi(Metric.PENDING_SANCTIONED, scope, from, to);
        Kpi pendingDisbursal = kpi(Metric.PENDING_DISBURSAL, scope, from, to);

        Financial financial = new Financial(disbursed, pendingSanctioned, pendingDisbursal,
                average(disbursed.amountPaise(), disbursed.count()),
                average(disbursed.freshPaise(), disbursed.fresh()),
                average(disbursed.reloanPaise(), disbursed.reloan()));

        long closed = metrics.aggregate(Metric.CLOSED, scope, from, to, null).count();
        long settled = metrics.aggregate(Metric.SETTLED, scope, from, to, null).count();
        long partPaid = metrics.aggregate(Metric.PART_PAID, scope, from, to, null).count();
        long[] collected = business.collectedByClosingType(from, to);
        ClosedBlock closedBlock = new ClosedBlock(closed, settled, partPaid,
                ScoreFormulas.ratio(closed, disbursed.count()), collected[0], collected[1], collected[2],
                collected[0] + collected[1] + collected[2]);

        Rates rates = new Rates(ScoreFormulas.ratio(disbursed.count(), applications.count()),
                ScoreFormulas.ratio(pending.count(), applications.count()),
                ScoreFormulas.ratio(rejected.count(), applications.count()));

        return new Snapshot(from.toString(), to.toString(), new Kpis(applications, disbursed, pending, rejected),
                financial, closedBlock, rates, business.rateTable(Metric.PF_RATE, scope, from, to),
                business.rateTable(Metric.ROI_RATE, scope, from, to));
    }

    // ---- monthly ----------------------------------------------------------------------------

    public Monthly monthly(DashboardScope scope, LocalDate from, LocalDate to, int months) {
        scope.require("ADMIN", "COLLECTION_HEAD", "COLLECTION_EXECUTIVE");
        int n = Math.max(1, Math.min(months, 12));
        YearMonth last = YearMonth.from(to);
        YearMonth first = last.minusMonths(n - 1L);

        Map<String, MonthCollection> byMonth = business.monthlyCollection(scope, first.atDay(1), last.atEndOfMonth());
        Map<String, TargetRow> targets = targetMap(first, last);
        boolean admin = scope.orgWide();
        Map<String, Long> disbursed = admin ? business.disbursedByMonth(first.atDay(1), last.atEndOfMonth()) : Map.of();

        List<MonthRow> rows = new ArrayList<>();
        for (YearMonth m = first; !m.isAfter(last); m = m.plusMonths(1)) {
            MonthCollection c = byMonth.getOrDefault(m.toString(), new MonthCollection(0, 0, 0, 0));
            TargetRow t = targets.get(m.toString());
            int bp = t.collectionTargetBp();
            Double pct = ScoreFormulas.ratio(c.collected(), c.repayable());
            Double deficit = pct == null ? null : Math.max(0.0, bp / 10000.0 - pct);
            long targetAmount = Math.round(c.repayable() * (bp / 10000.0));
            long achieved = disbursed.getOrDefault(m.toString(), 0L);
            Long disbursalTarget = admin ? t.disbursalTargetPaise() : null;
            Double achievedPct = disbursalTarget == null || disbursalTarget == 0 ? null
                    : ScoreFormulas.ratio(achieved, disbursalTarget);
            rows.add(new MonthRow(m.toString(), c.due(), c.repayable(), c.collected(), pct, c.preclosed(),
                    ScoreFormulas.ratio(c.preclosed(), c.due()), bp, deficit, targetAmount,
                    Math.max(0, targetAmount - c.collected()), achieved, disbursalTarget, achievedPct));
        }

        List<MarketingRow> marketing = new ArrayList<>();
        if (admin) {
            YearMonth mFirst = last.minusMonths(5);
            Map<String, long[]> lead = business.marketing(mFirst.atDay(1), last.atEndOfMonth());
            for (YearMonth m = mFirst; !m.isAfter(last); m = m.plusMonths(1)) {
                long[] v = lead.getOrDefault(m.toString(), new long[2]);
                marketing.add(new MarketingRow(m.toString(), v[0], v[1], ScoreFormulas.ratio(v[1], v[0])));
            }
        }

        Map<String, Agg> ret = metrics.aggregateByGrp(Metric.RELOAN_RETENTION, scope, from, to);
        long closed = ret.getOrDefault("CLOSED", Agg.ZERO).count();
        long reloan = ret.getOrDefault("RELOAN", Agg.ZERO).count();
        Retention retention = new Retention(ret.getOrDefault("DUE", Agg.ZERO).count(), closed, reloan,
                ret.getOrDefault("NO_REPEAT", Agg.ZERO).count(), ScoreFormulas.ratio(reloan, closed));
        return new Monthly(rows, marketing, retention);
    }

    private Map<String, TargetRow> targetMap(YearMonth from, YearMonth to) {
        Map<String, TargetRow> saved = new HashMap<>();
        business.targets(from, to).forEach(t -> saved.put(t.month(), t));
        Map<String, TargetRow> out = new LinkedHashMap<>();
        for (YearMonth m = from; !m.isAfter(to); m = m.plusMonths(1)) {
            out.put(m.toString(), saved.getOrDefault(m.toString(),
                    new TargetRow(m.toString(), null, BusinessQueries.DEFAULT_TARGET_BP)));
        }
        return out;
    }

    // ---- daily ------------------------------------------------------------------------------

    public Daily daily(DashboardScope scope, LocalDate from, LocalDate to) {
        scope.require("ADMIN", "DISBURSEMENT_HEAD", "ACCOUNTANT");
        Map<LocalDate, Long> pf = business.pfByDay(from, to);
        Map<LocalDate, long[]> accrued = new HashMap<>(); // [interest, penalty] frozen at the day each loan closed
        List<Loan> closedLoans = loadLoans(business.closedLoanIds(from, to));
        Map<Long, OutstandingBreakdown> priced = price(closedLoans, today());
        for (Loan l : closedLoans) {
            OutstandingBreakdown b = priced.get(l.getId());
            if (b == null || l.getClosedOn() == null) continue;
            long[] slot = accrued.computeIfAbsent(l.getClosedOn(), d -> new long[2]);
            slot[0] += b.interestPaise();
            slot[1] += b.penaltyPaise();
        }
        List<DayRow> days = new ArrayList<>();
        long cumulative = 0;
        long totalPf = 0;
        long totalInterest = 0;
        long totalPenalty = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            long dayPf = pf.getOrDefault(d, 0L);
            long[] a = accrued.getOrDefault(d, new long[2]);
            cumulative += dayPf + a[0] + a[1];
            totalPf += dayPf;
            totalInterest += a[0];
            totalPenalty += a[1];
            days.add(new DayRow(d.toString(), dayPf, a[0], a[1], cumulative));
        }
        return new Daily(days, new DailyTotals(totalPf, totalInterest, totalPenalty, cumulative));
    }

    // ---- pre-closure week -------------------------------------------------------------------

    public PreclosureWeek preclosureWeek(DashboardScope scope, LocalDate date) {
        scope.require("ADMIN", "COLLECTION_HEAD", "COLLECTION_EXECUTIVE");
        LocalDate anchor = date != null ? date : today();
        LocalDate from = anchor.plusDays(1);
        LocalDate to = anchor.plusDays(7);
        Map<String, Agg> due = metrics.aggregateByGrp(Metric.DUE_ON, scope, from, to);
        Map<String, Agg> pre = metrics.aggregateByGrp(Metric.PRECLOSED_ON, scope, from, to);
        Map<String, Agg> pend = metrics.aggregateByGrp(Metric.PENDING_ON, scope, from, to);
        Map<String, Agg> recv = metrics.aggregateByGrp(Metric.RECEIVED_ON, scope, from, to);

        List<GroupLoan> pendingLoans = metrics.groupLoans(Metric.PENDING_ON, scope, from, to, null);
        Map<Long, OutstandingBreakdown> priced = price(
                loadLoans(pendingLoans.stream().map(GroupLoan::loanId).toList()), today());
        Map<String, Long> owedByDay = new HashMap<>();
        for (GroupLoan gl : pendingLoans) {
            owedByDay.merge(gl.grp(), owed(priced, gl.loanId()), Long::sum);
        }

        List<PreDay> rows = new ArrayList<>();
        long[] t = new long[8];
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            String k = d.toString();
            long[] v = {due.getOrDefault(k, Agg.ZERO).count(), due.getOrDefault(k, Agg.ZERO).amount(),
                    pre.getOrDefault(k, Agg.ZERO).count(), pre.getOrDefault(k, Agg.ZERO).amount(),
                    pend.getOrDefault(k, Agg.ZERO).count(), owedByDay.getOrDefault(k, 0L),
                    recv.getOrDefault(k, Agg.ZERO).count(), recv.getOrDefault(k, Agg.ZERO).amount()};
            for (int i = 0; i < t.length; i++) t[i] += v[i];
            rows.add(preDay(k, v));
        }
        return new PreclosureWeek(anchor.toString(), rows, preDay(null, t));
    }

    private static PreDay preDay(String date, long[] v) {
        return new PreDay(date, v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], ScoreFormulas.ratio(v[3] + v[7], v[1]));
    }

    // ---- AUM --------------------------------------------------------------------------------

    public Aum aum(DashboardScope scope, LocalDate asOf) {
        scope.require("ADMIN");
        LocalDate at = asOf != null ? asOf : today();
        Map<String, Agg> agg = metrics.aggregateByGrp(Metric.AUM_BUCKET, scope, at, at);
        List<GroupLoan> loans = metrics.groupLoans(Metric.AUM_BUCKET, scope, at, at, null);
        Map<Long, OutstandingBreakdown> priced = price(loadLoans(loans.stream().map(GroupLoan::loanId).toList()), at);
        Map<String, Long> owedByBucket = new HashMap<>();
        for (GroupLoan gl : loans) {
            owedByBucket.merge(gl.grp(), owed(priced, gl.loanId()), Long::sum);
        }
        long totalCases = agg.values().stream().mapToLong(Agg::count).sum();
        long totalPrincipal = agg.values().stream().mapToLong(Agg::amount).sum();
        long totalOwed = owedByBucket.values().stream().mapToLong(Long::longValue).sum();

        List<AumRow> rows = new ArrayList<>();
        for (String b : BUCKETS) {
            Agg a = agg.getOrDefault(b, Agg.ZERO);
            rows.add(new AumRow(b, a.count(), a.amount(), owedByBucket.getOrDefault(b, 0L),
                    ScoreFormulas.ratio(a.count(), totalCases)));
        }
        AumRow total = new AumRow("TOTAL", totalCases, totalPrincipal, totalOwed, totalCases == 0 ? null : 1.0);
        long current = rows.get(0).cases();
        long d1to60 = rows.get(1).cases() + rows.get(2).cases();
        long d61to90 = rows.get(3).cases();
        long d90 = rows.get(4).cases();
        AumChips chips = new AumChips(ScoreFormulas.ratio(current, totalCases), ScoreFormulas.ratio(d1to60, totalCases),
                ScoreFormulas.ratio(d61to90, totalCases), ScoreFormulas.ratio(d90, totalCases),
                current, d1to60, d61to90, d90);
        return new Aum(at.toString(), rows, total, chips);
    }

    // ---- collection analysis ----------------------------------------------------------------

    public CollectionAnalysis collectionAnalysis(DashboardScope scope, LocalDate date, String groupBy) {
        scope.require("ADMIN", "COLLECTION_HEAD");
        String gb = groupBy == null ? "EXEC" : groupBy.trim().toUpperCase(java.util.Locale.ROOT);
        if (!gb.equals("EXEC") && !gb.equals("STATE")) {
            throw new BusinessException("INVALID_GROUP", "groupBy must be EXEC or STATE");
        }
        LocalDate d = date != null ? date : today();
        List<AnalysisAgg> raw = collection.analysis(scope, gb, d);
        Map<Long, String> names = gb.equals("EXEC")
                ? staffDirectory.namesFor(raw.stream().map(AnalysisAgg::key).filter(k -> k.matches("\\d+"))
                        .map(Long::valueOf).toList())
                : Map.of();
        List<AnalysisRow> rows = raw.stream().map(a -> {
            String label = a.key();
            if (gb.equals("EXEC")) {
                label = a.key().matches("\\d+") ? names.getOrDefault(Long.valueOf(a.key()), "Staff " + a.key())
                        : "Unassigned";
            }
            return new AnalysisRow(a.key(), label, a.loans(), a.principal(), a.repayable(), a.collectedCount(),
                    a.collected(), average(a.principal(), a.loans()), ScoreFormulas.ratio(a.collected(), a.repayable()));
        }).toList();
        return new CollectionAnalysis(d.toString(), gb, rows);
    }

    // ---- team -------------------------------------------------------------------------------

    public Team team(DashboardScope scope, LocalDate from, LocalDate to) {
        scope.require("ADMIN");
        Map<String, Long> status = team.staffByStatus();
        long total = status.values().stream().mapToLong(Long::longValue).sum();
        List<RoleCount> byRole = team.activeStaffByRole().entrySet().stream()
                .map(e -> new RoleCount(e.getKey(), e.getValue())).toList();
        List<HeadScore> heads = new ArrayList<>();
        for (String board : List.of("CREDIT_HEAD", "COLLECTION_HEAD")) {
            for (Scored s : board(board, from, to)) {
                heads.add(new HeadScore(s.ranked().staffId(), s.ranked().name(), board, s.ranked().score()));
            }
        }
        return new Team(total, status.getOrDefault("ACTIVE", 0L),
                status.getOrDefault("DISABLED", 0L), status.getOrDefault("INVITED", 0L), byRole, heads);
    }

    // ---- calendar ---------------------------------------------------------------------------

    public Calendar calendar(DashboardScope scope, String month, String mode) {
        scope.require("ADMIN");
        YearMonth ym = parseMonth(month, YearMonth.from(today()));
        String md = mode == null ? "DUE" : mode.trim().toUpperCase(java.util.Locale.ROOT);
        Metric m = switch (md) {
            case "DUE" -> Metric.CALENDAR_DUE;
            case "DISBURSED" -> Metric.CALENDAR_DISBURSED;
            case "BIRTHDAY" -> Metric.CALENDAR_BIRTHDAY;
            default -> throw new BusinessException("INVALID_MODE", "mode must be DUE, DISBURSED or BIRTHDAY");
        };
        boolean money = m != Metric.CALENDAR_BIRTHDAY;
        Map<String, Agg> byDay = metrics.aggregateByGrp(m, scope, ym.atDay(1), ym.atEndOfMonth());
        List<CalendarDay> days = new ArrayList<>();
        long withData = 0;
        long totalCount = 0;
        long totalAmount = 0;
        for (LocalDate d = ym.atDay(1); !d.isAfter(ym.atEndOfMonth()); d = d.plusDays(1)) {
            Agg a = byDay.getOrDefault(d.toString(), Agg.ZERO);
            if (a.count() > 0) withData++;
            totalCount += a.count();
            totalAmount += a.amount();
            days.add(new CalendarDay(d.toString(), a.count(), money ? a.amount() : null));
        }
        return new Calendar(ym.toString(), md, days, withData, totalCount, money ? totalAmount : null);
    }

    // ---- geo & companies --------------------------------------------------------------------

    public Geo geo(DashboardScope scope, LocalDate from, LocalDate to) {
        scope.require("ADMIN");
        return new Geo(geoRows(Metric.STATE, scope, from, to, Integer.MAX_VALUE),
                geoRows(Metric.PINCODE, scope, from, to, GEO_PINCODE_LIMIT));
    }

    private List<GeoRow> geoRows(Metric m, DashboardScope scope, LocalDate from, LocalDate to, int limit) {
        LocalDate[] prev = MetricQueries.previousWindow(from, to);
        Map<String, Long> prevCases = new HashMap<>();
        business.geo(m, scope, prev[0], prev[1]).forEach(g -> prevCases.put(g.grp(), g.cases()));
        List<GeoRow> rows = new ArrayList<>();
        for (GeoAgg g : business.geo(m, scope, from, to)) {
            if (rows.size() >= limit) break;
            String state = m == Metric.STATE ? g.grp() : (g.extra() == null ? "Unknown" : g.extra());
            rows.add(new GeoRow(g.grp(), g.grp(), state, g.cases(), g.principal(), g.closed(), g.due(),
                    ScoreFormulas.ratio(g.closed(), g.due()), prevCases.getOrDefault(g.grp(), 0L)));
        }
        return rows;
    }

    public Companies companies(DashboardScope scope, LocalDate from, LocalDate to) {
        scope.require("ADMIN");
        List<CompanyUsersAgg> all = business.companyUsers(from, to);
        long totalUsers = business.distinctUsers(from, to);
        List<CompanyUsers> users = all.stream().limit(TOP_COMPANIES).map(c -> new CompanyUsers(titleCase(c.company()),
                c.users(), ScoreFormulas.ratio(c.users(), totalUsers), c.avgSalary(), c.employmentType())).toList();
        List<CompanyPerformance> performance = business.companyPerformance(scope, from, to, TOP_COMPANIES).stream()
                .map(c -> new CompanyPerformance(titleCase(c.company()), c.disbursed(), c.principal(), c.repayable(),
                        c.collected(), ScoreFormulas.ratio(c.collected(), c.repayable()))).toList();
        return new Companies(all.size(), totalUsers, users, performance);
    }

    static String titleCase(String s) {
        if (s == null || s.isBlank()) return "Unknown";
        StringBuilder sb = new StringBuilder();
        for (String w : s.trim().split("\\s+")) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    // ---- collection allocation --------------------------------------------------------------

    public Allocation allocation(DashboardScope scope, LocalDate from, LocalDate to) {
        scope.require("ADMIN", "COLLECTION_HEAD");
        List<StaffSummary> roster = new ArrayList<>(staffDirectory.listActive("COLLECTION_HEAD"));
        roster.addAll(staffDirectory.listActive("COLLECTION_EXECUTIVE"));
        roster = scope.filterStaff(roster, StaffSummary::id);

        Map<Long, AllocRow> table = allocationRows(scope, roster, from, to);
        AllocRow total = table.get(null);
        List<AllocRow> rows = new ArrayList<>(table.values());
        rows.removeIf(r -> r.staffId() == null);
        rows.sort(java.util.Comparator.comparingLong(AllocRow::totalPaise).reversed()
                .thenComparing(AllocRow::name, String.CASE_INSENSITIVE_ORDER));
        LocalDate[] prev = MetricQueries.previousWindow(from, to);
        AllocRow before = allocationRows(scope, roster, prev[0], prev[1]).get(null);
        AllocCardsWithPrevious cards = new AllocCardsWithPrevious(total.assigned(), total.closed(), total.totalCount(),
                total.totalPaise(), new AllocCards(before.assigned(), before.closed(), before.totalCount(),
                        before.totalPaise()));
        return new Allocation(cards, rows, total);
    }

    /** Per-staffer allocation rows keyed by staff id; the {@code null} key holds the grand total. */
    private Map<Long, AllocRow> allocationRows(DashboardScope scope, List<StaffSummary> roster, LocalDate from,
                                               LocalDate to) {
        Map<String, Agg> assigned = metrics.aggregateByGrp(Metric.STAFF_CASES, scope, from, to);
        Map<Long, Long> closed = collection.closedByOfficer(scope, from, to);
        List<Collected> collected = collection.collectedByOfficer(scope, from, to);
        Map<Long, AllocRow> out = new LinkedHashMap<>();
        long[] t = new long[8];
        for (StaffSummary s : roster) {
            long[] v = new long[8]; // assigned, closed, freshN, freshP, reloanN, reloanP, totalN, totalP
            v[0] = assigned.getOrDefault(String.valueOf(s.id()), Agg.ZERO).count();
            v[1] = closed.getOrDefault(s.id(), 0L);
            for (Collected c : collected) {
                if (c.staffId() != s.id()) continue;
                if ("FRESH".equals(c.segment())) {
                    v[2] += c.loans();
                    v[3] += c.paise();
                } else {
                    v[4] += c.loans();
                    v[5] += c.paise();
                }
            }
            v[6] = v[2] + v[4];
            v[7] = v[3] + v[5];
            for (int i = 0; i < t.length; i++) t[i] += v[i];
            out.put(s.id(), allocRow(s.id(), s.name(), v));
        }
        out.put(null, allocRow(null, "Total", t));
        return out;
    }

    private static AllocRow allocRow(Long id, String name, long[] v) {
        return new AllocRow(id, name, v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7]);
    }

    // ---- leaderboard ------------------------------------------------------------------------

    private static final List<String> BOARDS = List.of("CREDIT_HEAD", "CREDIT_EXECUTIVE", "COLLECTION_HEAD",
            "COLLECTION_EXECUTIVE", "TELECALLER");

    private static Set<String> allowedBoards(String realRole) {
        return switch (realRole) {
            case "ADMIN" -> Set.copyOf(BOARDS);
            case "CREDIT_HEAD" -> Set.of("CREDIT_HEAD", "CREDIT_EXECUTIVE");
            case "COLLECTION_HEAD" -> Set.of("COLLECTION_HEAD", "COLLECTION_EXECUTIVE");
            case "CREDIT_EXECUTIVE", "COLLECTION_EXECUTIVE", "TELECALLER" -> Set.of(realRole);
            default -> Set.of();
        };
    }

    private record Scored(Ranked ranked, List<Factor> factors) {
    }

    public Leaderboard leaderboard(DashboardScope scope, String boardName, LocalDate from, LocalDate to) {
        String board = boardName == null ? "" : boardName.trim().toUpperCase(java.util.Locale.ROOT);
        if (!BOARDS.contains(board) || !allowedBoards(scope.realRole()).contains(board)) {
            throw new BusinessException("FORBIDDEN_ROLE", "You cannot open the " + board + " leaderboard");
        }
        boolean seesFactors = "ADMIN".equals(scope.realRole()) || scope.realRole().endsWith("_HEAD");
        List<Scored> scored = board(board, from, to);
        List<LeaderRow> rows = new ArrayList<>();
        int rank = 1;
        for (Scored s : scored) {
            Ranked r = s.ranked();
            boolean self = scope.selfId() != null && scope.selfId() == r.staffId();
            rows.add(new LeaderRow(r.staffId(), r.name(), rank++, r.score(), ScoreFormulas.stars(r.score()),
                    r.volume(), self, seesFactors || self ? s.factors() : null));
        }
        return new Leaderboard(board, rows.size(), rows);
    }

    /** One board, ranked, for the active staff holding {@code board} as their role. */
    private List<Scored> board(String board, LocalDate from, LocalDate to) {
        List<StaffSummary> roster = staffDirectory.listActive(board);
        if (roster.isEmpty()) return List.of();
        List<Long> ids = roster.stream().map(StaffSummary::id).toList();
        DashboardScope scope = DashboardScope.forStaff(board, ids);
        Map<Long, List<Input>> inputs = new HashMap<>();
        Map<Long, Long> volume = new HashMap<>();

        if (board.startsWith("CREDIT_")) {
            Map<String, Agg> assigned = metrics.aggregateByGrp(Metric.STAFF_FILES, scope, from, to);
            Map<Long, CreditRaw> raw = team.creditRaw(ids, from, to);
            for (Long id : ids) {
                long a = assigned.getOrDefault(String.valueOf(id), Agg.ZERO).count();
                CreditRaw c = raw.getOrDefault(id, new CreditRaw(0, 0, 0, 0));
                inputs.put(id, ScoreFormulas.credit(a, c.sanctioned(), c.rejected(), c.dueRepayable(), c.dueCollected()));
                volume.put(id, a);
            }
        } else if (board.startsWith("COLLECTION_")) {
            List<CaseRow> cases = collection.casesCreated(scope, from, to);
            Map<Long, Long> collected = collection.collectedOnCreatedCases(scope, from, to);
            List<Loan> open = loadLoans(cases.stream().filter(c -> !c.closed()).map(CaseRow::loanId).toList());
            Map<Long, OutstandingBreakdown> priced = price(open, today());
            Map<Long, long[]> acc = new HashMap<>(); // assigned, closed, owed
            for (CaseRow c : cases) {
                long[] a = acc.computeIfAbsent(c.staffId(), k -> new long[3]);
                a[0]++;
                if (c.closed()) a[1]++; else a[2] += owed(priced, c.loanId());
            }
            long max = acc.values().stream().mapToLong(a -> a[0]).max().orElse(0);
            for (Long id : ids) {
                long[] a = acc.getOrDefault(id, new long[3]);
                inputs.put(id, ScoreFormulas.collection(collected.getOrDefault(id, 0L), a[2], a[0], a[1], max));
                volume.put(id, a[0]);
            }
        } else {
            Map<Long, TelecallerRaw> raw = team.telecallerRaw(ids, from, to);
            long max = raw.values().stream().mapToLong(TelecallerRaw::calls).max().orElse(0);
            for (Long id : ids) {
                TelecallerRaw t = raw.getOrDefault(id, new TelecallerRaw(0, 0, 0, 0));
                inputs.put(id, ScoreFormulas.telecaller(t.leads(), t.converted(), t.contacted(), t.calls(), max));
                volume.put(id, t.calls());
            }
        }

        Map<Long, Scored> byId = new HashMap<>();
        List<Ranked> entries = new ArrayList<>();
        for (StaffSummary s : roster) {
            Ranked r = new Ranked(s.id(), s.name(), ScoreFormulas.score(inputs.get(s.id())), volume.getOrDefault(s.id(), 0L));
            entries.add(r);
            byId.put(s.id(), new Scored(r, ScoreFormulas.factors(inputs.get(s.id()))));
        }
        return ScoreFormulas.rank(entries).stream().map(r -> byId.get(r.staffId())).toList();
    }

    // ---- role view --------------------------------------------------------------------------

    public RoleView roleView(DashboardScope scope, LocalDate from, LocalDate to) {
        scope.require("CREDIT_HEAD", "CREDIT_EXECUTIVE", "COLLECTION_HEAD", "COLLECTION_EXECUTIVE", "TELECALLER",
                "DISBURSEMENT_HEAD", "ACCOUNTANT");
        LocalDate[] prev = MetricQueries.previousWindow(from, to);
        return switch (scope.view()) {
            case "CREDIT_HEAD", "CREDIT_EXECUTIVE" -> creditView(scope, from, to, prev);
            case "COLLECTION_HEAD", "COLLECTION_EXECUTIVE" -> collectionView(scope, from, to, prev);
            case "TELECALLER" -> telecallerView(scope, from, to, prev);
            case "DISBURSEMENT_HEAD" -> disbursementView(scope, from, to, prev);
            default -> accountantView(scope, from, to, prev);
        };
    }

    private RoleCard metricCard(String key, String label, Metric m, DashboardScope scope, LocalDate[] cur,
                                LocalDate[] prev, String tone) {
        Agg a = metrics.aggregate(m, scope, cur[0], cur[1], null);
        Agg b = metrics.aggregate(m, scope, prev[0], prev[1], null);
        return new RoleCard(key, label, a.count(), a.amount(), b.count(), b.amount(), m, tone);
    }

    private static RoleCard plainCard(String key, String label, Long count, Long amount, Long prevCount,
                                      Long prevAmount, String tone) {
        return new RoleCard(key, label, count, amount, prevCount, prevAmount, null, tone);
    }

    /** The caller's (or the single selected staffer's) leaderboard entry, when one exists. */
    private Optional<Scored> ownScore(DashboardScope scope, LocalDate from, LocalDate to) {
        if (scope.staffIds().size() != 1) return Optional.empty();
        Long id = scope.staffIds().get(0);
        Optional<StaffSummary> who = staffDirectory.findStaff(id);
        if (who.isEmpty() || !BOARDS.contains(who.get().role())) return Optional.empty();
        return board(who.get().role(), from, to).stream().filter(s -> s.ranked().staffId() == id).findFirst();
    }

    private static List<LocalDate> daysOf(LocalDate from, LocalDate to) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) days.add(d);
        return days;
    }

    private static Chart chart(String id, String title, String kind, List<LocalDate> days,
                               LinkedHashMap<String, String> labels, Map<String, Map<LocalDate, Long>> series) {
        List<Map<String, Object>> points = new ArrayList<>();
        for (LocalDate d : days) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("date", d.toString());
            labels.keySet().forEach(k -> p.put(k, series.getOrDefault(k, Map.of()).getOrDefault(d, 0L)));
            points.add(p);
        }
        return new Chart(id, title, kind, List.copyOf(labels.keySet()), labels, points);
    }

    private static Map<LocalDate, Long> byDay(Map<String, Agg> agg, boolean money) {
        Map<LocalDate, Long> out = new HashMap<>();
        agg.forEach((k, a) -> out.put(LocalDate.parse(k), money ? a.amount() : a.count()));
        return out;
    }

    private RoleView creditView(DashboardScope scope, LocalDate from, LocalDate to, LocalDate[] prev) {
        LocalDate[] cur = {from, to};
        List<RoleCard> cards = List.of(
                metricCard("assigned", "Assigned files", Metric.STAFF_FILES, scope, cur, prev, "blue"),
                metricCard("disbursed", "Disbursed", Metric.DISBURSED, scope, cur, prev, "emerald"),
                metricCard("pending", "Pending", Metric.PENDING, scope, cur, prev, "orange"),
                metricCard("rejected", "Rejected", Metric.REJECTED, scope, cur, prev, "sky"));

        List<Long> ids = scope.sqlIds();
        List<LocalDate> days = daysOf(from, to);
        Map<LocalDate, Long> assigned = team.assignedByDay(ids, from, to);
        Map<String, Agg> disbursedByDay = metrics.aggregateByGrp(Metric.DISBURSED, scope, from, to);
        Map<LocalDate, long[]> closure = team.sanctionClosureByDay(ids, from, to);

        List<Chart> charts = List.of(
                chart("assignedVsFollowups", "Assigned vs Follow-ups", "line", days, labels("assigned", "Assigned",
                        "followups", "Follow-ups"), Map.of("assigned", assigned,
                        "followups", team.followupsByDay(ids, from, to))),
                chart("assignedVsDisbursed", "Assigned vs Disbursed", "line", days, labels("assigned", "Assigned",
                        "disbursed", "Disbursed", "disbursedPaise", "Disbursed amount"), Map.of("assigned", assigned,
                        "disbursed", byDay(disbursedByDay, false), "disbursedPaise", byDay(disbursedByDay, true))),
                chart("sanctionClosure", "Sanction Repayment Closure", "bar", days, labels("unclosed", "Unclosed",
                        "closed", "Closed"), Map.of("unclosed", split(closure, 0), "closed", split(closure, 1))));

        Optional<Scored> own = ownScore(scope, from, to);
        return new RoleView(scope.view(), cards, charts, own.map(Scored::factors).orElse(null),
                own.map(s -> s.ranked().score()).orElse(null), null, null);
    }

    private static Map<LocalDate, Long> split(Map<LocalDate, long[]> m, int idx) {
        Map<LocalDate, Long> out = new HashMap<>();
        m.forEach((d, v) -> out.put(d, v[idx]));
        return out;
    }

    private static LinkedHashMap<String, String> labels(String... kv) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put(kv[i], kv[i + 1]);
        return out;
    }

    private RoleView collectionView(DashboardScope scope, LocalDate from, LocalDate to, LocalDate[] prev) {
        LocalDate[] cur = {from, to};
        long[] c = collection.collectedTotals(scope, from, to);
        long[] p = collection.collectedTotals(scope, prev[0], prev[1]);
        List<RoleCard> cards = List.of(
                metricCard("assigned", "Assigned cases", Metric.STAFF_CASES, scope, cur, prev, "blue"),
                metricCard("closed", "Closed", Metric.CLOSED, scope, cur, prev, "emerald"),
                plainCard("collectedCount", "Loans collected on", c[0], null, p[0], null, "sky"),
                plainCard("collectedPaise", "Collected", null, c[1], null, p[1], "navy"));

        Map<LocalDate, Long> ptp = collection.ptpCollectedByDay(scope, from, to);
        Chart chart = chart("ptpCollected", "PTP Collected", "bar", daysOf(from, to),
                labels("ptpPaise", "PTP collected"), Map.of("ptpPaise", ptp));
        PtpTotals t = collection.ptpTotals(scope, from, to);
        Optional<Scored> own = ownScore(scope, from, to);
        return new RoleView(scope.view(), cards, List.of(chart), own.map(Scored::factors).orElse(null),
                own.map(s -> s.ranked().score()).orElse(null),
                new Ptp(t.afterAssignment(), t.sameDay(), t.latest() == null ? null : t.latest().toString()), null);
    }

    private RoleView telecallerView(DashboardScope scope, LocalDate from, LocalDate to, LocalDate[] prev) {
        List<Long> ids = scope.sqlIds();
        long[] now = team.telecallerCards(ids, from, to);
        long[] before = team.telecallerCards(ids, prev[0], prev[1]);
        long calls = team.callsByStaff(ids, from, to).values().stream().mapToLong(Long::longValue).sum();
        long callsBefore = team.callsByStaff(ids, prev[0], prev[1]).values().stream().mapToLong(Long::longValue).sum();
        long conv = team.telecallerConversions(ids, from, to);
        long convBefore = team.telecallerConversions(ids, prev[0], prev[1]);
        List<RoleCard> cards = List.of(
                plainCard("leads", "Leads", now[0], null, before[0], null, "blue"),
                plainCard("calls", "Calls", calls, null, callsBefore, null, "emerald"),
                plainCard("callbacks", "Callbacks", now[1], null, before[1], null, "orange"),
                plainCard("conversions", "Conversions", conv, null, convBefore, null, "sky"));
        Chart chart = chart("leadsVsCalls", "Leads vs Calls", "line", daysOf(from, to),
                labels("leads", "Leads", "calls", "Calls"),
                Map.of("leads", team.leadsByDay(ids, from, to), "calls", team.callsByDay(ids, from, to)));
        Optional<Scored> own = ownScore(scope, from, to);
        return new RoleView(scope.view(), cards, List.of(chart), own.map(Scored::factors).orElse(null),
                own.map(s -> s.ranked().score()).orElse(null), null, null);
    }

    private RoleView disbursementView(DashboardScope scope, LocalDate from, LocalDate to, LocalDate[] prev) {
        LocalDate[] cur = {from, to};
        long failed = team.disbursalFailed(from, to);
        long failedBefore = team.disbursalFailed(prev[0], prev[1]);
        List<RoleCard> cards = List.of(
                metricCard("pendingDisbursal", "Pending disbursal", Metric.PENDING_DISBURSAL, scope, cur, prev, "orange"),
                metricCard("disbursed", "Disbursed", Metric.DISBURSED, scope, cur, prev, "emerald"),
                plainCard("failed", "Failed", failed, null, failedBefore, null, "red"));
        return new RoleView(scope.view(), cards, List.of(), null, null, null,
                business.rateTable(Metric.PF_RATE, scope, from, to));
    }

    private RoleView accountantView(DashboardScope scope, LocalDate from, LocalDate to, LocalDate[] prev) {
        Map<String, long[]> now = team.paymentsByStatus(from, to);
        Map<String, long[]> before = team.paymentsByStatus(prev[0], prev[1]);
        long[] zero = new long[2];
        long[] pending = team.pendingVerification();
        List<RoleCard> cards = List.of(
                plainCard("verified", "Verified", now.getOrDefault("VERIFIED", zero)[0],
                        now.getOrDefault("VERIFIED", zero)[1], before.getOrDefault("VERIFIED", zero)[0],
                        before.getOrDefault("VERIFIED", zero)[1], "emerald"),
                plainCard("pendingVerification", "Pending verification", pending[0], pending[1], null, null, "orange"),
                plainCard("rejectedPayments", "Rejected", now.getOrDefault("REJECTED", zero)[0],
                        now.getOrDefault("REJECTED", zero)[1], before.getOrDefault("REJECTED", zero)[0],
                        before.getOrDefault("REJECTED", zero)[1], "red"));
        return new RoleView(scope.view(), cards, List.of(), null, null, null, null);
    }

    // ---- records (drill-down) ---------------------------------------------------------------

    public Records records(DashboardScope scope, String metricName, String key, String segment, String q, int page,
                           int size, LocalDate from, LocalDate to) {
        Metric m;
        try {
            m = Metric.valueOf(metricName == null ? "" : metricName.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException("INVALID_METRIC", "Unknown metric " + metricName);
        }
        if (!m.views().contains(scope.view())) {
            throw new BusinessException("FORBIDDEN_ROLE", "The " + scope.view() + " view cannot read " + m);
        }
        if ((m == Metric.STAFF_FILES || m == Metric.STAFF_CASES) && key != null && !key.isBlank()
                && !scope.allStaff() && !scope.staffIds().contains(parseStaffKey(key))) {
            throw new BusinessException("FORBIDDEN_ROLE", "That staff member is outside your team");
        }
        int pageSize = Math.max(1, Math.min(size, MAX_RECORDS_PAGE));
        Page p = metrics.records(m, scope, from, to, key, segment, q, Math.max(0, page), pageSize);

        LocalDate asOf = m == Metric.AUM_BUCKET ? to : today();
        List<Long> pageLoanIds = p.rows().stream().map(RowKey::loanId).filter(java.util.Objects::nonNull).toList();
        List<Loan> loans = loadLoans(pageLoanIds);
        Map<Long, Loan> loanById = new HashMap<>();
        loans.forEach(l -> loanById.put(l.getId(), l));
        Map<Long, OutstandingBreakdown> priced = price(loans, asOf);
        Map<Long, AppDetail> details = metrics.details(p.rows().stream().map(RowKey::applicationId).toList());
        Map<Long, String> names = staffDirectory.namesFor(details.values().stream().map(AppDetail::assigneeId)
                .filter(java.util.Objects::nonNull).distinct().toList());

        List<RecordRow> rows = new ArrayList<>();
        for (RowKey r : p.rows()) {
            AppDetail d = details.get(r.applicationId());
            Loan l = r.loanId() == null ? null : loanById.get(r.loanId());
            rows.add(new RecordRow(r.applicationId(), r.loanId(), d == null ? null : d.customerId(),
                    d == null ? null : d.fullName(), d == null ? null : last4(d.mobile()), d == null ? null : d.status(),
                    r.segment(), r.amount(), l == null ? null : owed(priced, l.getId()),
                    l == null || l.getDisbursedOn() == null ? null : l.getDisbursedOn().toString(),
                    l == null || l.getDueDate() == null ? null : l.getDueDate().toString(),
                    l == null || l.getClosedOn() == null ? null : l.getClosedOn().toString(),
                    d == null || d.assigneeId() == null ? null : names.get(d.assigneeId()),
                    d == null ? null : d.state()));
        }
        long sum = p.sum();
        if (m.owedSum()) {
            sum = price(loadLoans(p.loanIds()), asOf).values().stream()
                    .mapToLong(OutstandingBreakdown::outstandingPaise).sum();
        }
        return new Records(rows, p.total(), p.fresh(), p.reloan(), sum);
    }

    private static Long parseStaffKey(String key) {
        try {
            return Long.valueOf(key.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException("INVALID_KEY", "Staff keys are numeric ids");
        }
    }

    private static String last4(String mobile) {
        if (mobile == null) return null;
        String digits = mobile.replaceAll("\\D", "");
        return digits.isEmpty() ? null : digits.substring(Math.max(0, digits.length() - 4));
    }

    // ---- targets ----------------------------------------------------------------------------

    public List<TargetRow> targets(String from, String to) {
        requireStaff();
        YearMonth now = YearMonth.from(today());
        YearMonth f = parseMonth(from, now.minusMonths(5));
        YearMonth t = parseMonth(to, now.plusMonths(1));
        if (f.isAfter(t) || java.time.temporal.ChronoUnit.MONTHS.between(f, t) > 36) {
            throw new BusinessException("INVALID_RANGE", "from must not be after to, and the span at most 36 months");
        }
        return new ArrayList<>(targetMap(f, t).values());
    }

    @Transactional
    public TargetRow saveTarget(String month, TargetUpdate body) {
        var actor = ActorContext.get();
        if (!"ADMIN".equals(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "Only ADMIN can set targets");
        }
        YearMonth ym = parseMonth(month, null);
        if (ym == null) throw new BusinessException("INVALID_DATE", "Months must be yyyy-MM");
        if (body == null || body.collectionTargetBp() < 0 || body.collectionTargetBp() > 10000) {
            throw new BusinessException("INVALID_TARGET", "The collection target must be between 0 and 10000 bp");
        }
        if (body.disbursalTargetPaise() != null && body.disbursalTargetPaise() < 0) {
            throw new BusinessException("INVALID_TARGET", "The disbursal target cannot be negative");
        }
        business.saveTarget(ym, body.disbursalTargetPaise(), body.collectionTargetBp(), actor.id());
        return new TargetRow(ym.toString(), body.disbursalTargetPaise(), body.collectionTargetBp());
    }
}
