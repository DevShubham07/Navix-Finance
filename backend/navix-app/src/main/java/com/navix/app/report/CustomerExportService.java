package com.navix.app.report;

import com.navix.app.report.CustomerExportFields.Field;
import com.navix.app.report.CustomerExportFields.Grain;
import com.navix.app.report.CustomerExportFields.Live;
import com.navix.common.exception.BusinessException;
import com.navix.common.query.ColumnFilter;
import com.navix.common.query.FilterSpec;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.domain.LoanStatus;
import com.navix.loan.entity.Loan;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.service.CustomerBookQuery;
import com.navix.loan.service.RepaymentService;
import com.navix.loan.service.RepaymentService.OutstandingBreakdown;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * ADMIN-only customer data export. Three calls: the field catalog, {@link #match} (who is in, as ids,
 * by the same segment / DPD / joined-date logic as the Customers tab — it runs {@link CustomerBookQuery}
 * itself) and {@link #rows} (the chosen columns for up to 200 of those ids). The browser pages through
 * the ids and builds the file, so no response is large and nothing is staged on the server.
 *
 * <p>Load: one 1 vCPU task and a micro database, so two exports run at a time ({@link #gate}); a third
 * gets {@code EXPORT_BUSY} and the client retries. Statements built here carry a 30 second timeout
 * (the book query inside {@code match} is the shared one and does not).
 *
 * <p>Audit: one log line per match and one per rows request that includes ID numbers — who, how many,
 * never a value.
 */
@Service
public class CustomerExportService {

    static final int MAX_IDS = 50_000;
    static final int MAX_PAGE = 200;
    private static final int QUERY_TIMEOUT_SECONDS = 30;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Logger AUDIT_LOG = LoggerFactory.getLogger(CustomerExportService.class);
    private static final Set<String> DPD_BUCKETS =
            Set.of("UPCOMING", "T0_T7", "T8_T30", "T30_T60", "T60_T90", "T90_PLUS");

    public record FieldDef(String key, String label, String group, String type, boolean sensitive) {
    }

    public record Catalog(List<String> groups, List<FieldDef> fields) {
    }

    public record MatchRequest(LocalDate from, LocalDate to, String segment, List<String> dpd, String grain,
                               Boolean includeIdNumbers) {
    }

    /** {@code count} rows in the chosen grain (capped at 50,000, then {@code truncated}); {@code customers} the matched customers. */
    public record MatchResult(long count, long customers, List<Long> ids, boolean truncated) {
    }

    public record RowsRequest(String grain, List<Long> ids, List<String> fields, Boolean includeIdNumbers) {
    }

    public record Column(String key, String label, String type) {
    }

    public record RowsResult(List<Column> columns, List<List<Object>> rows) {
    }

    private final CustomerBookQuery book;
    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcTemplate plain;
    private final LoanRepository loanRepository;
    private final RepaymentService repayments;
    private final Semaphore gate = new Semaphore(2);

    public CustomerExportService(JdbcTemplate sharedJdbc, CustomerBookQuery book, LoanRepository loanRepository,
                                 RepaymentService repayments) {
        // Our own template so the timeout applies to this export only, not to every query in the app.
        this.plain = new JdbcTemplate();
        this.plain.setDataSource(sharedJdbc.getDataSource());
        this.plain.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        this.jdbc = new NamedParameterJdbcTemplate(plain);
        this.book = book;
        this.loanRepository = loanRepository;
        this.repayments = repayments;
    }

    // ── catalog ──────────────────────────────────────────────────────────────────────────────

    public Catalog catalog() {
        requireAdmin();
        List<FieldDef> defs = CustomerExportFields.all().stream()
                .map(f -> new FieldDef(f.key(), f.label(), f.group(), f.type().name(), f.sensitive())).toList();
        return new Catalog(CustomerExportFields.GROUPS, defs);
    }

    // ── match ────────────────────────────────────────────────────────────────────────────────

    public MatchResult match(MatchRequest r) {
        CurrentActor actor = requireAdmin();
        if (r == null) {
            throw badRequest("INVALID_REQUEST", "Missing request");
        }
        Grain grain = grain(r.grain());
        String segment = r.segment() == null || r.segment().isBlank() ? "all" : r.segment().trim();
        if (!"all".equals(segment) && !CustomerBookQuery.SEGMENTS.contains(segment)) {
            throw badRequest("INVALID_SEGMENT", "Unknown group");
        }
        List<String> dpd = r.dpd() == null ? List.of() : r.dpd().stream().distinct().toList();
        for (String b : dpd) {
            if (!DPD_BUCKETS.contains(b)) {
                throw badRequest("INVALID_DPD", "Unknown days-past-due band");
            }
        }
        if (r.from() != null && r.to() != null && r.from().isAfter(r.to())) {
            throw badRequest("INVALID_DATE", "The start date is after the end date");
        }
        CustomerBookQuery.BookFilter filter = new CustomerBookQuery.BookFilter(null,
                r.from() == null ? null : r.from().atStartOfDay(IST).toInstant(),
                r.to() == null ? null : r.to().plusDays(1).atStartOfDay(IST).toInstant(),
                "all".equals(segment) ? null : segment, null, false, null, LocalDate.now(IST));
        FilterSpec spec = dpd.isEmpty() ? FilterSpec.empty()
                : FilterSpec.of(Map.of("dpd", new ColumnFilter.In(new ArrayList<>(dpd))));

        acquire();
        try {
            List<Long> customers = book.pageIds(filter, 0, MAX_IDS + 1, "customerId", "asc", spec);
            boolean truncated = customers.size() > MAX_IDS;
            long customerCount = truncated ? book.count(filter, spec) : customers.size();
            if (truncated) {
                customers = customers.subList(0, MAX_IDS);
            }
            List<Long> ids = customers;
            if (grain == Grain.APPLICATION) {
                ids = applicationIds(customers);
                if (ids.size() > MAX_IDS) {
                    ids = ids.subList(0, MAX_IDS);
                    truncated = true;
                }
            }
            AUDIT_LOG.info("customer-export match: staff={} group={} dpd={} grain={} count={} idNumbers={}",
                    actor.id(), segment, dpd, grain, ids.size(), Boolean.TRUE.equals(r.includeIdNumbers()));
            return new MatchResult(ids.size(), customerCount, ids, truncated);
        } finally {
            gate.release();
        }
    }

    private List<Long> applicationIds(List<Long> customerIds) {
        if (customerIds.isEmpty()) {
            return List.of();
        }
        // One array parameter: an IN list this long would pass the driver's 32,767 bind-variable limit.
        return plain.query(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "select id from loan_application where customer_id = any (?) order by id limit " + (MAX_IDS + 1));
            ps.setArray(1, con.createArrayOf("bigint", customerIds.toArray()));
            return ps;
        }, (rs, i) -> rs.getLong(1));
    }

    // ── rows ─────────────────────────────────────────────────────────────────────────────────

    public RowsResult rows(RowsRequest r) {
        CurrentActor actor = requireAdmin();
        if (r == null) {
            throw badRequest("INVALID_REQUEST", "Missing request");
        }
        Grain grain = grain(r.grain());
        if (r.ids() == null || r.ids().isEmpty()) {
            return new RowsResult(List.of(), List.of());
        }
        if (r.ids().size() > MAX_PAGE) {
            throw badRequest("TOO_MANY_IDS", "At most " + MAX_PAGE + " ids per request");
        }
        List<Long> ids = r.ids().stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (r.fields() == null || r.fields().isEmpty()) {
            throw badRequest("NO_FIELDS", "Choose at least one field");
        }
        // Every key must be on the allowlist; only the allowlist's own SQL is ever used.
        List<Field> fields = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String key : r.fields()) {
            Field f = key == null ? null : CustomerExportFields.byKey(key);
            if (f == null) {
                throw badRequest("UNKNOWN_FIELD", "Unknown field in request");
            }
            if (seen.add(key)) {
                fields.add(f);
            }
        }
        boolean idNumbers = Boolean.TRUE.equals(r.includeIdNumbers());
        long sensitive = fields.stream().filter(Field::sensitive).count();
        if (sensitive > 0 && !idNumbers) {
            throw badRequest("ID_NUMBERS_NOT_CONFIRMED", "ID number fields need the include-ID-numbers confirmation");
        }
        if (sensitive > 0) {
            AUDIT_LOG.info("customer-export id-numbers: staff={} grain={} rows={} idFields={}",
                    actor.id(), grain, ids.size(), sensitive);
        }

        acquire();
        try {
            return run(grain, ids, fields);
        } finally {
            gate.release();
        }
    }

    static String buildSql(Grain grain, List<Field> fields) {
        Set<String> joins = new LinkedHashSet<>();
        boolean live = false;
        StringBuilder select = new StringBuilder("select ");
        for (int i = 0; i < fields.size(); i++) {
            Field f = fields.get(i);
            joins.addAll(f.joins());
            live |= f.live() != Live.NONE;
            select.append(i == 0 ? "" : ", ").append(f.sql()).append(" as c").append(i);
        }
        if (live) {
            joins.add("l");
            select.append(", l.id as loan_pk");
        }
        String from;
        String tail;
        if (grain == Grain.CUSTOMER) {
            from = " from loan_application a join (select max(x.id) as mid from loan_application x"
                    + " where x.customer_id in (:ids) group by x.customer_id) mx on mx.mid = a.id";
            tail = " order by a.customer_id";
        } else {
            from = " from loan_application a";
            tail = " where a.id in (:ids) order by a.id";
        }
        return select + from + CustomerExportFields.joinSql(joins, grain) + tail;
    }

    private RowsResult run(Grain grain, List<Long> ids, List<Field> fields) {
        String sql = buildSql(grain, fields);
        boolean live = fields.stream().anyMatch(f -> f.live() != Live.NONE);
        int n = fields.size();
        List<Long> loanIds = new ArrayList<>();
        List<List<Object>> rows = jdbc.query(sql, new MapSqlParameterSource("ids", ids), (rs, i) -> {
            List<Object> row = new ArrayList<>(n);
            for (int c = 1; c <= n; c++) {
                row.add(normalize(rs.getObject(c)));
            }
            if (live) {
                long loanId = rs.getLong(n + 1);
                loanIds.add(rs.wasNull() ? null : loanId);
            }
            return row;
        });
        if (live) {
            fillLive(fields, rows, loanIds);
        }
        List<Column> cols = fields.stream().map(f -> new Column(f.key(), f.label(), f.type().name())).toList();
        return new RowsResult(cols, rows);
    }

    /** The live balance and DPD number, priced by RepaymentService in one batch for the page. */
    private void fillLive(List<Field> fields, List<List<Object>> rows, List<Long> loanIds) {
        List<Long> distinct = loanIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, Loan> loans = new HashMap<>();
        Map<Long, OutstandingBreakdown> priced = Map.of();
        LocalDate today = LocalDate.now(IST);
        if (!distinct.isEmpty()) {
            List<Loan> found = loanRepository.findAllById(distinct);
            found.forEach(l -> loans.put(l.getId(), l));
            priced = repayments.outstandingBreakdownsForAll(found, today);
        }
        for (int r = 0; r < rows.size(); r++) {
            Long loanId = loanIds.get(r);
            Loan loan = loanId == null ? null : loans.get(loanId);
            OutstandingBreakdown b = loanId == null ? null : priced.get(loanId);
            for (int c = 0; c < fields.size(); c++) {
                Live kind = fields.get(c).live();
                if (kind == Live.OUTSTANDING) {
                    rows.get(r).set(c, b == null ? null : b.outstandingPaise());
                } else if (kind == Live.DPD_DAYS) {
                    rows.get(r).set(c, loan == null || b == null ? null : dpd(loan, b.outstandingPaise(), today));
                }
            }
        }
    }

    /** Same rule as the reports centre: only an open loan with money still owed and a due date is past due. */
    static long dpd(Loan loan, long outstandingPaise, LocalDate today) {
        boolean open = loan.getClosedOn() == null && loan.getStatus() != LoanStatus.CLOSED
                && loan.getStatus() != LoanStatus.REPAID;
        if (!open || outstandingPaise <= 0 || loan.getDueDate() == null) {
            return 0;
        }
        return Math.max(0, ChronoUnit.DAYS.between(loan.getDueDate(), today));
    }

    static Object normalize(Object v) {
        if (v == null || v instanceof Boolean || v instanceof String) {
            return v;
        }
        if (v instanceof Long || v instanceof Integer || v instanceof Short) {
            return ((Number) v).longValue();
        }
        if (v instanceof BigDecimal bd) {
            return bd.stripTrailingZeros().scale() <= 0 ? (Object) bd.longValue() : (Object) bd.doubleValue();
        }
        if (v instanceof Number num) {
            return num.doubleValue();
        }
        return v.toString();
    }

    // ── plumbing ─────────────────────────────────────────────────────────────────────────────

    private void acquire() {
        try {
            if (!gate.tryAcquire(2, TimeUnit.SECONDS)) {
                throw new CustomerExportException(503, "EXPORT_BUSY", "Another export is running. Retrying shortly.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CustomerExportException(503, "EXPORT_BUSY", "Another export is running. Retrying shortly.");
        }
    }

    private static Grain grain(String s) {
        if ("customer".equalsIgnoreCase(s)) {
            return Grain.CUSTOMER;
        }
        if ("application".equalsIgnoreCase(s)) {
            return Grain.APPLICATION;
        }
        throw badRequest("INVALID_GRAIN", "Grain must be customer or application");
    }

    private static CustomerExportException badRequest(String code, String message) {
        return new CustomerExportException(400, code, message);
    }

    /** Same rule as the reports centre: the real role must be ADMIN (the working role does not count; DSA and all others are refused). */
    private static CurrentActor requireAdmin() {
        CurrentActor actor = ActorContext.get();
        if (actor == null || !"ADMIN".equals(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "The customer export is available to ADMIN only");
        }
        return actor;
    }
}
