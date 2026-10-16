package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.navix.common.query.ColumnFilter.Kind;
import com.navix.common.query.FilterSpec;
import com.navix.common.query.SqlFilter;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/** The register's filter registry: what reaches SQL, what is only parsed, and what can have a dropdown. */
class LoanRegisterQueryTest {

    @Test
    void outstandingAndOfficerAreParsedButNeverRegisteredForSql() {
        assertThat(LoanRegisterQuery.FILTER_COLUMNS.get("outstandingPaise")).isNull();
        assertThat(LoanRegisterQuery.FILTER_COLUMNS.get("assignedOfficerName")).isNull();
        FilterSpec spec = FilterSpec.parse(Map.of("f.outstandingPaise", "num:100..", "f.assignedOfficerName", "in:A"),
                LoanRegisterQuery.PARSE_COLUMNS);
        assertThat(spec.filters().keySet()).containsExactly("assignedOfficerName", "outstandingPaise");
        // Handed to SQL as-is they would be silently dropped, not emitted: nothing to inject, nothing to leak.
        assertThat(SqlFilter.and(spec, LoanRegisterQuery.FILTER_COLUMNS, new MapSqlParameterSource())).isEmpty();
    }

    @Test
    void sortKeysNeverIncludeTheJavaColumns() {
        assertThat(LoanRegisterQuery.SORTS).doesNotContainKeys("outstandingPaise", "assignedOfficerName");
        assertThat(LoanRegisterQuery.SORTS.keySet()).hasSize(11);
    }

    @Test
    void moneyBoundsAreScaledOnTheOperandNotTheColumn() {
        MapSqlParameterSource params = new MapSqlParameterSource();
        FilterSpec spec = FilterSpec.parse(Map.of("f.principalPaise", "num:10000..25000.50"), LoanRegisterQuery.FILTER_COLUMNS);

        String sql = SqlFilter.and(spec, LoanRegisterQuery.FILTER_COLUMNS, params);

        assertThat(sql).contains("(r.principal) >= :cf0min").contains("(r.principal) <= :cf0max");
        assertThat(params.getValue("cf0min")).isEqualTo(1_000_000L);
        assertThat(params.getValue("cf0max")).isEqualTo(2_500_050L);
    }

    @Test
    void identityColumnsAreTextOnlySoTheyCanNeverHaveADropdown() {
        assertThat(LoanRegisterQuery.FILTER_COLUMNS.get("borrowerName").accepts(Kind.IN)).isFalse();
        assertThat(LoanRegisterQuery.FILTER_COLUMNS.get("borrowerName").accepts(Kind.TEXT)).isTrue();
        assertThat(LoanRegisterQuery.FILTER_COLUMNS.get("status").accepts(Kind.IN)).isTrue();
        for (String money : new String[] {"principalPaise", "netDisbursedPaise", "totalRepayablePaise", "dpd", "loanCycle"}) {
            assertThat(LoanRegisterQuery.FILTER_COLUMNS.get(money).accepts(Kind.IN)).as(money).isFalse();
        }
    }
}
