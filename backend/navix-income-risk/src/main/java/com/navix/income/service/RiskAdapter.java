package com.navix.income.service;

import com.navix.common.risk.RiskPort;
import com.navix.income.domain.RiskCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Adapter exposing {@link LimitCalculator} + {@link RiskScoringService} as the
 * {@link RiskPort} consumed by the loan aggregate — making {@code navix-income-risk}
 * the single authority for the 25% cap and the A/B/C/D grade. Wired by component
 * scan from {@code navix-app}; adds no new Maven edge.
 */
@Component
@RequiredArgsConstructor
public class RiskAdapter implements RiskPort {

    private final LimitCalculator limitCalculator;
    private final RiskScoringService riskScoringService;

    @Override
    public long eligibleLimitPaise(long monthlySalaryPaise) {
        return limitCalculator.eligibleLimitPaise(monthlySalaryPaise);
    }

    @Override
    public RiskGrade grade(long monthlySalaryPaise, Integer bureauScore, Integer employmentMonths) {
        int bureau = bureauScore != null ? bureauScore : riskScoringService.defaultBureauScore();
        int score = riskScoringService.scoreFrom(monthlySalaryPaise, employmentMonths, bureau);
        RiskCategory category = riskScoringService.categoryForScore(score);
        long eligible = limitCalculator.eligibleLimitPaise(monthlySalaryPaise);
        long forCategory = limitCalculator.limitForCategory(monthlySalaryPaise, category);
        return new RiskGrade(category.name(), score, eligible, forCategory);
    }
}
