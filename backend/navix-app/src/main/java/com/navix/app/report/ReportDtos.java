package com.navix.app.report;

import java.util.List;

/**
 * Wire shapes of the ADMIN reports centre. Every report — whatever it is about — comes back as the
 * same table: typed columns, raw rows, and a handful of headline figures. The frontend renders one
 * preview from it and writes the CSV and the PDF from the very same rows, so the two downloads can
 * never carry different information.
 *
 * <p>Cells are raw: money is integer paise ({@link ColumnType#MONEY}), dates are ISO {@code yyyy-MM-dd},
 * timestamps ISO-8601 instants. Formatting happens once, on the client, at render/export time.
 */
public final class ReportDtos {

    private ReportDtos() {
    }

    /** How a column's raw cell is formatted (and, for MONEY / NUMBER, totalled). */
    public enum ColumnType { TEXT, NUMBER, MONEY, DATE, DATETIME, BOOL }

    /** One column: a stable key, the header label, and its type. */
    public record Column(String key, String label, ColumnType type) {
        static Column text(String key, String label) {
            return new Column(key, label, ColumnType.TEXT);
        }

        /** An identifier: a number, but written as-is (never digit-grouped or totalled). */
        static Column id(String key, String label) {
            return new Column(key, label, ColumnType.TEXT);
        }

        static Column number(String key, String label) {
            return new Column(key, label, ColumnType.NUMBER);
        }

        static Column money(String key, String label) {
            return new Column(key, label, ColumnType.MONEY);
        }

        static Column date(String key, String label) {
            return new Column(key, label, ColumnType.DATE);
        }

        static Column dateTime(String key, String label) {
            return new Column(key, label, ColumnType.DATETIME);
        }

        static Column bool(String key, String label) {
            return new Column(key, label, ColumnType.BOOL);
        }
    }

    /** A headline figure shown above the table (and written at the top of both downloads). */
    public record Fact(String label, Object value, ColumnType type) {
    }

    /**
     * A catalog entry: what the report is, which group it sits in, and — when it is period-filtered —
     * which date the period is applied to ({@code dateBasis}, null = a point-in-time snapshot).
     */
    public record ReportDef(String key, String category, String title, String description, String dateBasis) {
    }

    /**
     * One generated report. {@code from}/{@code to} are null for an all-time or snapshot report;
     * {@code totals} is false when summing the MONEY columns would add up unlike things.
     */
    public record Report(String key, String category, String title, String description, String dateBasis,
                         String from, String to, String generatedAt, List<Column> columns,
                         List<List<Object>> rows, List<Fact> summary, List<String> notes, boolean truncated,
                         boolean totals) {
    }
}
