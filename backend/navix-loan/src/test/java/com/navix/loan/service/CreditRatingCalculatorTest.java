package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.navix.common.verification.BureauReportFacts;
import com.navix.common.verification.BureauTradeline;
import com.navix.loan.service.CreditRatingCalculator.Rating;
import com.navix.loan.service.CreditRatingCalculator.Signals;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies the BRE-aligned rating math (score bands, deductions, caps) and the worked sample → 4.0★. */
class CreditRatingCalculatorTest {

    private final CreditRatingCalculator calc = new CreditRatingCalculator();

    // computeStars(score, defaults, recentInquiries) — score-only, no bureau detail

    @Test
    void scoreBandsAnchoredOnTheBreFloor() {
        assertThat(calc.computeStars(800, 0, 0)).isEqualTo(5.0);
        assertThat(calc.computeStars(780, 0, 0)).isEqualTo(5.0);
        assertThat(calc.computeStars(779, 0, 0)).isEqualTo(4.5);
        assertThat(calc.computeStars(750, 0, 0)).isEqualTo(4.5);
        assertThat(calc.computeStars(749, 0, 0)).isEqualTo(4.0);
        assertThat(calc.computeStars(720, 0, 0)).isEqualTo(4.0);
        assertThat(calc.computeStars(719, 0, 0)).isEqualTo(3.5);
        assertThat(calc.computeStars(690, 0, 0)).isEqualTo(3.5);
        assertThat(calc.computeStars(689, 0, 0)).isEqualTo(3.0);
        assertThat(calc.computeStars(660, 0, 0)).isEqualTo(3.0);
        assertThat(calc.computeStars(659, 0, 0)).isEqualTo(2.5);
        assertThat(calc.computeStars(631, 0, 0)).isEqualTo(2.5);
        assertThat(calc.computeStars(630, 0, 0)).isEqualTo(2.0);
        assertThat(calc.computeStars(601, 0, 0)).isEqualTo(2.0);
        // BRE rule 1 is "more than 600": 600 itself fails.
        assertThat(calc.computeStars(600, 0, 0)).isEqualTo(1.0);
        assertThat(calc.computeStars(450, 0, 0)).isEqualTo(1.0);
    }

    @Test
    void aBreFailingScoreNeverReadsAsARecommendation() {
        for (int score = 300; score <= 600; score += 25) {
            assertThat(calc.verdict(calc.computeStars(score, 0, 0))).isEqualTo("NOT RECOMMENDED");
        }
    }

    @Test
    void enquiryBurst() {
        assertThat(calc.computeStars(760, 0, 4)).isEqualTo(4.0);
        assertThat(calc.computeStars(760, 0, 3)).isEqualTo(4.5);
    }

    @Test
    void defaultsCapAt2_5() {
        assertThat(calc.computeStars(800, 2, 0)).isEqualTo(2.5);
        assertThat(calc.computeStars(800, 1, 5)).isEqualTo(2.5);
        // the cap never raises a lower file
        assertThat(calc.computeStars(610, 3, 0)).isEqualTo(2.0);
    }

    @Test
    void breDeductionsAndCaps() {
        List<String> reasons = new ArrayList<>();
        // 785 = 5.0, DPD 45 in 12m −1.0, 1 overdue −0.5 → 3.5
        assertThat(calc.computeStars(785, 0, 0, new Signals(45, 1, 0, true), reasons)).isEqualTo(3.5);
        assertThat(reasons).containsExactly("Score 785 → 5.0★",
                "45 days past due in the last 12 months (over 10) −1.0",
                "1 account overdue right now −0.5");

        // DPD of exactly 10 is inside BRE rule 4
        assertThat(calc.computeStars(785, 0, 0, new Signals(10, 0, 0, true), new ArrayList<>())).isEqualTo(5.0);
        // no personal loan in the window −0.5
        assertThat(calc.computeStars(785, 0, 0, new Signals(0, 0, 0, false), new ArrayList<>())).isEqualTo(4.5);
        // written-off / settled caps at 2.0
        assertThat(calc.computeStars(785, 0, 0, new Signals(0, 0, 1, true), new ArrayList<>())).isEqualTo(2.0);
        // everything at once still floors at 1.0
        assertThat(calc.computeStars(640, 2, 9, new Signals(90, 3, 2, false), new ArrayList<>())).isEqualTo(1.0);
    }

    @Test
    void missingFactsAreNeverCountedAgainstTheBorrower() {
        assertThat(calc.computeStars(785, 0, 0, Signals.NONE, new ArrayList<>())).isEqualTo(5.0);
    }

    @Test
    void personalLoanWindow() {
        LocalDate asOf = LocalDate.of(2026, 10, 1);
        assertThat(CreditRatingCalculator.personalLoanInWindow(List.of(), asOf)).isNull();
        // open, Experian code 5
        assertThat(CreditRatingCalculator.personalLoanInWindow(List.of(tl("5", "2025-01-01", null)), asOf)).isTrue();
        // closed inside the 9 months
        assertThat(CreditRatingCalculator.personalLoanInWindow(List.of(tl("Personal Loan", "2024-01-01", "2026-02-01")), asOf)).isTrue();
        // closed before the window
        assertThat(CreditRatingCalculator.personalLoanInWindow(List.of(tl("Personal Loan", "2023-01-01", "2025-11-30")), asOf)).isFalse();
        // not a personal loan
        assertThat(CreditRatingCalculator.personalLoanInWindow(List.of(tl("Auto Loan (Personal)", "2025-01-01", null)), asOf)).isFalse();
    }

    @Test
    void workedSample_kartikJindal() {
        // 778 → 4.5, 5 enquiries −0.5 → 4.0 (no detail, so nothing else applies)
        Rating rating = calc.rate(sample());
        assertThat(rating.stars()).isEqualTo(4.0);
        assertThat(rating.recommendation()).isEqualTo("RECOMMEND");
        assertThat(rating.summary()).contains("778").contains("Kartik Jindal").contains("5 enquiries");
        assertThat(rating.reasons()).containsExactly("Score 778 → 4.5★", "5 enquiries in the last 30 days −0.5");
    }

    private static BureauTradeline tl(String type, String opened, String closed) {
        return new BureauTradeline("LENDER", "XX1", type, null, null, opened, closed,
                0L, 0L, null, null, null, null, null);
    }

    @Test
    void verdictBands() {
        assertThat(calc.verdict(5.0)).isEqualTo("STRONGLY RECOMMEND");
        assertThat(calc.verdict(4.5)).isEqualTo("STRONGLY RECOMMEND");
        assertThat(calc.verdict(4.0)).isEqualTo("RECOMMEND");
        assertThat(calc.verdict(3.5)).isEqualTo("RECOMMEND");
        assertThat(calc.verdict(3.0)).isEqualTo("REFER — MANUAL REVIEW");
        assertThat(calc.verdict(2.0)).isEqualTo("NOT RECOMMENDED");
    }

    /**
     * A report the bureau returned WITHOUT a usable score must not be rated at all.
     *
     * <p>This is the trap the out-of-band-score fix walks into. CRIF answers some applications with a
     * SCORE-VALUE outside 300-900 — 47 of them in the September 2026 pending queue — which
     * {@code plausibleScore} correctly nulls while keeping a report full of real tradelines. Left to
     * the band logic, {@code nz()} would turn that null into 0, drop it into the "&lt; 650" band and
     * stamp a fabricated 2.0★ NOT RECOMMENDED on a perfectly readable file. Nothing about the
     * borrower earned that verdict.
     */
    @Test
    void aReportWithNoUsableScoreIsNotRated() {
        Rating rating = calc.rate(unscored());

        assertThat(rating.rated()).isFalse();
        assertThat(rating.stars()).isEqualTo(0.0);
        // The summary must not quote a score or a verdict — summarize() hardcodes "(bureau score N)"
        // and closes on a recommendation sentence, and with no score both would be fiction.
        assertThat(rating.summary()).doesNotContain("bureau score");
        assertThat(rating.summary()).doesNotContain("not recommended");
        assertThat(rating.summary()).contains("no usable credit score");
    }

    /** A scored report still rates exactly as before — the branch above must not leak. */
    @Test
    void aScoredReportIsStillRated() {
        assertThat(calc.rate(sample()).rated()).isTrue();
    }

    /** {@link #sample()} with the score removed; everything else about the file is unchanged. */
    static BureauReportFacts unscored() {
        return new BureauReportFacts(
                "KARTIK JINDAL", "BXFPJ0767C", "95880784XX", "1985-07-10", "Mumbai", "400001",
                null, 11, 9, 2, 0,
                861232L, 712212L, 149020L, 5, "1782599074402");
    }

    static BureauReportFacts sample() {
        return new BureauReportFacts(
                "KARTIK JINDAL", "BXFPJ0767C", "95880784XX", "1985-07-10", "Mumbai", "400001",
                778, 11, 9, 2, 0,
                861232L, 712212L, 149020L, 5, "1782599074402");
    }
}
