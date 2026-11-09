package com.navix.common.query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * A parsed set of column filters, keyed by column. Parses exactly what the frontend's
 * {@code column-filter-params.ts} emits (that file is the spec; keep them in step).
 *
 * <p>Parsing is lenient by design: an unknown column, a column that does not accept that kind, an
 * unparseable bound or a malformed day is dropped, never an error — a shared link must not blank a
 * page because a deploy renamed a column. (The one hard failure, the 500-value cap, lives in
 * {@link SqlFilter}.) Keys are held sorted so the emitted SQL and parameter names are deterministic.
 */
public final class FilterSpec {

    public static final String PREFIX = "f.";
    private static final Pattern DAY_SHAPE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private static final FilterSpec EMPTY = new FilterSpec(Map.of());

    private final Map<String, ColumnFilter> filters;

    private FilterSpec(Map<String, ColumnFilter> filters) {
        this.filters = Collections.unmodifiableMap(new TreeMap<>(filters));
    }

    public static FilterSpec empty() {
        return EMPTY;
    }

    public static FilterSpec of(Map<String, ColumnFilter> filters) {
        return new FilterSpec(filters);
    }

    public static FilterSpec parse(Map<String, String> params, FilterColumns columns) {
        Map<String, ColumnFilter> out = new TreeMap<>();
        if (params == null) {
            return EMPTY;
        }
        for (Map.Entry<String, String> e : params.entrySet()) {
            String name = e.getKey();
            if (name == null || !name.startsWith(PREFIX) || e.getValue() == null) {
                continue;
            }
            String key = name.substring(PREFIX.length());
            FilterColumns.Def def = columns.get(key);
            if (def == null) {
                continue;
            }
            ColumnFilter f = parseValue(e.getValue());
            if (f != null && def.accepts(f.kind())) {
                out.put(key, f);
            }
        }
        return out.isEmpty() ? EMPTY : new FilterSpec(out);
    }

    /** The facet query for a column must ignore that column's own filter, or its dropdown collapses. */
    public FilterSpec without(String column) {
        if (!filters.containsKey(column)) {
            return this;
        }
        Map<String, ColumnFilter> copy = new TreeMap<>(filters);
        copy.remove(column);
        return copy.isEmpty() ? EMPTY : new FilterSpec(copy);
    }

    public boolean isEmpty() {
        return filters.isEmpty();
    }

    /** Sorted by column key. */
    public Map<String, ColumnFilter> filters() {
        return filters;
    }

    public ColumnFilter get(String column) {
        return filters.get(column);
    }

    // ── single value ────────────────────────────────────────────────────────────────

    /** Mirror of {@code parseFilterValue}; null = dropped. */
    static ColumnFilter parseValue(String raw) {
        String value = raw.strip();
        if (value.isEmpty()) {
            return null;
        }
        int colon = value.indexOf(':');
        String tag = colon == -1 ? "" : value.substring(0, colon);
        String body = colon == -1 ? value : value.substring(colon + 1);

        switch (tag) {
            case "in":
                return in(body);
            case "num": {
                String[] p = bounds(body);
                return num(p[0], p[1]);
            }
            case "date": {
                String[] p = bounds(body);
                return day(day(p[0]), day(p[1]));
            }
            case "text": {
                int opEnd = body.indexOf(':');
                String op = opEnd == -1 ? "" : body.substring(0, opEnd);
                // First colon only, so `text:contains:a:b` searches for "a:b".
                String q = opEnd == -1 ? body : body.substring(opEnd + 1);
                ColumnFilter.TextOp textOp = switch (op) {
                    case "contains" -> ColumnFilter.TextOp.CONTAINS;
                    case "starts" -> ColumnFilter.TextOp.STARTS;
                    case "eq" -> ColumnFilter.TextOp.EQ;
                    default -> null;
                };
                return textOp == null || q.isBlank() ? null : new ColumnFilter.Text(textOp, q);
            }
            default:
                break;
        }

        // Untagged legacy / hand-written forms: read-lenient, the client writes canonical.
        if (value.contains("..")) {
            String[] p = bounds(value);
            LocalDate from = day(p[0]);
            LocalDate to = day(p[1]);
            if (from != null || to != null) {
                return day(from, to);
            }
            return num(p[0], p[1]);
        }
        return in(value);
    }

    /** JS {@code body.split("..")} then take the first two parts. */
    private static String[] bounds(String body) {
        String[] parts = body.split("\\.\\.", -1);
        return new String[] {parts.length > 0 ? parts[0] : "", parts.length > 1 ? parts[1] : ""};
    }

    private static ColumnFilter in(String body) {
        List<String> values = new ArrayList<>();
        for (String s : body.split(",")) {
            if (!s.isEmpty()) {
                values.add(unesc(s));
            }
        }
        return values.isEmpty() ? null : new ColumnFilter.In(values);
    }

    /** `%2C` before `%25`, the reverse of encoding, or a literal `%2C` the user typed turns into a comma. */
    static String unesc(String v) {
        return v.replaceAll("(?i)%2C", ",").replaceAll("(?i)%25", "%");
    }

    private static ColumnFilter num(String lo, String hi) {
        BigDecimal min = decimal(lo);
        BigDecimal max = decimal(hi);
        return min == null && max == null ? null : new ColumnFilter.Num(min, max);
    }

    /** Integer digits a numeric bound may carry. 18 keeps the scaled operand inside a long. */
    private static final int MAX_INTEGER_DIGITS = 18;

    /** Fraction digits a numeric bound may carry; also catches `1e-2000000000`. */
    private static final int MAX_FRACTION_DIGITS = 10;

    private static BigDecimal decimal(String v) {
        String t = v.strip();
        if (t.isEmpty()) {
            return null;
        }
        try {
            BigDecimal bd = new BigDecimal(t);
            // Reject absurd magnitudes BEFORE anything tries to expand them.
            //
            // `new BigDecimal("1e2000000000")` parses fine — it is unscaled 1 with scale
            // -2000000000 — and costs nothing until something calls `setScale`, which then has to
            // materialise two billion digits. `SqlFilter.stored` does exactly that to convert a
            // display bound into the stored unit, so a request of `f.amount=num:1e2000000000..`
            // would burn the heap on one URL. Both bounds here are O(1) on the parsed value: a
            // short string with a huge exponent keeps a small unscaled value, so `precision()`
            // stays cheap.
            //
            // `precision() - scale()` is the count of integer digits; `abs(scale)` catches the
            // mirror case of a vanishing value (`1e-2000000000`), whose integer-digit count goes
            // negative and would otherwise slip past. Anything beyond these is not a bound anyone
            // means — no money column holds 10^18 of anything — so it is dropped, which is what
            // this parser does with every other unusable value.
            if (bd.precision() - bd.scale() > MAX_INTEGER_DIGITS || Math.abs(bd.scale()) > MAX_FRACTION_DIGITS) {
                return null;
            }
            return bd;
        } catch (NumberFormatException e) {
            return null; // lenient: a bad bound is dropped, not fatal
        }
    }

    private static ColumnFilter day(LocalDate from, LocalDate to) {
        return from == null && to == null ? null : new ColumnFilter.Day(from, to);
    }

    /** A real calendar day; {@code 2026-13-99} matches the shape and is not one. */
    private static LocalDate day(String v) {
        String t = v.strip();
        if (!DAY_SHAPE.matcher(t).matches()) {
            return null;
        }
        try {
            return LocalDate.parse(t); // ISO_LOCAL_DATE is strict: rejects month 13 and Feb 30
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
