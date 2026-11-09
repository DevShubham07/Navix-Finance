package com.navix.loan.service;

import com.navix.loan.dto.AnalyzerDtos.AnalyzerView;
import com.navix.loan.dto.AnalyzerDtos.BandRow;
import com.navix.common.security.ActorContext;
import com.navix.loan.dto.AnalyzerDtos.Benchmarks;
import com.navix.loan.dto.AnalyzerDtos.CustomerFacts;
import com.navix.loan.dto.AnalyzerDtos.GroupStats;
import com.navix.loan.dto.AnalyzerDtos.IncomeStats;
import com.navix.loan.dto.AnalyzerDtos.OwnRecord;
import com.navix.loan.dto.AnalyzerDtos.PatternCount;
import com.navix.loan.dto.AnalyzerDtos.PointRow;
import com.navix.loan.dto.AnalyzerDtos.Pointer;
import com.navix.loan.dto.AnalyzerDtos.RepeatStats;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The staff-only AI Analyzer: a rule-based summary of how comparable past loans turned out. Advisory
 * only; it never blocks, rejects or changes a loan. Every number comes from the pure static methods
 * below (rows + today in, figures out); SQL lives only in the two thin loaders.
 *
 * <p>Banned inputs (never read here): gender, age, geography, sanctioning officer, limit overrides,
 * payment method, mandates, call counts, remarks. Settlement is read only to label an outcome.
 */
@Service
public class BorrowerAnalyzerService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LoanMath MATH = new LoanMath();
    private static final int TOLERANCE_DAYS = ApplicationFlowService.LATE_REPAYMENT_TOLERANCE_DAYS;
    /** Probable internal test account. */
    private static final Set<Long> EXCLUDED_CUSTOMERS = Set.of(5914823L);
    static final LocalDate RECENT_START = LocalDate.of(2026, 9, 1);
    private static final long SALARY_50K_PAISE = 5_000_000L;
    private static final List<String> PATTERNS = List.of(
            "ALWAYS_PAID", "PAID_THEN_OVERDUE", "FIRST_OVERDUE_SOME_PAID", "NEVER_PAID", "TOO_EARLY");

    /** One row per loan; see {@link #LOAN_ROWS_SQL}. */
    record LoanRow(long loanId, long customerId, long principal, long netDisbursed, long totalRepayable,
                   String status, LocalDate disbursedOn, LocalDate dueDate, LocalDate closedOn, long paid,
                   boolean settled, Long salaryPaise, Integer totalAccounts, Boolean employedAtDeclared,
                   Integer score, BigDecimal stars, String recommendation, String riskCategory) {
    }

    /** What the customer's applications say (newest first), including applications with no loan. */
    record Inputs(boolean hasApplication, Long sanctionedPaise, Long requestedPaise,
                  LocalDate approvedRepaymentDate, CustomerFacts facts) {
    }

    enum Outcome { GOOD, BAD, UNRESOLVED }

    private record Loan(LoanRow row, int seq, Outcome outcome, LoanRow prior) {
        boolean bad() {
            return outcome == Outcome.BAD;
        }

        boolean resolved() {
            return outcome != Outcome.UNRESOLVED;
        }
    }

    static final String LOAN_ROWS_SQL = """
            select l.id as loan_id, l.customer_id, l.principal, l.net_disbursed, l.total_repayable, l.status,
                   l.disbursed_on, l.due_date, l.closed_on,
                   coalesce((select sum(pm.amount) from payment pm
                              where pm.loan_id = l.id and pm.status = 'VERIFIED'), 0) as paid,
                   exists (select 1 from settlement s join collection_case c on c.id = s.collection_case_id
                            where c.loan_id = l.id and s.status = 'APPROVED') as settled,
                   p.monthly_salary_paise as salary_paise,
                   (p.credit_brief_facts->>'totalAccounts')::int as total_accounts,
                   (select case when lower(v.derived->>'employedAtDeclaredEmployer') in ('true','false')
                                then (v.derived->>'employedAtDeclaredEmployer')::boolean end
                      from application_verification v
                     where v.application_id = a.id and v.check_type = 'EMPLOYMENT'
                     order by v.created_at desc limit 1) as employed,
                   coalesce((p.credit_brief_facts->>'creditScore')::int, p.bureau_score::int) as score,
                   p.credit_star_rating as stars, p.credit_recommendation as recommendation,
                   p.risk_category as risk_category
              from loan l
              join loan_application a on a.loan_id = l.id
              join customer_profile p on p.application_id = a.id
             order by l.customer_id, l.disbursed_on, l.id
            """;

    /** Newest application first; the first row is the customer's newest application. */
    static final String INPUTS_SQL = """
            select a.sanctioned_amount_paise, a.amount_requested, a.approved_repayment_date,
                   p.monthly_salary_paise as salary_paise,
                   (p.credit_brief_facts->>'totalAccounts')::int as total_accounts,
                   (select case when lower(v.derived->>'employedAtDeclaredEmployer') in ('true','false')
                                then (v.derived->>'employedAtDeclaredEmployer')::boolean end
                      from application_verification v
                     where v.application_id = a.id and v.check_type = 'EMPLOYMENT'
                     order by v.created_at desc limit 1) as employed,
                   coalesce((p.credit_brief_facts->>'creditScore')::int, p.bureau_score::int) as score,
                   p.credit_star_rating as stars, p.credit_recommendation as recommendation,
                   p.risk_category as risk_category
              from loan_application a
              left join customer_profile p on p.application_id = a.id
             where a.customer_id = :cid
             order by a.id desc
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final CustomerService customerService;

    public BorrowerAnalyzerService(JdbcTemplate jdbcTemplate, CustomerService customerService) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.customerService = customerService;
    }

    public AnalyzerView analyze(long customerId) {
        customerService.requireVisible(customerId);
        // ponytail: scans every loan per request; cache it if the book reaches several thousand loans.
        List<LoanRow> rows = jdbc.query(LOAN_ROWS_SQL, new MapSqlParameterSource(), (rs, i) -> loanRow(rs));
        AnalyzerView v = view(rows, loadInputs(customerId), customerId, LocalDate.now(IST));
        return forRole(v, ActorContext.get().role());
    }

    static AnalyzerView forRole(AnalyzerView v, String role) {
        return MONEY_VIEW_ROLES.contains(role) ? v : withoutBookMoney(v);
    }

    /** Roles that may see the book-wide rupee totals; everyone else gets counts only. */
    private static final java.util.Set<String> MONEY_VIEW_ROLES =
            java.util.Set.of("ADMIN", "CREDIT_HEAD", "COLLECTION_HEAD", "DISBURSEMENT_HEAD");

    static AnalyzerView withoutBookMoney(AnalyzerView v) {
        Benchmarks b = v.benchmarks();
        Benchmarks nb = new Benchmarks(b.recentStart(), noMoney(b.all()), noMoney(b.earlyFirst()),
                noMoney(b.recentFirst()), noMoney(b.repeat()), null, b.patterns(), b.concerns(), b.informational(),
                b.pointTable(), b.bands(), b.incomeUncorroborated(), b.repeatEarlyPayer());
        return new AnalyzerView(v.asOf(), v.mode(), v.band(), v.concernCount(), v.unknownCount(), v.pointers(),
                v.informational(), v.facts(), v.ownRecord(), v.incomeUncorroborated(), nb);
    }

    private static GroupStats noMoney(GroupStats g) {
        return new GroupStats(g.key(), g.loans(), g.customers(), g.bad(), null, null, null, null, g.zeroPaidBad());
    }

    // ---- loaders (SQL only) ------------------------------------------------------------------

    private static <T> T get(ResultSet rs, String col, Class<T> type) throws SQLException {
        return rs.getObject(col, type);
    }

    private static LoanRow loanRow(ResultSet rs) throws SQLException {
        return new LoanRow(rs.getLong("loan_id"), rs.getLong("customer_id"), rs.getLong("principal"),
                rs.getLong("net_disbursed"), rs.getLong("total_repayable"), rs.getString("status"),
                get(rs, "disbursed_on", LocalDate.class), get(rs, "due_date", LocalDate.class),
                get(rs, "closed_on", LocalDate.class), rs.getLong("paid"), rs.getBoolean("settled"),
                get(rs, "salary_paise", Long.class), get(rs, "total_accounts", Integer.class),
                get(rs, "employed", Boolean.class), get(rs, "score", Integer.class),
                rs.getBigDecimal("stars"), rs.getString("recommendation"), rs.getString("risk_category"));
    }

    private record AppRow(Long sanctioned, Long requested, LocalDate approvedOn, CustomerFacts facts) {
    }

    private Inputs loadInputs(long customerId) {
        List<AppRow> apps = jdbc.query(INPUTS_SQL, new MapSqlParameterSource("cid", customerId), (rs, i) ->
                new AppRow(get(rs, "sanctioned_amount_paise", Long.class), get(rs, "amount_requested", Long.class),
                        get(rs, "approved_repayment_date", LocalDate.class),
                        new CustomerFacts(get(rs, "salary_paise", Long.class), get(rs, "total_accounts", Integer.class),
                                get(rs, "employed", Boolean.class), get(rs, "score", Integer.class),
                                rs.getBigDecimal("stars"), rs.getString("recommendation"),
                                rs.getString("risk_category"))));
        if (apps.isEmpty()) {
            return new Inputs(false, null, null, null, new CustomerFacts(null, null, null, null, null, null, null));
        }
        // Newest non-null per field across all of the customer's applications.
        CustomerFacts f = new CustomerFacts(
                first(apps, a -> a.facts().salaryPaise()), first(apps, a -> a.facts().totalAccounts()),
                first(apps, a -> a.facts().employedAtDeclared()), first(apps, a -> a.facts().score()),
                first(apps, a -> a.facts().stars()), first(apps, a -> a.facts().recommendation()),
                first(apps, a -> a.facts().riskCategory()));
        AppRow newest = apps.get(0);
        return new Inputs(true, newest.sanctioned(), newest.requested(), newest.approvedOn(), f);
    }

    private static <A, T> T first(List<A> items, Function<A, T> f) {
        for (A a : items) {
            T v = f.apply(a);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    // ---- outcome -----------------------------------------------------------------------------

    static Outcome outcome(LoanRow r, LocalDate today) {
        if (r.dueDate() == null) {
            return Outcome.UNRESOLVED;
        }
        if (closed(r)) {
            if (r.settled() && r.paid() < r.totalRepayable()) {
                return Outcome.BAD;
            }
            if (r.closedOn() == null) {
                return Outcome.UNRESOLVED;
            }
            return daysLate(r) <= TOLERANCE_DAYS ? Outcome.GOOD : Outcome.BAD;
        }
        return ChronoUnit.DAYS.between(r.dueDate(), today) > TOLERANCE_DAYS ? Outcome.BAD : Outcome.UNRESOLVED;
    }

    private static int daysLate(LoanRow r) {
        return (int) ChronoUnit.DAYS.between(r.dueDate(), r.closedOn());
    }

    private static boolean closed(LoanRow r) {
        return "CLOSED".equals(r.status());
    }

    /** Loans in per-customer sequence order (disbursedOn, loanId), each with its outcome and prior loan. */
    private static List<Loan> analyse(List<LoanRow> rows, LocalDate today) {
        Map<Long, List<LoanRow>> byCustomer = new LinkedHashMap<>();
        rows.stream()
                .sorted(Comparator.comparingLong(LoanRow::customerId)
                        .thenComparing(LoanRow::disbursedOn, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparingLong(LoanRow::loanId))
                .forEach(r -> byCustomer.computeIfAbsent(r.customerId(), k -> new ArrayList<>()).add(r));
        List<Loan> out = new ArrayList<>();
        for (List<LoanRow> mine : byCustomer.values()) {
            for (int i = 0; i < mine.size(); i++) {
                out.add(new Loan(mine.get(i), i + 1, outcome(mine.get(i), today), i > 0 ? mine.get(i - 1) : null));
            }
        }
        return out;
    }

    // ---- signs -------------------------------------------------------------------------------

    static Boolean thin(Integer accounts) {
        return accounts == null ? null : accounts < 30;
    }

    static Boolean longWait(Integer tenureDays) {
        return tenureDays == null ? null : tenureDays >= 28;
    }

    static Boolean largeTicket(Long principalPaise) {
        return principalPaise == null ? null : principalPaise > 750_000L;
    }

    static Boolean jobNotConfirmed(Boolean employed) {
        return employed == null ? null : !employed;
    }

    private static Integer tenure(LoanRow r) {
        return r.disbursedOn() == null || r.dueDate() == null
                ? null : (int) ChronoUnit.DAYS.between(r.disbursedOn(), r.dueDate());
    }

    /** The four tallied signs on a past loan. A missing job check is "no concern" here, never missing. */
    private static final Map<String, Function<LoanRow, Boolean>> CONCERNS = new LinkedHashMap<>();

    static {
        CONCERNS.put("THIN_RECORD", r -> thin(r.totalAccounts()));
        CONCERNS.put("LONG_WAIT", r -> longWait(tenure(r)));
        CONCERNS.put("LARGE_TICKET", r -> largeTicket(r.principal()));
        CONCERNS.put("JOB_NOT_CONFIRMED", r -> Boolean.FALSE.equals(r.employedAtDeclared()));
    }

    /** Checked-but-not-relied-on signs; null = the data is missing. */
    private static final Map<String, Function<CustomerFacts, Boolean>> INFO = new LinkedHashMap<>();

    static {
        INFO.put("SALARY_50K", f -> f.salaryPaise() == null ? null : f.salaryPaise() >= SALARY_50K_PAISE);
        INFO.put("STARS_3", f -> f.stars() == null ? null : f.stars().compareTo(BigDecimal.valueOf(3)) >= 0);
        INFO.put("RECOMMENDED", f -> {
            if (f.recommendation() == null) {
                return null;
            }
            String s = f.recommendation().trim();
            return s.equalsIgnoreCase("RECOMMEND") || s.equalsIgnoreCase("STRONGLY RECOMMEND");
        });
        INFO.put("RISK_B", f -> f.riskCategory() == null ? null : "B".equalsIgnoreCase(f.riskCategory().trim()));
        INFO.put("SCORE_700", f -> f.score() == null ? null : f.score() >= 700);
    }

    private static CustomerFacts factsOf(LoanRow r) {
        return new CustomerFacts(r.salaryPaise(), r.totalAccounts(), r.employedAtDeclared(), r.score(), r.stars(),
                r.recommendation(), r.riskCategory());
    }

    static boolean incomeUncorroborated(Long salaryPaise, Boolean employed, Integer accounts) {
        return salaryPaise != null && salaryPaise >= SALARY_50K_PAISE && Boolean.FALSE.equals(employed)
                && accounts != null && accounts < 50;
    }

    static String bandOf(int concerns) {
        return concerns <= 1 ? "LOWER" : concerns == 2 ? "WATCH" : "HIGH";
    }

    /** LOWER is a claim of safety, so it needs the unknowns to be unable to raise the band; WATCH/HIGH are floors. */
    static String firstLoanBand(int concerns, int unknown) {
        return concerns <= 1 && concerns + unknown >= 2 ? "INCOMPLETE" : bandOf(concerns);
    }

    // ---- benchmarks --------------------------------------------------------------------------

    private static Pointer split(String key, List<Loan> loans, Function<Loan, Boolean> sign) {
        int wl = 0, wb = 0, ol = 0, ob = 0;
        for (Loan l : loans) {
            Boolean v = sign.apply(l);
            if (v == null) {
                continue; // missing data: in neither group
            }
            if (v) {
                wl++;
                wb += l.bad() ? 1 : 0;
            } else {
                ol++;
                ob += l.bad() ? 1 : 0;
            }
        }
        return new Pointer(key, null, null, wl, wb, ol, ob);
    }

    private static GroupStats group(String key, List<Loan> ls) {
        int bad = 0, zero = 0;
        long principal = 0, badPrincipal = 0, paidOut = 0, collected = 0;
        for (Loan l : ls) {
            LoanRow r = l.row();
            principal += r.principal();
            paidOut += r.netDisbursed() + MATH.gstPaise(r.principal());
            collected += r.paid();
            if (l.bad()) {
                bad++;
                badPrincipal += r.principal();
                zero += r.paid() == 0 ? 1 : 0;
            }
        }
        int customers = (int) ls.stream().map(l -> l.row().customerId()).distinct().count();
        return new GroupStats(key, ls.size(), customers, bad, principal, badPrincipal, paidOut, collected, zero);
    }

    /** Bad share (by principal) at which the book nets to zero, in basis points; null when undefined. */
    private static Integer breakEvenBps(List<Loan> set) {
        double goodPrincipal = 0, goodCollected = 0, goodOut = 0, badPrincipal = 0, badCollected = 0, badOut = 0;
        for (Loan l : set) {
            LoanRow r = l.row();
            double out = r.netDisbursed() + MATH.gstPaise(r.principal());
            if (l.bad()) {
                badPrincipal += r.principal();
                badCollected += r.paid();
                badOut += out;
            } else {
                goodPrincipal += r.principal();
                goodCollected += r.paid();
                goodOut += out;
            }
        }
        if (goodPrincipal == 0 || badPrincipal == 0) {
            return null;
        }
        double gain = (goodCollected - goodOut) / goodPrincipal;
        double loss = (badOut - badCollected) / badPrincipal;
        return gain + loss <= 0 ? null : (int) Math.round(gain / (gain + loss) * 10_000);
    }

    private static String pattern(List<Loan> resolvedInOrder) {
        if (resolvedInOrder.isEmpty()) {
            return "TOO_EARLY";
        }
        if (resolvedInOrder.stream().noneMatch(Loan::bad)) {
            return "ALWAYS_PAID";
        }
        Loan first = resolvedInOrder.get(0);
        if (!first.bad()) {
            return "PAID_THEN_OVERDUE";
        }
        return first.row().paid() == 0 ? "NEVER_PAID" : "FIRST_OVERDUE_SOME_PAID";
    }

    static Benchmarks benchmarks(List<LoanRow> allRows, LocalDate today) {
        List<Loan> book = analyse(allRows, today).stream()
                .filter(l -> !EXCLUDED_CUSTOMERS.contains(l.row().customerId())).toList();
        // Only loans due at least 6 days ago, so early payers on not-yet-due loans cannot flatter recent cohorts.
        LocalDate cutoff = today.minusDays(TOLERANCE_DAYS + 1L);
        List<Loan> set = book.stream()
                .filter(l -> l.resolved() && l.row().dueDate() != null && !l.row().dueDate().isAfter(cutoff))
                .toList();
        List<Loan> first = set.stream().filter(l -> l.seq() == 1).toList();
        List<Loan> repeat = set.stream().filter(l -> l.seq() > 1).toList();
        List<Loan> recent = first.stream()
                .filter(l -> l.row().disbursedOn() != null && !l.row().disbursedOn().isBefore(RECENT_START)).toList();
        List<Loan> early = first.stream()
                .filter(l -> l.row().disbursedOn() != null && l.row().disbursedOn().isBefore(RECENT_START)).toList();

        List<Pointer> concerns = new ArrayList<>();
        CONCERNS.forEach((k, f) -> concerns.add(split(k, recent, l -> f.apply(l.row()))));
        List<Pointer> info = new ArrayList<>();
        INFO.forEach((k, f) -> info.add(split(k, recent, l -> f.apply(factsOf(l.row())))));

        int[][] pts = new int[5][3]; // loans, bad, zeroPaid
        for (Loan l : recent) {
            int p = (int) CONCERNS.values().stream().filter(f -> Boolean.TRUE.equals(f.apply(l.row()))).count();
            pts[p][0]++;
            pts[p][1] += l.bad() ? 1 : 0;
            pts[p][2] += l.row().paid() == 0 ? 1 : 0;
        }
        List<PointRow> table = new ArrayList<>();
        for (int p = 0; p < 5; p++) {
            table.add(new PointRow(p, pts[p][0], pts[p][1], pts[p][2]));
        }
        List<BandRow> bands = List.of(
                new BandRow("LOWER", pts[0][0] + pts[1][0], pts[0][1] + pts[1][1]),
                new BandRow("WATCH", pts[2][0], pts[2][1]),
                new BandRow("HIGH", pts[3][0] + pts[4][0], pts[3][1] + pts[4][1]));

        List<Loan> uncorroborated = first.stream()
                .filter(l -> incomeUncorroborated(l.row().salaryPaise(), l.row().employedAtDeclared(),
                        l.row().totalAccounts())).toList();
        IncomeStats income = new IncomeStats(uncorroborated.size(),
                (int) uncorroborated.stream().filter(Loan::bad).count(),
                (int) uncorroborated.stream().filter(l -> l.row().paid() == 0).count());

        int earlyN = 0, earlyBad = 0, otherN = 0, otherBad = 0;
        for (Loan l : repeat) {
            LoanRow prior = l.prior();
            if (prior == null || !closed(prior) || prior.closedOn() == null || prior.dueDate() == null) {
                continue;
            }
            if (ChronoUnit.DAYS.between(prior.closedOn(), prior.dueDate()) >= 8) {
                earlyN++;
                earlyBad += l.bad() ? 1 : 0;
            } else {
                otherN++;
                otherBad += l.bad() ? 1 : 0;
            }
        }

        // Customer patterns: every resolved loan of every customer in the book, any due date.
        Map<Long, List<Loan>> resolvedByCustomer = new TreeMap<>();
        for (Loan l : book) {
            List<Loan> list = resolvedByCustomer.computeIfAbsent(l.row().customerId(), k -> new ArrayList<>());
            if (l.resolved()) {
                list.add(l);
            }
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        PATTERNS.forEach(p -> counts.put(p, 0));
        resolvedByCustomer.values().forEach(list -> counts.merge(pattern(list), 1, Integer::sum));
        List<PatternCount> patterns = counts.entrySet().stream()
                .map(e -> new PatternCount(e.getKey(), e.getValue())).toList();

        return new Benchmarks(RECENT_START.toString(), group("ALL", set), group("EARLY_FIRST", early),
                group("RECENT_FIRST", recent), group("REPEAT", repeat), breakEvenBps(set), patterns, concerns, info,
                table, bands, income, new RepeatStats(earlyN, earlyBad, otherN, otherBad));
    }

    // ---- this customer -----------------------------------------------------------------------

    private static String state(Boolean concern) {
        return concern == null ? "UNKNOWN" : concern ? "CONCERN" : "OK";
    }

    private static String yesNo(Boolean v) {
        return v == null ? "UNKNOWN" : v ? "YES" : "NO";
    }

    private static Pointer withState(Pointer counts, String state, Long value) {
        return new Pointer(counts.key(), state, value, counts.withLoans(), counts.withBad(), counts.withoutLoans(),
                counts.withoutBad());
    }

    static AnalyzerView view(List<LoanRow> rows, Inputs in, long customerId, LocalDate today) {
        Benchmarks bench = benchmarks(rows, today);
        List<Loan> mine = analyse(rows.stream().filter(r -> r.customerId() == customerId).toList(), today);
        CustomerFacts facts = in.facts();
        boolean hasResolved = mine.stream().anyMatch(Loan::resolved);
        List<Loan> open = mine.stream().filter(l -> !closed(l.row())).toList();
        String mode = hasResolved ? "OWN_RECORD" : (!open.isEmpty() || in.hasApplication()) ? "FIRST_LOAN" : "NO_DATA";
        boolean uncorroborated =
                incomeUncorroborated(facts.salaryPaise(), facts.employedAtDeclared(), facts.totalAccounts());
        if (mode.equals("NO_DATA")) {
            return new AnalyzerView(today.toString(), mode, null, 0, 0, List.of(), List.of(), facts, null,
                    uncorroborated, bench);
        }

        Loan openNow = open.isEmpty() ? null : open.get(open.size() - 1);
        Long ticket = openNow != null ? Long.valueOf(openNow.row().principal())
                : in.sanctionedPaise() != null ? in.sanctionedPaise() : in.requestedPaise();
        Integer tenure = null;
        if (openNow != null) {
            tenure = tenure(openNow.row());
        } else if (in.approvedRepaymentDate() != null) {
            tenure = (int) ChronoUnit.DAYS.between(today, in.approvedRepaymentDate());
        }
        Map<String, Boolean> signs = new HashMap<>();
        signs.put("THIN_RECORD", thin(facts.totalAccounts()));
        signs.put("LONG_WAIT", longWait(tenure));
        signs.put("LARGE_TICKET", largeTicket(ticket));
        signs.put("JOB_NOT_CONFIRMED", jobNotConfirmed(facts.employedAtDeclared()));
        Map<String, Long> values = new HashMap<>();
        values.put("THIN_RECORD", facts.totalAccounts() == null ? null : facts.totalAccounts().longValue());
        values.put("LONG_WAIT", tenure == null ? null : tenure.longValue());
        values.put("LARGE_TICKET", ticket);

        List<Pointer> pointers = new ArrayList<>();
        int concernCount = 0, unknownCount = 0;
        for (Pointer c : bench.concerns()) {
            Boolean s = signs.get(c.key());
            pointers.add(withState(c, state(s), values.get(c.key())));
            concernCount += Boolean.TRUE.equals(s) ? 1 : 0;
            unknownCount += s == null ? 1 : 0;
        }
        List<Pointer> informational = new ArrayList<>();
        for (Pointer c : bench.informational()) {
            Long v = c.key().equals("SALARY_50K") ? facts.salaryPaise()
                    : c.key().equals("SCORE_700") && facts.score() != null ? Long.valueOf(facts.score()) : null;
            informational.add(withState(c, yesNo(INFO.get(c.key()).apply(facts)), v));
        }

        OwnRecord own = null;
        String band = firstLoanBand(concernCount, unknownCount);
        if (mode.equals("OWN_RECORD")) {
            own = ownRecord(mine, today);
            boolean watchOpen = open.stream().anyMatch(l -> {
                long dpd = ChronoUnit.DAYS.between(l.row().dueDate(), today);
                return dpd >= 1 && dpd <= TOLERANCE_DAYS;
            });
            band = own.bad() > 0 ? "HIGH" : (own.late1to5() > 0 || watchOpen) ? "WATCH" : "LOWER";
        }
        return new AnalyzerView(today.toString(), mode, band, concernCount, unknownCount, pointers, informational,
                facts, own, uncorroborated, bench);
    }

    private static OwnRecord ownRecord(List<Loan> mine, LocalDate today) {
        int good = 0, bad = 0, onTime = 0, late = 0, zeroBad = 0, openN = 0;
        Integer maxDpd = null;
        long principal = 0, collected = 0;
        Loan lastClosed = null;
        for (Loan l : mine) {
            LoanRow r = l.row();
            principal += r.principal();
            collected += r.paid();
            if (l.outcome() == Outcome.GOOD) {
                good++;
                int dl = daysLate(r);
                onTime += dl <= 0 ? 1 : 0;
                late += dl >= 1 ? 1 : 0;
            } else if (l.bad()) {
                bad++;
                zeroBad += r.paid() == 0 ? 1 : 0;
            }
            if (!closed(r)) {
                openN++;
                if (r.dueDate() != null) {
                    int dpd = (int) ChronoUnit.DAYS.between(r.dueDate(), today);
                    maxDpd = maxDpd == null ? dpd : Math.max(maxDpd, dpd);
                }
            } else if (r.closedOn() != null && r.dueDate() != null) {
                lastClosed = l;
            }
        }
        Integer lastEarly = lastClosed == null ? null
                : (int) ChronoUnit.DAYS.between(lastClosed.row().closedOn(), lastClosed.row().dueDate());
        return new OwnRecord(mine.size(), good + bad, good, bad, onTime, late, openN, maxDpd, zeroBad, lastEarly,
                principal, collected);
    }
}
