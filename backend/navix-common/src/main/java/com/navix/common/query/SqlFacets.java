package com.navix.common.query;

import com.navix.common.query.ColumnFilter.Kind;
import com.navix.common.util.LikeEscape;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The distinct values a faceted column can take, so its dropdown offers real choices.
 *
 * <p>It runs INSIDE the caller's scope: list scoping here is role-dependent and
 * {@code CustomerService} deliberately avoids being an enumeration oracle, so a bare
 * {@code select distinct} would show a scoped staffer values from outside their own book.
 * {@code scopeWhere} is therefore mandatory input, and the other columns' filters narrow the facet
 * too (the column's own filter is dropped via {@link FilterSpec#without}, so you can still switch).
 */
public final class SqlFacets {

    /** Past this the UI degrades the column to a text filter. */
    public static final int MAX_VALUES = 500;

    /** Raw stored values (a missing value is {@link ColumnFilter#BLANK}); the client labels them. */
    public record Facet(List<String> values, boolean truncated,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
            java.util.Map<String, String> labels) {

        public Facet(List<String> values, boolean truncated) {
            this(values, truncated, java.util.Map.of());
        }

        /** Raw value to display text, for columns keyed by an id (an officer, an owner). */
        public Facet withLabels(java.util.Map<String, String> labels) {
            return new Facet(values, truncated, labels == null ? java.util.Map.of() : labels);
        }
    }

    private SqlFacets() {
    }

    /**
     * @param prefix optional leading text such as a CTE ({@code "with … "}); caller-owned constant
     * @param from the FROM source, e.g. {@code "book b"}; caller-owned constant
     * @param scopeWhere the caller's own scope/base predicate ({@code null}/blank = none); its
     *     operands must already be bound in {@code params}
     * @param column a request-derived key — resolved only through {@code columns}
     */
    public static Facet facet(NamedParameterJdbcTemplate jdbc, String prefix, String from, String scopeWhere,
            FilterSpec spec, FilterColumns columns, String column, MapSqlParameterSource params) {
        return facet(jdbc, prefix, from, scopeWhere, spec, columns, column, null, params);
    }

    /**
     * As above, narrowed to values containing {@code search} (case-insensitive, bound as a parameter),
     * so a list past {@link #MAX_VALUES} can still be looked up on the server.
     */
    public static Facet facet(NamedParameterJdbcTemplate jdbc, String prefix, String from, String scopeWhere,
            FilterSpec spec, FilterColumns columns, String column, String search, MapSqlParameterSource params) {
        String sql = sql(prefix, from, scopeWhere, spec, columns, column, search, params);
        if (sql == null) {
            return new Facet(List.of(), false);
        }
        List<String> rows = new ArrayList<>(jdbc.query(sql, params, (rs, i) -> rs.getString("v")));
        boolean truncated = rows.size() > MAX_VALUES;
        List<String> values = new ArrayList<>();
        for (String v : rows.subList(0, Math.min(rows.size(), MAX_VALUES))) {
            values.add(v == null ? ColumnFilter.BLANK : v);
        }
        return new Facet(values, truncated);
    }

    /** The statement alone, for tests; {@code null} when the column is not a facetable one. */
    static String sql(String prefix, String from, String scopeWhere, FilterSpec spec, FilterColumns columns,
            String column, MapSqlParameterSource params) {
        return sql(prefix, from, scopeWhere, spec, columns, column, null, params);
    }

    /** A blank search is no search; an over-long one is cut, so a lookup cannot become a payload. */
    public static String normaliseSearch(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        String t = search.strip();
        return t.length() > MAX_SEARCH ? t.substring(0, MAX_SEARCH) : t;
    }

    static final int MAX_SEARCH = 100;

    static String sql(String prefix, String from, String scopeWhere, FilterSpec spec, FilterColumns columns,
            String column, String search, MapSqlParameterSource params) {
        FilterColumns.Def def = columns.get(column);
        if (def == null || !def.accepts(Kind.IN)) {
            return null;
        }
        String scope = scopeWhere == null || scopeWhere.isBlank() ? "true" : scopeWhere;
        String needle = normaliseSearch(search);
        String like = "";
        if (needle != null) {
            params.addValue("facetSearch", "%" + LikeEscape.escapeLike(needle.toLowerCase(java.util.Locale.ROOT)) + "%");
            like = " and lower(cast((" + def.sql() + ") as text)) like :facetSearch escape '\\'";
        }
        return (prefix == null ? "" : prefix)
                + "select distinct cast((" + def.sql() + ") as text) as v from " + from
                + " where (" + scope + ")"
                + SqlFilter.and(spec.without(column), columns, params) + like
                // limit +1 is how we learn there were more than we return
                + " order by v nulls last limit " + (MAX_VALUES + 1);
    }
}
