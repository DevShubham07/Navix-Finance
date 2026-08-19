package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.navix.loan.service.DecisionNotes.Parsed;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The notes column is parsed, not re-written, so these cases pin the EXACT strings
 * {@code ApplicationFlowService} has written historically. Changing a format string there without
 * adding a case here silently regresses every existing row on /staff/my-decisions.
 */
class DecisionNotesTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("null/blank notes yield an all-null result, never an exception")
    void blank(String notes) {
        Parsed p = DecisionNotes.parse(notes);
        assertThat(p.amountPaise()).isNull();
        assertThat(p.salaryCreditDay()).isNull();
        assertThat(p.repaymentDate()).isNull();
        assertThat(p.assigneeId()).isNull();
        assertThat(p.txnRef()).isNull();
        assertThat(p.remark()).isNull();
    }

    @Test
    @DisplayName("SANCTION: the full key/value triple, no remark")
    void sanctionTriple() {
        Parsed p = DecisionNotes.parse(
                "amountPaise=500000 salaryCreditDay=1 projectedRepaymentDate=2026-09-01");
        assertThat(p.amountPaise()).isEqualTo(500_000L);
        assertThat(p.salaryCreditDay()).isEqualTo(1);
        assertThat(p.repaymentDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(p.remark()).isNull();
    }

    @Test
    @DisplayName("SANCTION: key/values plus the em-dash remark the staffer typed")
    void sanctionWithRemark() {
        Parsed p = DecisionNotes.parse(
                "amountPaise=500000 salaryCreditDay=1 projectedRepaymentDate=2026-09-01"
                        + " — kindly share the bank statement password");
        assertThat(p.amountPaise()).isEqualTo(500_000L);
        assertThat(p.salaryCreditDay()).isEqualTo(1);
        assertThat(p.repaymentDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(p.remark()).isEqualTo("kindly share the bank statement password");
    }

    @Test
    @DisplayName("ASSIGN: executiveId resolves to the assignee, nothing else set")
    void assign() {
        Parsed p = DecisionNotes.parse("executiveId=12");
        assertThat(p.assigneeId()).isEqualTo(12L);
        assertThat(p.amountPaise()).isNull();
        assertThat(p.remark()).isNull();
    }

    @Test
    @DisplayName("REASSIGN: the NEW assignee wins; the previous holder is ignored")
    void reassignPrefersNewExecutive() {
        Parsed p = DecisionNotes.parse("previousExecutiveId=4 newExecutiveId=9");
        assertThat(p.assigneeId()).isEqualTo(9L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Credit Score Issue", "myself"})
    @DisplayName("free text (REJECT_LEAD / MARK_PENDING) lands whole in the remark")
    void freeText(String notes) {
        Parsed p = DecisionNotes.parse(notes);
        assertThat(p.remark()).isEqualTo(notes);
        assertThat(p.amountPaise()).isNull();
        assertThat(p.assigneeId()).isNull();
        assertThat(p.txnRef()).isNull();
    }

    @Test
    @DisplayName("VALIDATE_SUCCESS: the frontend-written Txn/ref prefix is extracted")
    void txnRef() {
        Parsed p = DecisionNotes.parse("Txn/ref: XEXK190826000001");
        assertThat(p.txnRef()).isEqualTo("XEXK190826000001");
        assertThat(p.remark()).isNull();
    }

    @Test
    @DisplayName("free text containing an em-dash is NOT split — the sentence survives verbatim")
    void freeTextWithEmDashIsNotSplit() {
        Parsed p = DecisionNotes.parse("salary slip missing — will call back");
        assertThat(p.remark()).isEqualTo("salary slip missing — will call back");
    }

    @Test
    @DisplayName("a malformed value degrades: good fields survive, the raw string reaches the operator")
    void malformedValueKeepsRawString() {
        Parsed p = DecisionNotes.parse("amountPaise=notanumber salaryCreditDay=5");
        assertThat(p.amountPaise()).isNull();
        assertThat(p.salaryCreditDay()).isEqualTo(5);
        assertThat(p.remark()).isEqualTo("amountPaise=notanumber salaryCreditDay=5");
    }

    @Test
    @DisplayName("an unparseable date does not void a good amount")
    void badDateDoesNotVoidAmount() {
        Parsed p = DecisionNotes.parse("amountPaise=500000 projectedRepaymentDate=31-09-2026");
        assertThat(p.amountPaise()).isEqualTo(500_000L);
        assertThat(p.repaymentDate()).isNull();
        assertThat(p.remark()).contains("31-09-2026");
    }

    @Test
    @DisplayName("the remark tail is never tokenised, so an '=' inside it is preserved")
    void remarkTailIsNotTokenised() {
        Parsed p = DecisionNotes.parse("amountPaise=500000 — net = 4.4L after fees");
        assertThat(p.amountPaise()).isEqualTo(500_000L);
        assertThat(p.remark()).isEqualTo("net = 4.4L after fees");
    }

    @Test
    @DisplayName("unknown keys are ignored rather than treated as an error")
    void unknownKeysIgnored() {
        Parsed p = DecisionNotes.parse("amountPaise=500000 somethingNew=42");
        assertThat(p.amountPaise()).isEqualTo(500_000L);
        assertThat(p.remark()).isNull();
    }

    @Test
    @DisplayName("a legacy format with no known key is preserved rather than dropped")
    void unknownFormatPreserved() {
        Parsed p = DecisionNotes.parse("loanId=77");
        assertThat(p.remark()).isEqualTo("loanId=77");
    }
}
