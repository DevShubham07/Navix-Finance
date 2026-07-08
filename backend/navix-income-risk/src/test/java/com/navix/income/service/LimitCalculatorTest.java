package com.navix.income.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.navix.income.domain.RiskCategory;
import org.junit.jupiter.api.Test;

class LimitCalculatorTest {

    private final LimitCalculator calculator = new LimitCalculator();

    @Test
    void eligibleLimitIsQuarterOfSalaryFlooredToHundredRupees() {
        // ₹50,000 → 25% = ₹12,500
        assertThat(calculator.eligibleLimitPaise(5_000_000L)).isEqualTo(1_250_000L);
        // ₹41,234 → 25% = ₹10,308.50 → floored to nearest ₹100 = ₹10,300
        assertThat(calculator.eligibleLimitPaise(4_123_400L)).isEqualTo(1_030_000L);
    }

    @Test
    void eligibleLimitCappedAtInstantCeiling() {
        // ₹42,00,000 salary → 25% = ₹10,50,000 > ceiling → capped at ₹10,00,000
        assertThat(calculator.eligibleLimitPaise(420_000_000L))
                .isEqualTo(LimitCalculator.MAX_INSTANT_LOAN_PAISE);
    }

    @Test
    void zeroSalaryIsZeroLimit() {
        assertThat(calculator.eligibleLimitPaise(0L)).isZero();
    }

    @Test
    void categoryScalesRelativeToTheQuarterBase() {
        long salary = 5_000_000L; // ₹50,000 → 25% base = ₹12,500 (1_250_000 paise)
        // A keeps the full 25% base; B/C are progressively reduced; D is declined (0).
        assertThat(calculator.limitForCategory(salary, RiskCategory.A)).isEqualTo(1_250_000L); // ×1.00
        assertThat(calculator.limitForCategory(salary, RiskCategory.B)).isEqualTo(1_000_000L); // ×0.80 → ₹10,000
        assertThat(calculator.limitForCategory(salary, RiskCategory.C)).isEqualTo(620_000L);   // ×0.50 → ₹6,250 floored ₹6,200
        assertThat(calculator.limitForCategory(salary, RiskCategory.D)).isZero();              // declined
    }
}
