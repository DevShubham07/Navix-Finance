package com.navix.loan.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The "customer book" as SQL: one lightweight row per customer carrying exactly what the Customers
 * list needs to <b>filter, sort, segment and count</b> — never the full {@code CustomerSummary}.
 * {@link CustomerService#page} pages over it and hydrates only the ids on the page;
 * {@link CustomerService#summary} groups over it for the segment chips.
 *
 * <p>The {@code segment} CASE mirrors {@code segmentOf()} in the frontend's {@code segments.ts}
 * (loan status outranks application status) and {@code loan_status} mirrors
 * {@code Loan.effectiveStatus(today)}; the {@code stage} CTE is the SQL twin of
 * {@code ApplicationEventRepository.findCurrentStatusEnteredAt}. Keep the three in step.
 */
@Component
public class CustomerBookQuery {

    /**
     * @param scopeIds customers a scoped staffer may see (null = whole book)
     * @param scopeIncludesUnallocated true when the caller may also see customers nobody owns
     * @param mineIds "My customers" restriction — owned by or decided by the caller (null = off)
     */
    public record BookFilter(
            String needle,
            Instant from,
            Instant to,
            String segment,
            Set<Long> scopeIds,
            boolean scopeIncludesUnallocated,
            Set<Long> mineIds,
            LocalDate today) {
    }

    public record SegmentCount(String segment, long count, long unallocated) {
    }

    public static final Set<String> SEGMENTS = Set.of(
            "incomplete", "pending", "review", "approved", "disbursementPending",
            "active", "overdue", "hold", "rejected", "closed", "unallocated");

    private static final String BOOK_CTE = """
            with latest_app as (
                select distinct on (a.customer_id) a.customer_id, a.id as app_id, a.status as app_status, a.created_at
                from loan_application a
                order by a.customer_id, a.id desc
            ), latest_loan as (
                select distinct on (l.customer_id) l.customer_id,
                       case when l.status = 'ACTIVE' and l.due_date is not null and l.due_date < :today
                            then 'OVERDUE' else l.status end as loan_status
                from loan l
                order by l.customer_id, l.id desc
            ), prof as (
                select distinct on (a.customer_id) a.customer_id, p.full_name, p.pan, p.mobile
                from customer_profile p
                join loan_application a on a.id = p.application_id
                order by a.customer_id, a.id desc
            ), stage as (
                select e.application_id, max(e.at) as at
                from application_event e
                join loan_application a on a.id = e.application_id
                where e.to_status = a.status
                  and (e.from_status is null or e.from_status <> e.to_status)
                group by e.application_id
            ), book as (
                select la.customer_id,
                       la.created_at,
                       o.owner_staff_id,
                       coalesce(s.at, la.created_at) as status_changed_at,
                       pr.full_name, pr.pan, pr.mobile,
                       case
                         when ll.loan_status in ('OVERDUE', 'IN_COLLECTIONS') then 'overdue'
                         when la.app_status in ('OVERDUE', 'DEFAULTED') then 'overdue'
                         when ll.loan_status in ('ACTIVE', 'DISBURSING') then 'active'
                         when la.app_status in ('DISBURSED', 'ACTIVE') then 'active'
                         when la.app_status in ('REVIEW_PENDING', 'CREDIT_EXEC_PENDING', 'CREDIT_HEAD_PENDING') then 'review'
                         when la.app_status in ('KYC_APPROVED', 'CREDIT_EXEC_APPROVED', 'CREDIT_HEAD_APPROVED', 'SANCTIONED') then 'approved'
                         when la.app_status in ('DISBURSEMENT_PENDING', 'ACCOUNTANT_PENDING') then 'disbursementPending'
                         when la.app_status = 'DISBURSEMENT_FAILED' then 'hold'
                         when la.app_status = 'DRAFT' then 'incomplete'
                         when la.app_status in ('KYC_REJECTED', 'REJECTED') then 'rejected'
                         when la.app_status in ('CLOSED', 'WRITTEN_OFF', 'CANCELLED') then 'closed'
                         else 'pending'
                       end as segment
                from latest_app la
                left join latest_loan ll on ll.customer_id = la.customer_id
                left join customer_owner o on o.customer_id = la.customer_id
                left join stage s on s.application_id = la.app_id
                left join prof pr on pr.customer_id = la.customer_id
            )
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public CustomerBookQuery(JdbcTemplate jdbcTemplate) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    /** Customer ids for one page, in display order (stage date desc, nulls last). */
    public List<Long> pageIds(BookFilter filter, int offset, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(filter, true, params);
        params.addValue("limit", limit).addValue("offset", offset);
        String sql = BOOK_CTE + "select b.customer_id from book b" + where
                + " order by b.status_changed_at desc nulls last, b.customer_id desc"
                + " limit :limit offset :offset";
        return jdbc.queryForList(sql, params, Long.class);
    }

    public long count(BookFilter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(filter, true, params);
        Long n = jdbc.queryForObject(BOOK_CTE + "select count(*) from book b" + where, params, Long.class);
        return n == null ? 0 : n;
    }

    /** Per-segment counts under the filter, ignoring {@code filter.segment()}. */
    public List<SegmentCount> segmentCounts(BookFilter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(filter, false, params);
        String sql = BOOK_CTE + "select b.segment, count(*) as n,"
                + " count(*) filter (where b.owner_staff_id is null) as unallocated"
                + " from book b" + where + " group by b.segment";
        return jdbc.query(sql, params, (rs, i) ->
                new SegmentCount(rs.getString("segment"), rs.getLong("n"), rs.getLong("unallocated")));
    }

    private static String where(BookFilter f, boolean applySegment, MapSqlParameterSource p) {
        p.addValue("today", java.sql.Date.valueOf(f.today()));
        List<String> clauses = new ArrayList<>();

        if (f.needle() != null && !f.needle().isEmpty()) {
            p.addValue("needle", "%" + escapeLike(f.needle()) + "%");
            clauses.add("(lower(b.full_name) like :needle escape '\\'"
                    + " or lower(b.pan) like :needle escape '\\'"
                    + " or b.mobile like :needle escape '\\'"
                    + " or cast(b.customer_id as text) like :needle escape '\\'"
                    + " or exists (select 1 from loan_application a2 where a2.customer_id = b.customer_id"
                    + " and cast(a2.id as text) like :needle escape '\\'))");
        }
        if (f.from() != null) {
            p.addValue("from", Timestamp.from(f.from()));
            clauses.add("b.created_at >= :from");
        }
        if (f.to() != null) {
            p.addValue("to", Timestamp.from(f.to()));
            clauses.add("b.created_at < :to");
        }
        if (applySegment && f.segment() != null && !f.segment().isBlank() && !"all".equals(f.segment())) {
            if ("unallocated".equals(f.segment())) {
                clauses.add("b.owner_staff_id is null");
            } else {
                p.addValue("seg", f.segment());
                clauses.add("b.segment = :seg");
            }
        }
        if (f.scopeIds() != null) {
            String owned = idsClause(f.scopeIds(), "scopeIds", p);
            clauses.add(f.scopeIncludesUnallocated()
                    ? "(" + owned + " or b.owner_staff_id is null)"
                    : owned);
        }
        if (f.mineIds() != null) {
            clauses.add(idsClause(f.mineIds(), "mineIds", p));
        }
        return clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses);
    }

    /** {@code in ()} is invalid SQL, so an empty set becomes a constant false. */
    private static String idsClause(Set<Long> ids, String name, MapSqlParameterSource p) {
        if (ids.isEmpty()) {
            return "false";
        }
        p.addValue(name, ids);
        return "b.customer_id in (:" + name + ")";
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
