package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.navix.common.verification.BureauReportFacts;
import com.navix.loan.service.CreditRatingCalculator.Rating;
import org.junit.jupiter.api.Test;

/** Verifies the spec's rating math (bands, bonus, the two penalties) and the worked sample → 4.0★. */
class CreditRatingCalculatorTest {

    private final CreditRatingCalculator calc = new CreditRatingCalculator();

    // computeStars(score, defaults, totalBalance, recentInquiries)

    @Test
    void scoreBands() {
        assertThat(calc.computeStars(800, 0, 100, 0)).isEqualTo(4.5); // >750 (bonus also 4.5)
        assertThat(calc.computeStars(720, 0, 100, 0)).isEqualTo(4.0); // 700–750
        assertThat(calc.computeStars(680, 0, 100, 0)).isEqualTo(3.0); // 650–699
        assertThat(calc.computeStars(600, 0, 100, 0)).isEqualTo(2.0); // <650
    }

    @Test
    void bonusForStrongCleanFile() {
        // >770 & 0 defaults → 4.5; and balance == 0 → 5.0
        assertThat(calc.computeStars(800, 0, 0, 0)).isEqualTo(5.0);
        assertThat(calc.computeStars(800, 0, 5000, 0)).isEqualTo(4.5);
        // not above 770 → no bonus, stays on the base band
        assertThat(calc.computeStars(760, 0, 0, 0)).isEqualTo(4.5); // 760>750 base 4.5 anyway
        assertThat(calc.computeStars(755, 0, 0, 0)).isEqualTo(4.5);
    }

    @Test
    void penalty2_recentInquiries() {
        // 760 base 4.5, 4 inquiries (>3) → −0.5
        assertThat(calc.computeStars(760, 0, 5000, 4)).isEqualTo(4.0);
        // exactly 3 → no penalty
        assertThat(calc.computeStars(760, 0, 5000, 3)).isEqualTo(4.5);
    }

    @Test
    void penalty1_defaultsCapAt2_5() {
        // any default caps at 2.5 regardless of an otherwise-strong score
        assertThat(calc.computeStars(800, 2, 5000, 0)).isEqualTo(2.5);
        // the cap is a ceiling applied last, so it also bounds a file that took the inquiry penalty
        assertThat(calc.computeStars(800, 1, 5000, 5)).isEqualTo(2.5);
        // a weak score with defaults stays at its lower base (the min() never raises it to 2.5)
        assertThat(calc.computeStars(600, 3, 5000, 0)).isEqualTo(2.0);
    }

    @Test
    void workedSample_kartikJindal_is4Stars() {
        BureauReportFacts f = sample();
        // 778 base 4.5 → bonus 4.5 (balance≠0) → −0.5 (5 inquiries) → no cap → 4.0
        assertThat(calc.computeStars(778, 0, 861232L, 5)).isEqualTo(4.0);

        Rating rating = calc.rate(f);
        assertThat(rating.stars()).isEqualTo(4.0);
        assertThat(rating.recommendation()).isEqualTo("RECOMMEND");
        assertThat(rating.summary()).contains("778").contains("Kartik Jindal");
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

    static BureauReportFacts sample() {
        return new BureauReportFacts(
                "KARTIK JINDAL", "BXFPJ0767C", "95880784XX", "1985-07-10", "Mumbai", "400001",
                778, 11, 9, 2, 0,
                861232L, 712212L, 149020L, 5, "1782599074402");
    }
}
