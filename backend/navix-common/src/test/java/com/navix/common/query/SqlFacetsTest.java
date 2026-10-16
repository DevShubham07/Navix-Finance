package com.navix.common.query;

import static com.navix.common.query.QueryTestSupport.COLS;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

class SqlFacetsTest {

    @Test
    void runsInsideTheCallersScopeAndIgnoresOwnFilter() {
        FilterSpec spec = FilterSpec.parse(Map.of("f.status", "in:A", "f.name", "text:contains:ravi"), COLS);
        MapSqlParameterSource p = new MapSqlParameterSource();
        String sql = SqlFacets.sql("with book as (select 1) ", "book b", "b.customer_id in (:scopeIds)",
                spec, COLS, "status", p);
        assertTrue(sql.startsWith("with book as (select 1) select distinct cast((b.status) as text) as v from book b"), sql);
        assertTrue(sql.contains(" where (b.customer_id in (:scopeIds))"), sql);
        assertTrue(sql.contains("lower(cast((lower(b.full_name)) as text)) like"), sql); // other filters narrow
        assertFalse(sql.contains("cast((b.status) as text) in ("), sql); // its own filter does not
        assertTrue(sql.endsWith("limit 501"), sql);
    }

    @Test
    void noScopeIsExplicitTrueNotMissing() {
        String sql = SqlFacets.sql("", "book b", null, FilterSpec.empty(), COLS, "status", new MapSqlParameterSource());
        assertTrue(sql.contains(" where (true)"), sql);
    }

    @Test
    void unknownOrNonFacetColumnYieldsNoQuery() {
        MapSqlParameterSource p = new MapSqlParameterSource();
        assertNull(SqlFacets.sql("", "book b", null, FilterSpec.empty(), COLS, "x; drop table loan", p));
        assertNull(SqlFacets.sql("", "book b", null, FilterSpec.empty(), COLS, "amount", p));
    }
}
