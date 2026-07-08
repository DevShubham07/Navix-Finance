package com.navix.income.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.navix.income.domain.RiskCategory;
import org.junit.jupiter.api.Test;

class LimitCalculatorTest {

    private final LimitCalculator calculator = new LimitCalculator();

    @Test
    void eligibleLimitIsFlatInstantCapRegardlessOfSalary() {
        long flat = LimitCalculator.MAX_INSTANT_LOAN_PAISE; // ₹10,00,000
        assertThat(calculator.eligibleLimitPaise(4_000_000L)).isEqualTo(flat);
        assertThat(calculator.eligibleLimitPaise(5_000_000L)).isEqualTo(flat);
        assertThat(calculator.eligibleLimitPaise(50_000_000L)).isEqualTo(flat);
    }

    @Test
    void categoryDoesNotReduceTheFlatCap() {
        long salary = 4_000_000L;
        long flat = LimitCalculator.MAX_INSTANT_LOAN_PAISE;
        assertThat(calculator.limitForCategory(salary, RiskCategory.A)).isEqualTo(flat);
        assertThat(calculator.limitForCategory(salary, RiskCategory.B)).isEqualTo(flat);
        assertThat(calculator.limitForCategory(salary, RiskCategory.C)).isEqualTo(flat);
        assertThat(calculator.limitForCategory(salary, RiskCategory.D)).isEqualTo(flat);
    }
}
