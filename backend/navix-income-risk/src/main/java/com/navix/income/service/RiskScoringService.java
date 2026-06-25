package com.navix.income.service;

import com.navix.common.exception.ResourceNotFoundException;
import com.navix.income.domain.RiskCategory;
import com.navix.income.entity.IncomeProfile;
import com.navix.income.entity.RiskAssessment;
import com.navix.income.repository.IncomeProfileRepository;
import com.navix.income.repository.RiskAssessmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes a borrower's {@link RiskCategory} and granted limit from available signals.
 *
 * <p>Demo model (deterministic, bureau mocked): a 0–100 score blends bureau strength, employment
 * tenure (UAN months) and salary band, then maps to a band — A≥75, B≥55, C≥35, else D. The category
 * drives the granted limit via {@link LimitCalculator} but never the pricing (fees/interest are flat).
 * At go-live the bureau/banking signals come from the verification module.
 */
@Service
@RequiredArgsConstructor
public class RiskScoringService {

    /** Default bureau score assumed when none is available (demo / no bureau pull yet). */
    static final int DEFAULT_BUREAU_SCORE = 700;

    private final IncomeProfileRepository incomeProfileRepository;
    private final RiskAssessmentRepository riskAssessmentRepository;
    private final LimitCalculator limitCalculator;

    /** Assess an applicant from their income profile, persisting and returning the assessment. */
    @Transactional
    public RiskAssessment assess(Long applicantId) {
        IncomeProfile profile = incomeProfileRepository.findByApplicantId(applicantId)
                .orElseThrow(() -> new ResourceNotFoundException("IncomeProfile", String.valueOf(applicantId)));
        return assess(profile, DEFAULT_BUREAU_SCORE);
    }

    /** Assess from an explicit profile + bureau score (used directly in tests). */
    @Transactional
    public RiskAssessment assess(IncomeProfile profile, int bureauScore) {
        int score = score(profile, bureauScore);
        RiskCategory category = categoryForScore(score);
        long cap = limitCalculator.eligibleLimitPaise(profile.getMonthlySalary());
        long granted = limitCalculator.limitForCategory(profile.getMonthlySalary(), category);

        RiskAssessment assessment = new RiskAssessment();
        assessment.setApplicantId(profile.getApplicantId());
        assessment.setScore(score);
        assessment.setCategory(category);
        assessment.setLimitGranted(granted);
        assessment.setFactors("score=%d; category=%s; capPaise=%d; bureau=%d"
                .formatted(score, category, cap, bureauScore));
        return riskAssessmentRepository.save(assessment);
    }

    /** Blend signals into a 0–100 score. Higher is safer. */
    int score(IncomeProfile profile, int bureauScore) {
        int bureauPoints = clamp((bureauScore - 300) * 50 / 600, 0, 50);
        int tenureMonths = profile.getUanTenure() != null ? profile.getUanTenure() : 0;
        int tenurePoints = clamp(tenureMonths * 25 / 24, 0, 25);
        long salaryRupees = profile.getMonthlySalary() != null ? profile.getMonthlySalary() / 100 : 0;
        int salaryPoints = (int) clamp(salaryRupees * 25 / 100_000, 0, 25);
        return clamp(bureauPoints + tenurePoints + salaryPoints, 0, 100);
    }

    RiskCategory categoryForScore(int score) {
        if (score >= 75) {
            return RiskCategory.A;
        }
        if (score >= 55) {
            return RiskCategory.B;
        }
        if (score >= 35) {
            return RiskCategory.C;
        }
        return RiskCategory.D;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
