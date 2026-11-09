package com.navix.common.query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The server-side twin of the frontend's {@code ColumnFilter} union
 * ({@code lib/staff/column-filter-match.ts}). Four kinds because a register column is one of four
 * things: a set you tick, a quantity you bound, a day you window, or text you search.
 */
public sealed interface ColumnFilter {

    /** The explicit "has no value" sentinel — an absence cannot be expressed as a value otherwise. */
    String BLANK = "__NULL__";

    /** Which kind a column accepts; {@link FilterColumns} declares one set per column. */
    enum Kind { IN, NUM, DAY, TEXT }

    Kind kind();

    /** Raw stored values; may contain {@link #BLANK}. */
    record In(List<String> values) implements ColumnFilter {
        public In {
            values = List.copyOf(values);
        }

        @Override
        public Kind kind() {
            return Kind.IN;
        }
    }

    /** Inclusive on both sides, either may be null. {@link BigDecimal}, never double — money-safe. */
    record Num(BigDecimal min, BigDecimal max) implements ColumnFilter {
        @Override
        public Kind kind() {
            return Kind.NUM;
        }
    }

    /** IST business days, inclusive on both sides; either may be null. */
    record Day(LocalDate from, LocalDate to) implements ColumnFilter {
        @Override
        public Kind kind() {
            return Kind.DAY;
        }
    }

    enum TextOp { CONTAINS, STARTS, EQ }

    record Text(TextOp op, String term) implements ColumnFilter {
        @Override
        public Kind kind() {
            return Kind.TEXT;
        }
    }
}
