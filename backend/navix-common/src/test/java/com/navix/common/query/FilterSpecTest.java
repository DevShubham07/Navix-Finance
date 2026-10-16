package com.navix.common.query;

import static com.navix.common.query.QueryTestSupport.COLS;
import static org.junit.jupiter.api.Assertions.*;

import com.navix.common.query.ColumnFilter.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FilterSpecTest {

    private static ColumnFilter one(String col, String v) {
        return FilterSpec.parse(Map.of("f." + col, v), COLS).get(col);
    }

    @Test
    void inRoundTripsEscapeOrder() {
        // client: "a,b%2C" -> esc: % first -> "a,b%252C", then , -> "a%2Cb%252C"
        assertEquals(new In(List.of("a,b%2C", "X")), one("status", "in:a%2Cb%252C,X"));
    }

    @Test
    void lowercaseEscapesDecodeLikeTheClient() {
        assertEquals(new In(List.of("a,b")), one("status", "in:a%2cb"));
    }

    @Test
    void nullSentinelIsPreserved() {
        assertEquals(new In(List.of(ColumnFilter.BLANK)), one("officer", "in:__NULL__"));
    }

    @Test
    void numRanges() {
        assertEquals(new Num(new BigDecimal("100000"), new BigDecimal("500000")), one("amount", "num:100000..500000"));
        assertEquals(new Num(new BigDecimal("100000"), null), one("amount", "num:100000.."));
        assertEquals(new Num(null, new BigDecimal("500000")), one("amount", "num:..500000"));
        assertEquals(new Num(new BigDecimal("1.5"), new BigDecimal("2.5")), one("amount", "num:1.5..2.5"));
        assertNull(one("amount", "num:..")); // empty on both sides is no filter
        assertEquals(new Num(null, new BigDecimal("9")), one("amount", "num:abc..9")); // bad bound dropped
    }

    @Test
    void dayRanges() {
        assertEquals(new Day(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1)),
                one("dueDate", "date:2026-01-01..2026-02-01"));
        assertEquals(new Day(LocalDate.of(2026, 1, 1), null), one("dueDate", "date:2026-01-01.."));
        assertEquals(new Day(null, LocalDate.of(2026, 2, 1)), one("dueDate", "date:..2026-02-01"));
    }

    @Test
    void malformedDayIsDropped() {
        assertNull(one("dueDate", "date:2026-13-99..2026-14-01"));
        assertEquals(new Day(null, LocalDate.of(2026, 2, 1)), one("dueDate", "date:2026-13-99..2026-02-01"));
        assertNull(one("dueDate", "date:2026-02-30.."));
        assertNull(one("dueDate", "date:2026-1-1.."));
    }

    @Test
    void textSplitsOnFirstColonOnly() {
        assertEquals(new Text(TextOp.CONTAINS, "ravi"), one("name", "text:contains:ravi"));
        assertEquals(new Text(TextOp.EQ, "a:b"), one("name", "text:eq:a:b"));
        assertNull(one("name", "text:regex:x"));
        assertNull(one("name", "text:contains:  "));
    }

    @Test
    void aNameBeginningWithATagStaysAnInList() {
        // tagged on purpose: a stored value / name may start "eq:" or contain ".."
        assertEquals(new In(List.of("a..b")), one("status", "in:a..b"));
    }

    @Test
    void unknownColumnIsDropped() {
        assertTrue(FilterSpec.parse(Map.of("f.nope", "in:A", "other", "in:A"), COLS).isEmpty());
    }

    @Test
    void kindTheColumnDoesNotAcceptIsDropped() {
        assertNull(one("status", "num:1..2"));
    }

    @Test
    void untaggedLegacyValues() {
        assertEquals(new In(List.of("SANCTIONED", "ACTIVE")), one("status", "SANCTIONED,ACTIVE"));
        assertEquals(new Day(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1)),
                one("dueDate", "2026-01-01..2026-02-01"));
        assertEquals(new Num(new BigDecimal("5"), new BigDecimal("9")), one("amount", "5..9"));
    }

    @Test
    void withoutDropsOnlyThatColumn() {
        FilterSpec s = FilterSpec.parse(Map.of("f.status", "in:A", "f.officer", "in:__NULL__"), COLS);
        assertNull(s.without("status").get("status"));
        assertNotNull(s.without("status").get("officer"));
        assertNotNull(s.get("status")); // original untouched
    }
}
