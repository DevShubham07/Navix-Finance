package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.navix.common.query.ColumnFilter.Kind;
import com.navix.common.query.FilterSpec;
import com.navix.common.query.SqlFilter;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/** The customer register's filter registry: kinds, money scale and what scope is kept out of. */
class CustomerBookQueryTest {

    private static final com.navix.common.query.FilterColumns COLS = CustomerBookQuery.FILTER_COLUMNS;

    @Test
    void newColumnsDeclareTheRightKinds() {
        for (String c : new String[] {"dpd", "risk", "owner"}) {
            assertThat(COLS.get(c).accepts(Kind.IN)).as(c).isTrue();
            assertThat(COLS.get(c).accepts(Kind.NUM)).as(c).isFalse();
        }
        assertThat(COLS.get("employer").accepts(Kind.IN)).isTrue();
        assertThat(COLS.get("employer").accepts(Kind.TEXT)).isTrue();
        for (String c : new String[] {"loanAmount", "salary", "creditScore"}) {
            assertThat(COLS.get(c).accepts(Kind.NUM)).as(c).isTrue();
            assertThat(COLS.get(c).accepts(Kind.IN)).as(c).isFalse();
        }
        assertThat(COLS.get("joined").accepts(Kind.DAY)).isTrue();
    }

    @Test
    void moneyColumnsScaleRupeesToPaiseOnTheOperandAndScoreDoesNot() {
        assertThat(COLS.get("loanAmount").displayScale()).isEqualTo(100);
        assertThat(COLS.get("salary").displayScale()).isEqualTo(100);
        assertThat(COLS.get("creditScore").displayScale()).isEqualTo(1);
        MapSqlParameterSource p = new MapSqlParameterSource();
        String sql = SqlFilter.and(FilterSpec.parse(Map.of("f.loanAmount", "num:5000.01..10000"), COLS), COLS, p);
        assertThat(sql).contains("(b.loan_amount) >= :cf0min");
        assertThat(p.getValue("cf0min")).isEqualTo(500_001L);
        assertThat(p.getValue("cf0max")).isEqualTo(1_000_000L);
    }

    @Test
    void ownerIsFilterableButTheScopeColumnNamesAreNotRegistered() {
        assertThat(COLS.get("owner")).isNotNull();
        assertThat(COLS.get("owner_staff_id")).isNull();
        assertThat(COLS.get("customer_id")).isNull();
        assertThat(COLS.get("ownerStaffId")).isNull();
    }
}
