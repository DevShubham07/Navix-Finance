package com.navix.common.query;

import com.navix.common.exception.BusinessException;
import com.navix.common.query.ColumnFilter.Kind;
import com.navix.common.util.LikeEscape;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * Emits the SQL for a {@link FilterSpec}. Column SQL comes only from {@link FilterColumns}; every
 * operand is bound as a named parameter ({@code cf0}, {@code cf1}…, numbered, never derived from the
 * request), so nothing user-supplied is ever concatenated.
 */
public final class SqlFilter {

    /** The backend convention: a "day" is an IST business day (see CustomerBookQuery's from/to). */
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** One checklist of this size is already a UI bug; refuse rather than build a huge IN. */
    public static final int MAX_IN_VALUES = 500;

    private SqlFilter() {
    }

    /**
     * Returns {@code " and (…)"} or {@code ""}. The caller appends this AFTER its own scope clauses,
     * so a filter can only ever narrow what a staffer may already see — never widen it.
     *
     * @throws BusinessException 422 {@code FILTER_TOO_MANY_VALUES} for an {@code in} over 500 values
     *     (the one non-lenient case)
     */
    public static String and(FilterSpec spec, FilterColumns columns, MapSqlParameterSource params) {
        List<String> clauses = new ArrayList<>();
        int n = 0;
        for (Map.Entry<String, ColumnFilter> e : spec.filters().entrySet()) {
            FilterColumns.Def def = columns.get(e.getKey());
            ColumnFilter f = e.getValue();
            if (def == null || !def.accepts(f.kind())) {
                continue; // not in the allow-list: emit nothing, bind nothing
            }
            String expr = "(" + def.sql() + ")";
            String p = "cf" + n++;
            String clause = switch (f) {
                case ColumnFilter.In in -> in(expr, in, p, params);
                case ColumnFilter.Num num -> num(expr, def.displayScale(), num, p, params);
                case ColumnFilter.Day day -> day(expr, day, p, params);
                case ColumnFilter.Text text -> text(expr, text, p, params);
            };
            if (clause != null) {
                clauses.add("(" + clause + ")");
            }
        }
        return clauses.isEmpty() ? "" : " and (" + String.join(" and ", clauses) + ")";
    }

    private static String in(String expr, ColumnFilter.In in, String p, MapSqlParameterSource params) {
        if (in.values().size() > MAX_IN_VALUES) {
            throw new BusinessException("FILTER_TOO_MANY_VALUES",
                    "A filter can list at most " + MAX_IN_VALUES + " values");
        }
        if (in.values().isEmpty()) {
            return "false"; // "match none of these" is not "match everything"
        }
        boolean blank = in.values().contains(ColumnFilter.BLANK);
        List<String> rest = in.values().stream().filter(v -> !ColumnFilter.BLANK.equals(v)).toList();
        List<String> parts = new ArrayList<>();
        if (blank) {
            parts.add(expr + " is null");
        }
        if (!rest.isEmpty()) {
            params.addValue(p, rest);
            // cast: values arrive as text, the column may be an enum/bigint, and Postgres will not
            // compare those to a varchar parameter.
            parts.add("cast(" + expr + " as text) in (:" + p + ")");
        }
        return String.join(" or ", parts);
    }

    /**
     * Bounds arrive in display units and are converted to the stored unit (display x scale, e.g.
     * rupees x 100 = paise) on the OPERAND, rounded HALF_UP to a whole stored unit and bound as a
     * long, so the column is compared bare and its index stays usable. Scale 1 binds as given.
     */
    private static String num(String expr, long scale, ColumnFilter.Num num, String p, MapSqlParameterSource params) {
        List<String> parts = new ArrayList<>();
        BigDecimal min = num.min();
        BigDecimal max = num.max();
        if (min != null) {
            params.addValue(p + "min", stored(min, scale));
            parts.add(expr + " >= :" + p + "min");
        }
        if (max != null) {
            params.addValue(p + "max", stored(max, scale));
            parts.add(expr + " <= :" + p + "max");
        }
        return parts.isEmpty() ? null : String.join(" and ", parts);
    }

    /**
     * A bound in display units as the stored unit, saturating rather than throwing.
     *
     * `FilterSpec` already caps a bound at 18 integer digits, but multiplying by a scale can still
     * carry it past `Long.MAX_VALUE` — 17 nines of rupees is under the cap and over a long once it
     * becomes paise. `longValueExact()` threw an `ArithmeticException` there, which surfaced as a
     * 500 on a request this parser is supposed to handle leniently.
     *
     * Clamping is also the semantically right answer for a RANGE bound: "at least 10^20 paise" and
     * "at least Long.MAX_VALUE paise" select exactly the same rows — none — so saturating gives the
     * reader the honest empty register instead of an error page.
     */
    private static Object stored(BigDecimal displayValue, long scale) {
        if (scale == 1) {
            return displayValue;
        }
        BigDecimal scaled = displayValue.multiply(BigDecimal.valueOf(scale))
                .setScale(0, java.math.RoundingMode.HALF_UP);
        if (scaled.compareTo(MAX_STORED) > 0) {
            return Long.MAX_VALUE;
        }
        if (scaled.compareTo(MIN_STORED) < 0) {
            return Long.MIN_VALUE;
        }
        return scaled.longValueExact();
    }

    private static final BigDecimal MAX_STORED = BigDecimal.valueOf(Long.MAX_VALUE);
    private static final BigDecimal MIN_STORED = BigDecimal.valueOf(Long.MIN_VALUE);

    private static String day(String expr, ColumnFilter.Day day, String p, MapSqlParameterSource params) {
        List<String> parts = new ArrayList<>();
        LocalDate from = day.from();
        LocalDate to = day.to();
        if (from != null) {
            params.addValue(p + "from", Timestamp.from(from.atStartOfDay(IST).toInstant()));
            parts.add(expr + " >= :" + p + "from");
        }
        if (to != null) {
            // Exclusive next-midnight, so a one-day range means that whole day.
            params.addValue(p + "to", Timestamp.from(to.plusDays(1).atStartOfDay(IST).toInstant()));
            parts.add(expr + " < :" + p + "to");
        }
        return parts.isEmpty() ? null : String.join(" and ", parts);
    }

    private static String text(String expr, ColumnFilter.Text text, String p, MapSqlParameterSource params) {
        String term = text.term().strip().toLowerCase(Locale.ROOT);
        if (term.isEmpty()) {
            return null;
        }
        String col = "lower(cast(" + expr + " as text))";
        switch (text.op()) {
            case EQ:
                params.addValue(p, term);
                return col + " = :" + p;
            case STARTS:
                params.addValue(p, LikeEscape.escapeLike(term) + "%");
                return col + " like :" + p + " escape '\\'";
            default:
                params.addValue(p, "%" + LikeEscape.escapeLike(term) + "%");
                return col + " like :" + p + " escape '\\'";
        }
    }
}
