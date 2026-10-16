package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.navix.common.query.FilterSpec;
import com.navix.common.query.SqlFilter;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/** What the ledger declares about its columns; the SQL against a real database is the integration test. */
class TransactionLedgerQueryTest {

    private static FilterSpec spec(String key, String value) {
        return FilterSpec.parse(Map.of(key, value), TransactionLedgerQuery.FILTER_COLUMNS);
    }

    @Test
    void statusIsATextFilterNotAChecklist() {
        // A disbursal's status is a LoanStatus and a repayment's a PaymentStatus: an `in` list would
        // mix two enums, so the registry must drop it rather than accept it.
        assertThat(spec("f.status", "in:VERIFIED,ACTIVE").isEmpty()).isTrue();
        assertThat(spec("f.status", "text:eq:VERIFIED").isEmpty()).isFalse();
    }

    @Test
    void amountBoundsAreRupeesScaledToPaiseOnTheOperand() {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String sql = SqlFilter.and(spec("f.amount", "num:1000..2500.50"), TransactionLedgerQuery.FILTER_COLUMNS, p);

        assertThat(sql).contains("(t.amount_paise) >= :cf0min").contains("(t.amount_paise) <= :cf0max");
        assertThat(p.getValue("cf0min")).isEqualTo(100_000L);   // ₹1,000 → paise
        assertThat(p.getValue("cf0max")).isEqualTo(250_050L);   // ₹2,500.50 → paise, never a float
    }

    @Test
    void identityColumnsHaveNoFacet() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionLedgerQuery query = new TransactionLedgerQuery(jdbc);
        var filter = new TransactionLedgerQuery.LedgerFilter(null, null, null, null);

        for (String identity : new String[] {"borrower", "reference", "loan", "status", "amount", "unknown"}) {
            var facet = query.facet(filter, FilterSpec.empty(), identity);
            assertThat(facet.values()).as(identity).isEmpty();
        }
        verifyNoInteractions(jdbc); // refused before any statement is built
    }

    @Test
    void unregisteredColumnsAreDroppedAndOperandsAreBound() {
        assertThat(spec("f.txn_ref", "text:eq:x").isEmpty()).isTrue();
        MapSqlParameterSource p = new MapSqlParameterSource();
        String sql = SqlFilter.and(spec("f.reference", "text:contains:x'; drop table loan"),
                TransactionLedgerQuery.FILTER_COLUMNS, p);
        assertThat(sql).doesNotContain("drop table");
        assertThat(p.getParameterNames()).containsExactly("cf0");
    }

    @Test
    void sortsAreConstantExpressionsKeyedByColumn() {
        assertThat(TransactionLedgerQuery.SORTS).containsKeys("date", "amount", "status", "borrower");
        assertThat(TransactionLedgerQuery.SORTS.values()).allMatch(v -> v.startsWith("t.") || v.startsWith("lower(t."));
    }
}
