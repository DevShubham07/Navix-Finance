package com.navix.loan.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.navix.loan.dto.AnalyzerDtos.AnalyzerView;
import com.navix.loan.dto.AnalyzerDtos.Benchmarks;
import com.navix.loan.dto.AnalyzerDtos.CustomerFacts;
import com.navix.loan.dto.AnalyzerDtos.PointRow;
import com.navix.loan.dto.AnalyzerDtos.Pointer;
import com.navix.loan.service.BorrowerAnalyzerService.Inputs;
import com.navix.loan.service.BorrowerAnalyzerService.LoanRow;
import com.navix.loan.service.BorrowerAnalyzerService.Outcome;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BorrowerAnalyzerServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 9);
    private static final long TOTAL = 1_270_000L;
    private static long nextId = 1;

    private static LocalDate d(String s) {
        return LocalDate.parse(s);
    }

    /** A loan with no concerns: 5,000 rupees, 20 days, 40 accounts, job confirmed. */
    private static LoanRow loan(long cust, String disbursed, String due, String closed, long paid) {
        return new LoanRow(nextId++, cust, 500_000, 441_000, TOTAL, closed == null ? "ACTIVE" : "CLOSED",
                d(disbursed), d(due), closed == null ? null : d(closed), paid, false,
                null, 40, true, null, null, null, null);
    }

    private static LoanRow with(LoanRow r, long principal, Integer accounts, Boolean employed, Long salary) {
        return new LoanRow(r.loanId(), r.customerId(), principal, r.netDisbursed(), r.totalRepayable(), r.status(),
                r.disbursedOn(), r.dueDate(), r.closedOn(), r.paid(), r.settled(), salary, accounts, employed,
                r.score(), r.stars(), r.recommendation(), r.riskCategory());
    }

    private static LoanRow settled(LoanRow r) {
        return new LoanRow(r.loanId(), r.customerId(), r.principal(), r.netDisbursed(), r.totalRepayable(),
                r.status(), r.disbursedOn(), r.dueDate(), r.closedOn(), r.paid(), true, r.salaryPaise(),
                r.totalAccounts(), r.employedAtDeclared(), r.score(), r.stars(), r.recommendation(),
                r.riskCategory());
    }

    private static Outcome outcome(LoanRow r) {
        return BorrowerAnalyzerService.outcome(r, TODAY);
    }

    private static Pointer concern(Benchmarks b, String key) {
        return b.concerns().stream().filter(p -> p.key().equals(key)).findFirst().orElseThrow();
    }

    private static int pattern(Benchmarks b, String key) {
        return b.patterns().stream().filter(p -> p.pattern().equals(key)).findFirst().orElseThrow().customers();
    }

    @Test
    void outcomeBoundaries() {
        assertEquals(Outcome.GOOD, outcome(loan(1, "2026-09-10", "2026-10-01", "2026-10-06", TOTAL)));
        assertEquals(Outcome.BAD, outcome(loan(1, "2026-09-10", "2026-10-01", "2026-10-07", TOTAL)));
        assertEquals(Outcome.GOOD, outcome(loan(1, "2026-09-10", "2026-10-01", "2026-09-20", TOTAL)));
        // open: 5 days past due is still unresolved, 6 is bad
        assertEquals(Outcome.UNRESOLVED, outcome(loan(1, "2026-10-10", "2026-11-04", null, 0)));
        assertEquals(Outcome.BAD, outcome(loan(1, "2026-10-10", "2026-11-03", null, 0)));
        // settled short is bad even when it closed on time
        assertEquals(Outcome.BAD, outcome(settled(loan(1, "2026-09-10", "2026-10-01", "2026-10-01", 600_000))));
        // settled but paid in full: judged on lateness alone
        assertEquals(Outcome.GOOD, outcome(settled(loan(1, "2026-09-10", "2026-10-01", "2026-10-03", TOTAL))));
        assertEquals(Outcome.BAD, outcome(settled(loan(1, "2026-09-10", "2026-10-01", "2026-10-09", TOTAL))));
    }

    @Test
    void dueDateWindowExcludesRecentlyDueLoans() {
        List<LoanRow> rows = List.of(
                loan(1, "2026-10-10", "2026-11-05", "2026-10-30", TOTAL), // due 4 days ago, closed early: out
                loan(2, "2026-10-10", "2026-11-03", "2026-10-30", TOTAL)); // due 6 days ago: in
        Benchmarks b = BorrowerAnalyzerService.benchmarks(rows, TODAY);
        assertEquals(1, b.all().loans());
        assertEquals(1, b.recentFirst().loans());
        assertEquals(0, b.earlyFirst().loans());
    }

    @Test
    void excludedTestCustomerDoesNotMoveBenchmarks() {
        List<LoanRow> base = new ArrayList<>(List.of(
                loan(1, "2026-09-10", "2026-10-01", "2026-10-01", TOTAL),
                loan(2, "2026-09-10", "2026-10-01", "2026-10-20", 0)));
        Benchmarks before = BorrowerAnalyzerService.benchmarks(base, TODAY);
        base.add(loan(5914823L, "2026-09-10", "2026-10-01", "2026-10-25", 0));
        base.add(loan(5914823L, "2026-09-20", "2026-10-10", "2026-10-10", TOTAL));
        assertEquals(before, BorrowerAnalyzerService.benchmarks(base, TODAY));
        assertEquals(2, before.all().loans());
        assertEquals(1, before.all().bad());
    }

    @Test
    void pointsBandsAndMissingAccounts() {
        LoanRow a = loan(1, "2026-09-10", "2026-09-30", "2026-09-30", TOTAL); // 0 points, good
        LoanRow b = with(loan(2, "2026-09-01", "2026-10-01", "2026-10-10", 600_000), 800_000, 10, true, null); // 3, bad
        LoanRow c = with(loan(3, "2026-09-10", "2026-09-30", "2026-09-30", TOTAL), 500_000, 10, false, null); // 2, good
        LoanRow dd = with(loan(4, "2026-09-10", "2026-09-30", "2026-09-30", TOTAL), 500_000, null, null, null); // 0, job check missing
        LoanRow e = with(loan(5, "2026-09-01", "2026-10-01", "2026-10-20", 0), 800_000, 10, false, null); // 4, bad
        Benchmarks bm = BorrowerAnalyzerService.benchmarks(List.of(a, b, c, dd, e), TODAY);

        List<PointRow> t = bm.pointTable();
        assertEquals(new PointRow(0, 2, 0, 0), t.get(0));
        assertEquals(new PointRow(1, 0, 0, 0), t.get(1));
        assertEquals(new PointRow(2, 1, 0, 0), t.get(2));
        assertEquals(new PointRow(3, 1, 1, 0), t.get(3));
        assertEquals(new PointRow(4, 1, 1, 1), t.get(4));
        assertEquals(2, bm.bands().get(0).loans());
        assertEquals(1, bm.bands().get(1).loans());
        assertEquals(2, bm.bands().get(2).loans());
        assertEquals(2, bm.bands().get(2).bad());

        // dd has no totalAccounts: in neither THIN_RECORD group (a is the only "without")
        Pointer thin = concern(bm, "THIN_RECORD");
        assertEquals(3, thin.withLoans());
        assertEquals(2, thin.withBad());
        assertEquals(1, thin.withoutLoans());
        assertEquals(0, thin.withoutBad());
        // a missing job check counts as "without": a, b, dd have true/true/true here
        Pointer job = concern(bm, "JOB_NOT_CONFIRMED");
        assertEquals(2, job.withLoans());
        assertEquals(3, job.withoutLoans());

        assertEquals("LOWER", BorrowerAnalyzerService.bandOf(0));
        assertEquals("LOWER", BorrowerAnalyzerService.bandOf(1));
        assertEquals("WATCH", BorrowerAnalyzerService.bandOf(2));
        assertEquals("HIGH", BorrowerAnalyzerService.bandOf(3));
        assertEquals("HIGH", BorrowerAnalyzerService.bandOf(4));
    }

    @Test
    void customerPatterns() {
        List<LoanRow> rows = List.of(
                loan(1, "2026-10-10", "2026-11-07", "2026-11-01", TOTAL), // good, due inside the 6-day window
                loan(2, "2026-08-01", "2026-08-20", "2026-08-20", TOTAL),
                loan(2, "2026-09-01", "2026-09-20", "2026-10-10", 600_000),
                loan(3, "2026-09-01", "2026-09-20", "2026-10-10", 0),
                loan(4, "2026-09-01", "2026-09-20", "2026-10-10", 500_000),
                loan(5, "2026-11-01", "2026-11-25", null, 0));
        Benchmarks b = BorrowerAnalyzerService.benchmarks(rows, TODAY);
        assertEquals(1, pattern(b, "ALWAYS_PAID"));
        assertEquals(1, pattern(b, "PAID_THEN_OVERDUE"));
        assertEquals(1, pattern(b, "NEVER_PAID"));
        assertEquals(1, pattern(b, "FIRST_OVERDUE_SOME_PAID"));
        assertEquals(1, pattern(b, "TOO_EARLY"));
    }

    private static final CustomerFacts NO_FACTS = new CustomerFacts(null, null, null, null, null, null, null);

    private static AnalyzerView own(LoanRow... rows) {
        return BorrowerAnalyzerService.view(List.of(rows), new Inputs(true, null, null, null, NO_FACTS), 7, TODAY);
    }

    @Test
    void ownRecordBand() {
        AnalyzerView high = own(loan(7, "2026-09-01", "2026-09-20", "2026-10-10", 600_000));
        assertEquals("OWN_RECORD", high.mode());
        assertEquals("HIGH", high.band());
        assertEquals(1, high.ownRecord().bad());

        AnalyzerView watchClosed = own(loan(7, "2026-09-01", "2026-09-20", "2026-09-23", TOTAL));
        assertEquals("WATCH", watchClosed.band());
        assertEquals(1, watchClosed.ownRecord().late1to5());

        AnalyzerView watchOpen = own(
                loan(7, "2026-08-01", "2026-08-20", "2026-08-10", TOTAL),
                loan(7, "2026-10-10", "2026-11-06", null, 0)); // 3 days past due
        assertEquals("WATCH", watchOpen.band());
        assertEquals(3, watchOpen.ownRecord().maxOpenDpd());
        assertEquals(10, watchOpen.ownRecord().lastClosedDaysEarly());

        AnalyzerView lower = own(loan(7, "2026-09-01", "2026-09-20", "2026-09-15", TOTAL));
        assertEquals("LOWER", lower.band());
        assertEquals(1, lower.ownRecord().onTime());
    }

    @Test
    void firstLoanAndNoData() {
        CustomerFacts facts = new CustomerFacts(6_000_000L, 10, false, null, null, null, null);
        Inputs in = new Inputs(true, 800_000L, null, TODAY.plusDays(30), facts);
        AnalyzerView v = BorrowerAnalyzerService.view(List.of(), in, 7, TODAY);
        assertEquals("FIRST_LOAN", v.mode());
        assertEquals(4, v.concernCount());
        assertEquals("HIGH", v.band());
        assertEquals(0, v.unknownCount());
        assertTrue(v.incomeUncorroborated());

        Inputs sparse = new Inputs(true, null, null, null, NO_FACTS);
        AnalyzerView s = BorrowerAnalyzerService.view(List.of(), sparse, 7, TODAY);
        assertEquals(4, s.unknownCount());
        assertEquals(0, s.concernCount());
        assertEquals("INCOMPLETE", s.band()); // nothing on file must never read as Lower

        assertEquals("LOWER", BorrowerAnalyzerService.firstLoanBand(0, 1)); // the one unknown cannot reach Watch
        assertEquals("INCOMPLETE", BorrowerAnalyzerService.firstLoanBand(1, 1));
        assertEquals("WATCH", BorrowerAnalyzerService.firstLoanBand(2, 2)); // concerns already found are a floor

        AnalyzerView none = BorrowerAnalyzerService.view(List.of(), new Inputs(false, null, null, null, NO_FACTS), 7, TODAY);
        assertEquals("NO_DATA", none.mode());
        assertNull(none.band());
    }

    @Test
    void bookMoneyIsNullForRolesWithoutTheMoneyView() {
        List<LoanRow> rows = List.of(loan(1, "2026-09-10", "2026-10-01", "2026-10-01", TOTAL));
        Inputs in = new Inputs(false, null, null, null,
                new CustomerFacts(null, null, null, null, null, null, null));
        AnalyzerView full = BorrowerAnalyzerService.view(rows, in, 1, TODAY);

        AnalyzerView admin = BorrowerAnalyzerService.forRole(full, "ADMIN");
        assertTrue(admin.benchmarks().all().principalPaise() != null && admin.benchmarks().all().principalPaise() > 0);

        AnalyzerView tele = BorrowerAnalyzerService.forRole(full, "TELECALLER");
        var b = tele.benchmarks();
        assertNull(b.all().principalPaise());
        assertNull(b.all().badPrincipalPaise());
        assertNull(b.repeat().paidOutPaise());
        assertNull(b.earlyFirst().collectedPaise());
        assertNull(b.breakEvenBps());
        assertEquals(full.benchmarks().all().loans(), b.all().loans());
    }
}
