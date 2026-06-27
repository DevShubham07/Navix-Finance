package com.navix.income.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.navix.common.risk.RiskPort;
import com.navix.income.repository.IncomeProfileRepository;
import com.navix.income.repository.RiskAssessmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link RiskAdapter}: it must expose the {@link LimitCalculator} 25%-cap verbatim
 * and grade representative borrowers into A/B/C/D. The adapter only calls the stateless
 * {@code RiskScoringService} helpers + {@code LimitCalculator}, so the repositories are never hit.
 */
@ExtendWith(MockitoExtension.class)
class RiskAdapterTest {

    @Mock
    private IncomeProfileRepository incomeProfileRepository;
    @Mock
    private RiskAssessmentRepository riskAssessmentRepository;

    private final LimitCalculator reference = new LimitCalculator();
    private RiskAdapter adapter;

    @BeforeEach
    void setUp() {
        LimitCalculator limitCalculator = new LimitCalculator();
        RiskScoringService scoring = new RiskScoringService(
                incomeProfileRepository, riskAssessmentRepository, limitCalculator);
        adapter = new RiskAdapter(limitCalculator, scoring);
    }

    @Test
    void eligibleLimit_matchesLimitCalculatorAcrossSweep() {
        long[] salaries = {0L, 999_99L, 4_000_000L, 12_345_678L};
        for (long salary : salaries) {
            assertThat(adapter.eligibleLimitPaise(salary))
                    .as("salary=%d", salary)
                    .isEqualTo(reference.eligibleLimitPaise(salary));
        }
    }

    @Test
    void grade_eligibleLimit_matchesLimitCalculator() {
        long salary = 4_000_000L;
        RiskPort.RiskGrade grade = adapter.grade(salary, 750, 12);

        assertThat(grade.eligibleLimitPaise()).isEqualTo(reference.eligibleLimitPaise(salary));
    }

    @Test
    void grade_categorisesRepresentativeBorrowers() {
        // Strong: high salary, high bureau, long tenure → A.
        assertThat(adapter.grade(10_000_000L, 850, 24).category()).isEqualTo("A");
        // Good: mid salary/bureau/tenure → B.
        assertThat(adapter.grade(4_000_000L, 750, 12).category()).isEqualTo("B");
        // Elevated → C.
        assertThat(adapter.grade(2_000_000L, 650, 6).category()).isEqualTo("C");
        // Thin/weak profile → D.
        assertThat(adapter.grade(500_000L, 300, 0).category()).isEqualTo("D");
    }

    @Test
    void grade_nullBureauAndTenure_usesSensibleDefaults() {
        RiskPort.RiskGrade grade = adapter.grade(4_000_000L, null, null);

        assertThat(grade.category()).isIn("A", "B", "C", "D");
        assertThat(grade.score()).isBetween(0, 100);
        assertThat(grade.eligibleLimitPaise()).isEqualTo(reference.eligibleLimitPaise(4_000_000L));
    }
}
