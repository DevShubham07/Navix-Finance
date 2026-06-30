package com.navix.loan.service;

import com.navix.common.risk.RiskPort;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.LoanApplicationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Validates a loan request against eligibility rules, and is the single source for recomputing the
 * eligible limit from salary.
 *
 * <p>The eligible limit (25% of salary, risk-adjusted) is produced by the income-risk module via
 * {@link RiskPort}; this service gates a requested amount against it and recomputes it on salary
 * changes outside onboarding (admin or borrower self-edit). All amounts are integer paise.
 */
@Service
@RequiredArgsConstructor
public class EligibilityService {

    private final LoanApplicationRepository applicationRepository;
    private final RiskPort risk;

    /**
     * Whether a requested amount is within the eligible limit.
     *
     * @param amountRequestedPaise requested principal, in paise
     * @param eligibleLimitPaise   eligible limit from income-risk, in paise
     * @return true if the amount is positive and does not exceed the limit
     */
    public boolean isEligible(long amountRequestedPaise, long eligibleLimitPaise) {
        return amountRequestedPaise > 0 && amountRequestedPaise <= eligibleLimitPaise;
    }

    /**
     * Recompute and persist {@code eligibleLimit} (the firm 25%-of-salary cap) on the applicant's
     * <b>not-yet-disbursed</b> applications from {@code monthlySalaryPaise}. A disbursed loan's limit
     * is historical and untouched. No-op when the salary is null / non-positive.
     */
    @Transactional
    public void recomputeForApplicant(Long applicantId, Long monthlySalaryPaise) {
        if (applicantId == null || monthlySalaryPaise == null || monthlySalaryPaise <= 0) {
            return;
        }
        long eligible = risk.eligibleLimitPaise(monthlySalaryPaise);
        for (LoanApplication a : applicationRepository.findByApplicantId(applicantId)) {
            if (a.getLoanId() == null) {
                a.setEligibleLimit(eligible);
                applicationRepository.save(a);
            }
        }
    }
}
