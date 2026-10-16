package com.navix.common.query;

import static com.navix.common.query.QueryTestSupport.COLS;
import static org.junit.jupiter.api.Assertions.*;

import com.navix.common.exception.BusinessException;
import com.navix.common.query.ColumnFilter.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

class SqlFilterInjectionTest {

    private static String emit(Map<String, String> req, MapSqlParameterSource p) {
        return SqlFilter.and(FilterSpec.parse(req, COLS), COLS, p);
    }

    @Test
    void hostileColumnNamesEmitNothingAndBindNothing() {
        for (String col : new String[] {"x; drop table loan", "1=1 --", "status) or (1=1", "status'--", "b.status"}) {
            MapSqlParameterSource p = new MapSqlParameterSource();
            assertEquals("", emit(Map.of("f." + col, "in:A"), p), col);
            assertEquals(0, p.getParameterNames().length, col);
        }
    }

    @Test
    void hostileColumnDoesNotDisturbALegitimateOne() {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String sql = emit(Map.of("f.status", "in:A", "f.x; drop table loan", "in:B"), p);
        assertFalse(sql.contains("drop"));
        assertEquals(1, p.getParameterNames().length);
    }

    @Test
    void operandsAreBoundNeverInline() {
        String evil = "A' or '1'='1";
        MapSqlParameterSource p = new MapSqlParameterSource();
        String sql = emit(Map.of("f.status", "in:" + evil.replace(",", "%2C")), p);
        assertFalse(sql.contains("1'='1"), sql);
        assertFalse(sql.contains("'A"), sql);
        assertEquals(" and ((cast((b.status) as text) in (:cf0)))", sql);
        assertEquals(List.of(evil), p.getValue("cf0"));
    }

    @Test
    void quoteAndPercentCannotBreakOutOfLike() {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String sql = emit(Map.of("f.name", "text:contains:50%_'; drop table x --"), p);
        assertFalse(sql.contains("drop"), sql);
        assertFalse(sql.contains("'; "), sql);
        assertTrue(sql.contains("like :cf0 escape '\\'"), sql);
        // % and _ are escaped so the term is literal, and the quote stays inside the bound value
        assertEquals("%50\\%\\_'; drop table x --%", p.getValue("cf0"));
    }

    @Test
    void backslashInTermIsEscapedSoItCannotEscapeTheEscape() {
        MapSqlParameterSource p = new MapSqlParameterSource();
        emit(Map.of("f.name", "text:starts:a\\%"), p);
        assertEquals("a\\\\\\%%", p.getValue("cf0"));
    }

    @Test
    void numAndDayOperandsAreBound() {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String sql = emit(Map.of("f.amount", "num:100..200", "f.dueDate", "date:2026-01-01..2026-01-01"), p);
        assertFalse(sql.contains("100"));
        assertFalse(sql.contains("2026"));
        assertTrue(sql.contains("(b.amount) >= :cf0min") && sql.contains("(b.amount) <= :cf0max"), sql);
        // one-day range = that whole IST day: [00:00 IST, next 00:00 IST)
        assertTrue(sql.contains("(b.due_date) >= :cf1from") && sql.contains("(b.due_date) < :cf1to"), sql);
        java.sql.Timestamp from = (java.sql.Timestamp) p.getValue("cf1from");
        java.sql.Timestamp to = (java.sql.Timestamp) p.getValue("cf1to");
        assertEquals(24 * 3600 * 1000L, to.getTime() - from.getTime());
        assertEquals(java.time.Instant.parse("2025-12-31T18:30:00Z"), from.toInstant());
    }

    @Test
    void nullSentinelBecomesIsNullOrIn() {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String sql = emit(Map.of("f.officer", "in:__NULL__,7"), p);
        assertTrue(sql.contains("(b.owner_staff_id) is null or cast((b.owner_staff_id) as text) in (:cf0)"), sql);
        assertEquals(List.of("7"), p.getValue("cf0"));
        MapSqlParameterSource q = new MapSqlParameterSource();
        String only = emit(Map.of("f.officer", "in:__NULL__"), q);
        assertTrue(only.contains("is null") && !only.contains(" in ("), only);
        assertEquals(0, q.getParameterNames().length);
    }

    @Test
    void emptySpecEmitsNothing() {
        assertEquals("", SqlFilter.and(FilterSpec.empty(), COLS, new MapSqlParameterSource()));
    }

    @Test
    void emptyInEmitsFalse() {
        FilterSpec s = FilterSpec.of(Map.of("status", new In(List.of())));
        MapSqlParameterSource p = new MapSqlParameterSource();
        assertEquals(" and ((false))", SqlFilter.and(s, COLS, p));
        assertEquals(0, p.getParameterNames().length);
    }

    @Test
    void over500ValuesIs422() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 501; i++) many.add("v" + i);
        FilterSpec s = FilterSpec.of(Map.of("status", new In(many)));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> SqlFilter.and(s, COLS, new MapSqlParameterSource()));
        assertEquals("FILTER_TOO_MANY_VALUES", ex.getCode());
        // exactly 500 is fine
        FilterSpec ok = FilterSpec.of(Map.of("status", new In(many.subList(0, 500))));
        assertFalse(SqlFilter.and(ok, COLS, new MapSqlParameterSource()).isEmpty());
    }

    @Test
    void registryRefusesNonConstantLookingSql() {
        assertThrows(IllegalArgumentException.class,
                () -> FilterColumns.builder().column("a", "b.x; drop table y", ColumnFilter.Kind.IN));
        assertThrows(IllegalArgumentException.class,
                () -> FilterColumns.builder().column("a b", "b.x", ColumnFilter.Kind.IN));
    }

    @Test
    void moneyBoundIsScaledOnTheOperandNotTheColumn() {
        FilterColumns paise = FilterColumns.builder()
                .scaled("amount", "b.amount_paise", 100, ColumnFilter.Kind.NUM).build();
        MapSqlParameterSource p = new MapSqlParameterSource();
        String sql = SqlFilter.and(FilterSpec.parse(Map.of("f.amount", "num:10000..99.5"), paise), paise, p);
        assertEquals(" and (((b.amount_paise) >= :cf0min and (b.amount_paise) <= :cf0max))".replace("((("," (("), sql.replace("((("," (("));
        assertFalse(sql.contains("/") || sql.contains("*"), sql);
        assertEquals(1000000L, p.getValue("cf0min"));
        assertEquals(9950L, p.getValue("cf0max")); // 99.5 rupees
        MapSqlParameterSource q = new MapSqlParameterSource();
        SqlFilter.and(FilterSpec.parse(Map.of("f.amount", "num:..0.005"), paise), paise, q);
        assertEquals(1L, q.getValue("cf0max")); // 0.5 paise rounds HALF_UP
    }
}
