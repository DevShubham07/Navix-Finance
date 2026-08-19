package com.navix.loan.service;

import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the free-form {@code application_event.notes} string into typed fields.
 *
 * <p>The notes column is a grab-bag: some writers pack machine key/values into it
 * ({@code amountPaise=500000 salaryCreditDay=1 projectedRepaymentDate=2026-09-01}), some write a
 * staffer's free text ({@code Credit Score Issue}), and the disbursement path writes a
 * frontend-formatted {@code Txn/ref: XEXK190826000001}. Rendering that raw put machine payloads in
 * front of operators, so it is parsed here — once, server-side — and surfaced as real columns.
 *
 * <p>Historical rows are already stored in these shapes, so parsing is unavoidable; it cannot be
 * fixed by only changing what new writes look like.
 *
 * <p><b>Deliberately action-agnostic.</b> This never branches on the event's {@code action}: a
 * historical row may carry a format its action no longer writes, and keying on the action would
 * silently drop it. The shape of the string is the only input.
 *
 * <p><b>No data is ever lost.</b> Anything not recognised as a known key/value falls through to
 * {@link Parsed#remark()} verbatim, including values that fail to convert.
 */
public final class DecisionNotes {

    private DecisionNotes() {
    }

    /** The frontend writes this prefix when the Disbursement Head confirms a transfer. */
    private static final Pattern TXN_REF = Pattern.compile("Txn/ref:\\s*(.+)", Pattern.CASE_INSENSITIVE);

    /** {@code key=value} pairs; values are non-space runs, so a remark can never be swallowed. */
    private static final Pattern KEY_VALUE = Pattern.compile("(\\w+)=(\\S+)");

    /** Separator the SANCTION writer puts between its key/values and the staffer's remark. */
    private static final String REMARK_SEPARATOR = " — ";

    private static final Parsed EMPTY = new Parsed(null, null, null, null, null, null);

    /**
     * @param amountPaise     sanctioned amount, integer paise
     * @param salaryCreditDay day-of-month the borrower's salary lands
     * @param repaymentDate   the projected repayment date carried on the sanction
     * @param assigneeId      staff id an application was assigned to
     * @param txnRef          the outgoing transfer's transaction reference
     * @param remark          whatever a human actually typed — never null-ed to hide unparsed text
     */
    public record Parsed(Long amountPaise, Integer salaryCreditDay, LocalDate repaymentDate,
                         Long assigneeId, String txnRef, String remark) {
    }

    /**
     * Parse {@code notes}. Never throws and never returns null; an unrecognised string comes back
     * whole in {@link Parsed#remark()}.
     */
    public static Parsed parse(String notes) {
        if (notes == null || notes.isBlank()) {
            return EMPTY;
        }
        String working = notes.trim();

        // 1. Txn ref first — it is a whole-string format, and pulling it out leaves the (usually
        //    empty) text before it as the working string rather than misreading it as a remark.
        String txnRef = null;
        Matcher txn = TXN_REF.matcher(working);
        if (txn.find()) {
            txnRef = txn.group(1).trim();
            working = working.substring(0, txn.start()).trim();
        }
        if (working.isEmpty()) {
            return new Parsed(null, null, null, null, txnRef, null);
        }

        // 2. Split on the FIRST em-dash separator: head may hold key/values, tail is the remark.
        int sep = working.indexOf(REMARK_SEPARATOR);
        String head = sep >= 0 ? working.substring(0, sep).trim() : working;
        String tail = sep >= 0 ? working.substring(sep + REMARK_SEPARATOR.length()).trim() : null;

        // 3. Tokenise the head only — the tail is a human sentence and may legitimately contain
        //    an '=' sign, which must not be read as a key/value pair.
        Long amountPaise = null;
        Integer salaryCreditDay = null;
        LocalDate repaymentDate = null;
        Long assigneeId = null;
        boolean sawKnownKey = false;
        boolean malformed = false;

        Matcher kv = KEY_VALUE.matcher(head);
        while (kv.find()) {
            String key = kv.group(1);
            String value = kv.group(2);
            switch (key) {
                case "amountPaise" -> {
                    sawKnownKey = true;
                    try {
                        amountPaise = Long.valueOf(value);
                    } catch (NumberFormatException e) {
                        malformed = true;
                    }
                }
                case "salaryCreditDay" -> {
                    sawKnownKey = true;
                    try {
                        salaryCreditDay = Integer.valueOf(value);
                    } catch (NumberFormatException e) {
                        malformed = true;
                    }
                }
                case "projectedRepaymentDate" -> {
                    sawKnownKey = true;
                    try {
                        repaymentDate = LocalDate.parse(value);
                    } catch (java.time.format.DateTimeParseException e) {
                        malformed = true;
                    }
                }
                // ASSIGN writes executiveId; REASSIGN writes previousExecutiveId + newExecutiveId.
                // The NEW assignee is the one worth showing — previousExecutiveId is deliberately
                // ignored so a reassignment reads as "assigned to <whoever holds it now>".
                case "executiveId", "newExecutiveId" -> {
                    sawKnownKey = true;
                    try {
                        assigneeId = Long.valueOf(value);
                    } catch (NumberFormatException e) {
                        malformed = true;
                    }
                }
                default -> {
                    // Unknown key — ignored, not an error. Legacy and future formats stay readable
                    // via the remark fallback below.
                }
            }
        }

        // 4. No known key anywhere → the whole thing is free text. Return it UN-SPLIT so an em-dash
        //    inside a staffer's sentence survives verbatim.
        if (!sawKnownKey) {
            return new Parsed(null, null, null, null, txnRef, working);
        }

        // 5. A value failed to convert: keep the good fields AND hand the operator the raw string,
        //    so a bad date can never make an amount disappear without trace.
        String remark = malformed ? working : tail;
        return new Parsed(amountPaise, salaryCreditDay, repaymentDate, assigneeId, txnRef,
                remark == null || remark.isBlank() ? null : remark);
    }
}
