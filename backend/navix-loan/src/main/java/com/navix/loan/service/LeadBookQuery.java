package com.navix.loan.service;

import static com.navix.common.query.ColumnFilter.Kind.IN;
import static com.navix.common.query.ColumnFilter.Kind.NUM;
import static com.navix.common.query.ColumnFilter.Kind.TEXT;
import static com.navix.common.util.LikeEscape.escapeLike;

import com.navix.common.query.FilterColumns;
import com.navix.common.query.FilterSpec;
import com.navix.common.query.SqlFacets;
import com.navix.common.query.SqlFilter;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The telecalling lead register as SQL: the ids of one page, the count behind it, and the facets of
 * its filterable columns — all three built from the same {@link #baseClauses} and the same
 * {@link SqlFilter}, so the page, its total and a column's checklist cannot disagree.
 *
 * <p>Why this is native and not the JPA {@code Specification} it replaced: the shared column filters
 * emit SQL text and cannot compose with a Specification, and translating them into a second,
 * Criteria-shaped emitter would leave two filter implementations free to drift apart. One emitter,
 * so {@link LeadService#list} pages over this and hydrates only the ids on the page.
 * {@code LeadBookQueryEquivalenceIT} holds the old Specification against this query.
 *
 * <p>Wanted index (no migration here): {@code lead (id desc) where owner_dsa_id is null} — the
 * default order plus the scope predicate, so the unfiltered page and its count stay index scans. The
 * existing {@code idx_lead_call_status} and {@code idx_lead_created_at} cover the two narrowing
 * filters staff use most.
 */
@Component
public class LeadBookQuery {

    /**
     * The view-level narrowing the old {@code list()} took, already normalised by the service (trimmed,
     * blank = null, window as instants). {@code createdBy} / {@code outcome} / ratings are the
     * params the admin register sends; none is a column filter.
     */
    public record LeadFilter(
            String needle,
            String callStatus,
            String source,
            Long createdBy,
            Instant from,
            Instant to,
            Integer minRating,
            Integer maxRating,
            String outcome) {
    }

    /**
     * The columns a staffer can filter on, each tied to a SQL expression written here as a literal —
     * the request only ever picks a key ({@link FilterColumns}). Every expression is over {@code lead l}.
     *
     * <p>Deliberately absent: {@code owner_dsa_id} (the scope — a request must not be able to argue
     * with it; see {@link #baseClauses}), {@code lead_outcome} (the register SHOWS an outcome that
     * {@code CONFIRMED} overlays from the attributed application on read, while this column stores only
     * NEW/OUTREACHED/REJECTED — a filter on it would disagree with the cell, so the page keeps its
     * {@code leadOutcome} select, which has the same stored-value meaning it always had), and
     * {@code created_by_staff_id} / {@code created_at} (the admin register's Telecaller select and
     * date window are the one control for those, because the tracker graphs follow them too).
     *
     * <p>Text matches the SQL value and the client matches the cell: name, mobile, pincode and city are
     * shown as stored. Identity columns (name, mobile, pincode) are TEXT only — never a checklist of
     * tens of thousands of them. {@code source} and {@code call_status} are small enums, so a
     * checklist (a missing source is {@code __NULL__}, which is what "—" on screen means); their TEXT
     * kind exists only for the client's "too many values" degrade path. Rating is stars, scale 1.
     */
    public static final FilterColumns FILTER_COLUMNS = FilterColumns.builder()
            .column("name", "l.name", TEXT)
            .column("mobile", "l.mobile", TEXT)
            .column("pincode", "l.pincode", TEXT)
            .column("city", "l.city", TEXT)
            .column("source", "l.source", IN, TEXT)
            .column("callStatus", "l.call_status", IN, TEXT)
            .column("rating", "l.quality_rating", NUM)
            .build();

    private final NamedParameterJdbcTemplate jdbc;

    public LeadBookQuery(JdbcTemplate jdbcTemplate) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    /** Lead ids for one page, newest first — the order the Specification query used ({@code id desc}). */
    public List<Long> pageIds(LeadFilter filter, FilterSpec spec, int offset, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(filter, spec, params);
        params.addValue("limit", limit).addValue("offset", offset);
        return jdbc.queryForList(
                "select l.id from lead l" + where + " order by l.id desc limit :limit offset :offset",
                params, Long.class);
    }

    /** The row count under exactly the clauses {@link #pageIds} uses, so total and page cannot disagree. */
    public long count(LeadFilter filter, FilterSpec spec) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(filter, spec, params);
        Long n = jdbc.queryForObject("select count(*) from lead l" + where, params, Long.class);
        return n == null ? 0 : n;
    }

    /**
     * The distinct values of one column, for its checklist — inside the SAME base clauses as the list
     * (so DSA-owned leads can never leak a value into a dropdown) and narrowed by the other columns'
     * filters; the column's own is dropped by {@link SqlFacets}.
     */
    public SqlFacets.Facet facet(LeadFilter filter, FilterSpec spec, String column) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        List<String> base = baseClauses(filter, params);
        return SqlFacets.facet(jdbc, null, "lead l", String.join(" and ", base), spec, FILTER_COLUMNS, column, params);
    }

    /** Scope and view clauses first, the column filters ANDed last — a filter can only narrow. */
    static String where(LeadFilter f, FilterSpec spec, MapSqlParameterSource p) {
        return " where " + String.join(" and ", baseClauses(f, p)) + SqlFilter.and(spec, FILTER_COLUMNS, p);
    }

    static List<String> baseClauses(LeadFilter f, MapSqlParameterSource p) {
        List<String> clauses = new ArrayList<>();
        // SCOPE, and first. DSA-owned leads never appear to a telecaller/ADMIN here — they stay out of
        // the telecalling queue entirely; ADMIN reaches them only through /api/admin/dsa/**. It is a
        // constant in the base so no filter, facet or request param can reach past it.
        clauses.add("l.owner_dsa_id is null");
        if (f.needle() != null) {
            // Name is case-insensitive, mobile is matched as typed — as the Specification did.
            p.addValue("needleName", "%" + escapeLike(f.needle().toLowerCase(java.util.Locale.ROOT)) + "%");
            p.addValue("needleMobile", "%" + escapeLike(f.needle()) + "%");
            clauses.add("(lower(l.name) like :needleName escape '\\' or l.mobile like :needleMobile escape '\\')");
        }
        if (f.callStatus() != null) {
            p.addValue("callStatus", f.callStatus());
            clauses.add("l.call_status = :callStatus");
        }
        if (f.source() != null) {
            p.addValue("source", f.source());
            clauses.add("l.source = :source");
        }
        if (f.createdBy() != null) {
            p.addValue("createdBy", f.createdBy());
            clauses.add("l.created_by_staff_id = :createdBy");
        }
        if (f.outcome() != null) {
            p.addValue("outcome", f.outcome());
            clauses.add("l.lead_outcome = :outcome");
        }
        // Half-open [from, to): the service passes `to` as the NEXT midnight, so a one-day window
        // means that whole day.
        if (f.from() != null) {
            p.addValue("from", Timestamp.from(f.from()));
            clauses.add("l.created_at >= :from");
        }
        if (f.to() != null) {
            p.addValue("to", Timestamp.from(f.to()));
            clauses.add("l.created_at < :to");
        }
        // Inclusive bounds; an unrated lead (null) matches neither, as before.
        if (f.minRating() != null) {
            p.addValue("minRating", f.minRating());
            clauses.add("l.quality_rating >= :minRating");
        }
        if (f.maxRating() != null) {
            p.addValue("maxRating", f.maxRating());
            clauses.add("l.quality_rating <= :maxRating");
        }
        return clauses;
    }
}
