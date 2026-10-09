package com.navix.app.dashboard;

import com.navix.app.dashboard.MetricSql.Metric;
import com.navix.common.exception.BusinessException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Runs {@link Metric} fragments. Aggregates, grouped aggregates and the drill-down records list all
 * go through {@link MetricSql#query}, so they are built from the very same fragment.
 */
@Component
public class MetricQueries {

    /** Count and money of a metric, split Fresh / Re-loan. */
    public record Agg(long count, long fresh, long reloan, long amount, long freshAmount, long reloanAmount) {
        public static final Agg ZERO = new Agg(0, 0, 0, 0, 0, 0);
    }

    /** A (group, loan) pair -- for metrics whose money comes from RepaymentService rather than SQL. */
    public record GroupLoan(String grp, long loanId) {
    }

    public record RowKey(long applicationId, Long loanId, String segment, Long amount, String extra) {
    }

    public record Page(List<RowKey> rows, long total, long fresh, long reloan, long sum, List<Long> loanIds) {
    }

    /** What the drawer shows for an application beyond the metric's own columns. */
    public record AppDetail(long applicationId, Long customerId, String status, Long assigneeId,
                            String fullName, String mobile, String state) {
    }

    private static final String AGG_COLUMNS = "count(*) as n,"
            + " count(*) filter (where f.segment = 'FRESH') as fresh,"
            + " count(*) filter (where f.segment = 'RELOAN') as reloan,"
            + " coalesce(sum(f.amount), 0) as amt,"
            + " coalesce(sum(f.amount) filter (where f.segment = 'FRESH'), 0) as fresh_amt,"
            + " coalesce(sum(f.amount) filter (where f.segment = 'RELOAN'), 0) as reloan_amt";

    private final NamedParameterJdbcTemplate jdbc;

    public MetricQueries(JdbcTemplate jdbcTemplate) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    public NamedParameterJdbcTemplate jdbc() {
        return jdbc;
    }

    /** The bind parameters every fragment may reference. A key narrows the period for day/month keys. */
    public MapSqlParameterSource params(Metric metric, DashboardScope scope, LocalDate from, LocalDate to,
                                        String key, String groupBy) {
        LocalDate f = from;
        LocalDate t = to;
        String k = key == null || key.isBlank() ? null : key.trim();
        if (k != null) {
            try {
                switch (metric.keyKind()) {
                    case DAY -> f = t = LocalDate.parse(k);
                    case MONTH -> {
                        YearMonth ym = YearMonth.parse(k);
                        f = ym.atDay(1);
                        t = ym.atEndOfMonth();
                    }
                    case GROUP_DAY -> f = t = LocalDate.parse(k.substring(k.lastIndexOf(':') + 1));
                    default -> { /* the key only filters the group */ }
                }
                if (metric == Metric.COMPANY) {
                    k = k.toLowerCase(Locale.ROOT); // the API shows title case; the group key is lower case
                }
                if (metric == Metric.PF_RATE || metric == Metric.ROI_RATE) {
                    k = new BigDecimal(k).setScale(2, java.math.RoundingMode.HALF_UP)
                            .stripTrailingZeros().toPlainString();
                }
            } catch (DateTimeParseException | NumberFormatException | StringIndexOutOfBoundsException e) {
                throw new BusinessException("INVALID_KEY", "Unrecognised record key for " + metric);
            }
        }
        String gb = groupBy;
        if (metric == Metric.COLLECTION_GROUP && gb == null && k != null) {
            gb = k.startsWith("STATE:") ? "STATE" : "EXEC";
        }
        return new MapSqlParameterSource()
                .addValue("from", f)
                .addValue("to", t)
                .addValue("key", k)
                .addValue("groupBy", gb)
                .addValue("allStaff", scope.allStaff())
                .addValue("ids", scope.sqlIds());
    }

    public Agg aggregate(Metric m, DashboardScope s, LocalDate from, LocalDate to, String key) {
        String sql = MetricSql.query(m, s, "select " + AGG_COLUMNS + " from {F}");
        return jdbc.queryForObject(sql, params(m, s, from, to, key, null), (rs, i) -> agg(rs));
    }

    /** Aggregate per {@code grp}, ordered by group. */
    public Map<String, Agg> aggregateByGrp(Metric m, DashboardScope s, LocalDate from, LocalDate to) {
        String sql = MetricSql.query(m, s,
                "select f.grp, " + AGG_COLUMNS + " from {F} where f.grp is not null group by f.grp order by f.grp");
        Map<String, Agg> out = new LinkedHashMap<>();
        jdbc.query(sql, params(m, s, from, to, null, null), rs -> {
            out.put(rs.getString("grp"), agg(rs));
        });
        return out;
    }

    /** Every (group, loan) the metric matches. */
    public List<GroupLoan> groupLoans(Metric m, DashboardScope s, LocalDate from, LocalDate to, String key) {
        String sql = MetricSql.query(m, s, "select f.grp, f.loan_id from {F} where f.loan_id is not null");
        return jdbc.query(sql, params(m, s, from, to, key, null),
                (rs, i) -> new GroupLoan(rs.getString("grp"), rs.getLong("loan_id")));
    }

    /**
     * One page of a metric's rows plus the whole-set counts. {@code fresh}/{@code reloan} ignore the
     * segment filter (they label the pills); {@code total}/{@code sum} respect it.
     */
    public Page records(Metric m, DashboardScope s, LocalDate from, LocalDate to, String key, String segment,
                        String q, int page, int size) {
        String seg = segment == null ? "ALL" : segment.trim().toUpperCase(Locale.ROOT);
        if (!List.of("ALL", "FRESH", "RELOAN").contains(seg)) {
            throw new BusinessException("INVALID_SEGMENT", "segment must be ALL, FRESH or RELOAN");
        }
        MapSqlParameterSource p = params(m, s, from, to, key, null);
        String needle = q == null ? "" : q.trim();
        p.addValue("segment", seg);
        p.addValue("q", needle.isEmpty() ? null : needle);
        p.addValue("qLike", needle.isEmpty() ? null : "%" + escapeLike(needle.toLowerCase(Locale.ROOT)) + "%");
        String filter = " left join customer_profile qp on qp.application_id = f.application_id"
                + " where (cast(:qLike as text) is null or lower(coalesce(qp.full_name, '')) like :qLike escape '!'"
                + " or cast(f.application_id as text) = :q)";

        String countSql = MetricSql.query(m, s, "select count(*) filter (where f.segment = 'FRESH') as fresh,"
                + " count(*) filter (where f.segment = 'RELOAN') as reloan,"
                + " count(*) filter (where :segment = 'ALL' or f.segment = :segment) as total,"
                + " coalesce(sum(f.amount) filter (where :segment = 'ALL' or f.segment = :segment), 0) as sum_amt"
                + " from {F}" + filter);
        long[] c = jdbc.queryForObject(countSql, p, (rs, i) -> new long[] {
                rs.getLong("fresh"), rs.getLong("reloan"), rs.getLong("total"), rs.getLong("sum_amt")});

        p.addValue("size", size);
        p.addValue("offset", (long) page * size);
        String pageSql = MetricSql.query(m, s, "select f.application_id, f.loan_id, f.segment, f.amount, f.extra"
                + " from {F}" + filter + " and (:segment = 'ALL' or f.segment = :segment)"
                + " order by f.application_id desc, f.loan_id desc nulls last limit :size offset :offset");
        List<RowKey> rows = jdbc.query(pageSql, p, (rs, i) -> new RowKey(rs.getLong("application_id"),
                (Long) rs.getObject("loan_id"), rs.getString("segment"), (Long) rs.getObject("amount"),
                rs.getString("extra")));

        List<Long> loanIds = List.of();
        if (m.owedSum()) {
            String idsSql = MetricSql.query(m, s, "select f.loan_id from {F}" + filter
                    + " and (:segment = 'ALL' or f.segment = :segment) and f.loan_id is not null");
            loanIds = jdbc.queryForList(idsSql, p, Long.class);
        }
        return new Page(rows, c[2], c[0], c[1], c[3], loanIds);
    }

    /** Names, status, masked-phone source and state for the given applications. */
    public Map<Long, AppDetail> details(List<Long> applicationIds) {
        if (applicationIds.isEmpty()) return Map.of();
        String sql = "select a.id, a.customer_id, a.status, a.assigned_executive_id, cp.full_name, cp.mobile,"
                + " " + MetricSql.STATE_SQL + " as state"
                + " from loan_application a"
                + " left join customer_profile cp on cp.application_id = a.id"
                + " left join application_verification va on va.application_id = a.id and va.check_type = 'ADDRESS'"
                + " left join application_verification vb on vb.application_id = a.id and vb.check_type = 'AADHAAR'"
                + " left join application_verification vc on vc.application_id = a.id and vc.check_type = 'PAN'"
                + " where a.id in (:ids)";
        Map<Long, AppDetail> out = new LinkedHashMap<>();
        jdbc.query(sql, new MapSqlParameterSource("ids", applicationIds), rs -> {
            out.put(rs.getLong("id"), new AppDetail(rs.getLong("id"), (Long) rs.getObject("customer_id"),
                    rs.getString("status"), (Long) rs.getObject("assigned_executive_id"),
                    rs.getString("full_name"), rs.getString("mobile"), rs.getString("state")));
        });
        return out;
    }

    private static Agg agg(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Agg(rs.getLong("n"), rs.getLong("fresh"), rs.getLong("reloan"), rs.getLong("amt"),
                rs.getLong("fresh_amt"), rs.getLong("reloan_amt"));
    }

    static String escapeLike(String s) {
        return s.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    /** The previous window of equal length, immediately before {@code from}. */
    public static LocalDate[] previousWindow(LocalDate from, LocalDate to) {
        long days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        LocalDate prevTo = from.minusDays(1);
        return new LocalDate[] {prevTo.minusDays(days - 1), prevTo};
    }
}
