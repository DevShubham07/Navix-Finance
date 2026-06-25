package com.navix.income.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.navix.income.domain.RiskCategory;
import org.junit.jupiter.api.Test;

class LimitCalculatorTest {

    private final LimitCalculator calculator = new LimitCalculator();

    @Test
    void eligibleLimitIsQuarterOfSalaryFlooredToHundred() {
        assertThat(calculator.eligibleLimitPaise(4_000_000L)).isEqualTo(1_000_000L); // ₹40k → ₹10k
        assertThat(calculator.eligibleLimitPaise(5_000_000L)).isEqualTo(1_250_000L); // ₹50k → ₹12.5k
        assertThat(calculator.eligibleLimitPaise(4_012_300L)).isEqualTo(1_000_000L); // floored to ₹100
    }

    @Test
    void categoryReducesButNeverExceedsCap() {
        long salary = 4_000_000L; // cap ₹10,000
        assertThat(calculator.limitForCategory(salary, RiskCategory.A)).isEqualTo(1_000_000L);
        assertThat(calculator.limitForCategory(salary, RiskCategory.B)).isEqualTo(800_000L);
        assertThat(calculator.limitForCategory(salary, RiskCategory.C)).isEqualTo(500_000L);
        assertThat(calculator.limitForCategory(salary, RiskCategory.D)).isZero();
    }
}
