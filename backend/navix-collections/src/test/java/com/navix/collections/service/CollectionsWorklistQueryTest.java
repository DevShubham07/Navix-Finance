package com.navix.collections.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.navix.collections.service.CollectionsWorklistQuery.WorklistFilter;
import com.navix.common.query.FilterSpec;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * The SQL shape of the worklist query: what a request can and cannot reach. The row-level behaviour
 * (does the filtered page equal the old client-side result) is in the Postgres-backed
 * {@code CollectionsWorklistQueryIntegrationTest} in navix-app, which needs a real database.
 */
class CollectionsWorklistQueryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 16);

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final CollectionsWorklistQuery query = new CollectionsWorklistQuery(jdbc);

    private static WorklistFilter head() {
        return new WorklistFilter(null, null, null, null, false, null, TODAY, TODAY);
    }

    private static WorklistFilter executive(Long id) {
        return new WorklistFilter(null, null, null, null, true, id, TODAY, TODAY);
    }

    private static FilterSpec spec(String... kv) {
        Map<String, String> m = new java.util.HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return FilterSpec.parse(m, CollectionsWorklistQuery.FILTER_COLUMNS);
    }

    @Test
    void identityAndScopeColumnsAreNotFilterable() {
        // Customer id, loan id, mobile and PAN are found with the search box, never a checklist; the
        // The officer id column is registered as "officerName" on purpose (filters are ANDed after scope).
        for (String key : new String[] {"customerId", "loanId", "mobile", "pan", "officerId", "assignedOfficerId"}) {
            assertThat(CollectionsWorklistQuery.FILTER_COLUMNS.get(key)).as(key).isNull();
        }
        for (String key : new String[] {"borrowerName", "employer", "principalPaise", "salaryPaise", "dueDate",
                "dpd", "bucketLabel", "officerName"}) {
            assertThat(CollectionsWorklistQuery.FILTER_COLUMNS.get(key)).as(key).isNotNull();
        }
        // Money is declared in rupees on the wire, paise stored.
        assertThat(CollectionsWorklistQuery.FILTER_COLUMNS.get("principalPaise").displayScale()).isEqualTo(100);
        assertThat(CollectionsWorklistQuery.FILTER_COLUMNS.get("salaryPaise").displayScale()).isEqualTo(100);
    }

    @Test
    void theOfficerFilterKeysOnTheIdAndIsAndedAfterTheScope() {
        assertThat(CollectionsWorklistQuery.FILTER_COLUMNS.get("officerName").sql()).isEqualTo("w.officer_id");
        query.pageIds(executive(7L), spec("f.officerName", "in:9,__NULL__"), 0, 25, null, null);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), any(SqlParameterSource.class), eq(Long.class));
        String s = sql.getValue();
        assertThat(s.indexOf("w.officer_id = :scopeOfficer")).isLessThan(s.indexOf("(w.officer_id)"));
        assertThat(s).contains("(w.officer_id) is null");
    }

    @Test
    void anExecutivesFacetRunsInsideTheirOwnScope() {
        query.facet(executive(7L), spec("f.dpd", "num:1.."), "bucketLabel");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).query(sql.capture(), params.capture(), any(RowMapper.class));
        assertThat(sql.getValue()).contains("(w.officer_id = :scopeOfficer)");
        assertThat(params.getValue().getValue("scopeOfficer")).isEqualTo(7L);
        // The other columns' filter narrows the facet, scope comes first.
        assertThat(sql.getValue().indexOf("w.officer_id = :scopeOfficer")).isLessThan(sql.getValue().indexOf("cf0"));
    }

    @Test
    void anExecutiveWhoseStaffIdDoesNotResolveSeesNothingNotEverything() {
        query.facet(executive(null), FilterSpec.empty(), "bucketLabel");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        assertThat(sql.getValue()).contains("where (false)").doesNotContain("scopeOfficer");
    }

    @Test
    void identityAndMoneyColumnsNeverFacetAndTouchNoDatabase() {
        for (String column : new String[] {"customerId", "loanId", "mobile", "pan", "borrowerName", "principalPaise", "dpd"}) {
            assertThat(query.facet(head(), FilterSpec.empty(), column).values()).as(column).isEmpty();
        }
        verify(jdbc, never()).query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
    }

    @Test
    void theColumnFilterIsAndedAfterTheScopeClause() {
        query.pageIds(executive(7L), spec("f.borrowerName", "text:contains:asha", "f.dpd", "num:5.."), 0, 25, "dpd", "desc");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).queryForList(sql.capture(), params.capture(), eq(Long.class));
        String s = sql.getValue();
        assertThat(s.indexOf("w.officer_id = :scopeOfficer")).isPositive().isLessThan(s.indexOf("cf0"));
        // Every operand is bound, never concatenated.
        assertThat(s).doesNotContain("asha");
        assertThat(params.getValue().getValue("cf0")).isEqualTo("%asha%");
    }

    @Test
    void anUnknownSortKeyFallsBackToMostOverdueFirstAndNeverReachesTheStatement() {
        query.pageIds(head(), FilterSpec.empty(), 0, 25, "1; drop table loan", "asc");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), any(SqlParameterSource.class), eq(Long.class));
        assertThat(sql.getValue()).contains("order by w.dpd desc").doesNotContain("drop table");
    }

    @Test
    void theBucketCaseMirrorsTheDpdCalculatorBands() {
        // One literal check per band edge so the SQL CASE and DpdCalculator cannot drift apart silently.
        query.count(head(), FilterSpec.empty());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(sql.capture(), any(SqlParameterSource.class), eq(Long.class));
        assertThat(sql.getValue()).contains("<= 0 then 'UPCOMING'", "<= 7 then 'T0_T7'", "<= 30 then 'T8_T30'",
                "<= 60 then 'T30_T60'", "<= 90 then 'T60_T90'", "else 'T90_PLUS'");
        DpdCalculator calc = new DpdCalculator();
        assertThat(calc.bucket(7).name()).isEqualTo("T0_T7");
        assertThat(calc.bucket(8).name()).isEqualTo("T8_T30");
        assertThat(calc.bucket(30).name()).isEqualTo("T8_T30");
        assertThat(calc.bucket(31).name()).isEqualTo("T30_T60");
        assertThat(calc.bucket(60).name()).isEqualTo("T30_T60");
        assertThat(calc.bucket(61).name()).isEqualTo("T60_T90");
        assertThat(calc.bucket(90).name()).isEqualTo("T60_T90");
        assertThat(calc.bucket(91).name()).isEqualTo("T90_PLUS");
    }
}
