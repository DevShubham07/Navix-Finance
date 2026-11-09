package com.navix.common.query;

import com.navix.common.query.ColumnFilter.Kind;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The allow-list, and the security boundary of column filtering: the ONLY way a column name reaches
 * SQL is {@link #get(String)} returning a {@link Def} whose {@code sql} was written in source code —
 * the same idea as {@code CustomerBookQuery.SORTS}, where a request only ever picks a key.
 *
 * <p>Why it is hard to misuse rather than merely documented: the request-derived string never
 * leaves this lookup — {@link FilterSpec} stores keys, {@link SqlFilter} names bind parameters by
 * position ({@code cf0}, {@code cf1}…), and the registry is immutable once built, so there is no
 * call that accepts a request string and returns SQL text. The builder also refuses a key or an
 * expression that does not look like a hand-written constant, which catches a request string
 * being fed in by mistake — a backstop only. The real guarantee is that callers pass string
 * literals; Java cannot enforce that, so declare the registry in a {@code static final}.
 *
 * <p>A text column is matched here against the SQL expression, while the client matches the
 * on-screen label. Register a text column on a label-equivalent expression, or the same filter
 * narrows differently depending on which side evaluates it. (An {@code in} list compares
 * {@code cast(expr as text)} so one wire format serves enum and bigint columns alike.)
 */
public final class FilterColumns {

    /** Hand-written keys only: letters, digits, underscore. */
    private static final Pattern KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
    /**
     * An expression is code, not data: no statement separators or comments. Cheap tripwire, not the
     * defence — the defence is that nothing request-derived is ever passed to {@link Builder#column}.
     */
    private static final Pattern FORBIDDEN_SQL = Pattern.compile(";|--|/\\*");

    /** One allowed column. Constructor is private: instances exist only via {@link Builder}. */
    public static final class Def {
        private final String sql;
        private final Set<Kind> kinds;
        private final long displayScale;

        private Def(String sql, Set<Kind> kinds, long displayScale) {
            this.sql = sql;
            this.kinds = kinds;
            this.displayScale = displayScale;
        }

        /**
         * Multiplier applied to a {@code Num} bound to get the STORED unit: display -> stored, so
         * rupees -> paise is 100 (a bound of 10000 binds 1000000). Never the reverse. 1 for counts
         * and day-counts. The scaling happens on the bound operand, so the column stays bare and
         * indexable.
         */
        public long displayScale() {
            return displayScale;
        }

        /** Compile-time-constant SQL expression, e.g. {@code "b.status"}. */
        public String sql() {
            return sql;
        }

        public boolean accepts(Kind kind) {
            return kinds.contains(kind);
        }
    }

    private final Map<String, Def> defs;

    private FilterColumns(Map<String, Def> defs) {
        this.defs = Map.copyOf(defs);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** {@code null} for any key the register did not declare — callers drop such filters. */
    public Def get(String key) {
        return key == null ? null : defs.get(key);
    }

    public static final class Builder {
        private final Map<String, Def> defs = new LinkedHashMap<>();

        private Builder() {
        }

        /** @param sql a string literal / constant in source — never anything built from a request */
        public Builder column(String key, String sql, Kind first, Kind... rest) {
            return scaled(key, sql, 1, first, rest);
        }

        /** A money column stored in paise: {@code scaled("amount", "b.amount_paise", 100, NUM)}. */
        public Builder scaled(String key, String sql, long displayScale, Kind first, Kind... rest) {
            if (displayScale < 1) {
                throw new IllegalArgumentException("bad display scale for " + key);
            }
            if (!KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("bad filter column key: " + key);
            }
            if (sql == null || sql.isBlank() || FORBIDDEN_SQL.matcher(sql).find()) {
                throw new IllegalArgumentException("bad filter column sql for " + key);
            }
            if (defs.put(key, new Def(sql, EnumSet.of(first, rest), displayScale)) != null) {
                throw new IllegalArgumentException("duplicate filter column " + key);
            }
            return this;
        }

        public FilterColumns build() {
            return new FilterColumns(defs);
        }
    }
}
