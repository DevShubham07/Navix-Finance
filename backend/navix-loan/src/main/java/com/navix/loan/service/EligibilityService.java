package com.navix.loan.service;

import com.navix.common.risk.RiskPort;
import com.navix.loan.entity.CustomerLimitOverride;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.CustomerLimitOverrideRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Validates a loan request against eligibility rules, and is the single source for deciding and
 * recomputing a customer's eligible limit.
 *
 * <p>The limit is normally derived — 25% of salary, produced by the income-risk module via
 * {@link RiskPort}. An ADMIN may override it per customer (V69), and that override wins everywhere
 * the limit is resolved, which is why every writer of {@code loan_application.eligible_limit} goes
 * through {@link #effectiveLimitPaise}: a derived value written directly would overwrite the
 * override on the next payslip verification, salary edit or reborrow. All amounts are integer paise.
 */
@Service
@RequiredArgsConstructor
public class EligibilityService {

    private final LoanApplicationRepository applicationRepository;
    private final CustomerLimitOverrideRepository overrideRepository;
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
     * The limit for a customer: the ADMIN override when one exists, else the 25%-of-salary rule.
     * This is the only way the eligible limit should ever be computed.
     */
    public long effectiveLimitPaise(Long customerId, long monthlySalaryPaise) {
        return overrideOf(customerId).orElseGet(() -> risk.eligibleLimitPaise(monthlySalaryPaise));
    }

    /** The ADMIN override for this customer, if one is set. */
    public Optional<Long> overrideOf(Long customerId) {
        return customerId == null ? Optional.empty()
                : overrideRepository.findById(customerId).map(CustomerLimitOverride::getLimitPaise);
    }

    /**
     * Recompute and persist {@code eligibleLimit} on the customer's <b>not-yet-disbursed</b>
     * applications. A disbursed loan's limit is historical and untouched.
     *
     * <p>The salary is only a fallback: an override applies even to a customer with no salary on
     * file, so the limit is resolved first and the call is a no-op only when neither is available.
     */
    @Transactional
    public void recomputeForCustomer(Long customerId, Long monthlySalaryPaise) {
        if (customerId == null) {
            return;
        }
        Long eligible = overrideOf(customerId).orElseGet(() ->
                monthlySalaryPaise != null && monthlySalaryPaise > 0
                        ? risk.eligibleLimitPaise(monthlySalaryPaise)
                        : null);
        if (eligible == null) {
            return;
        }
        for (LoanApplication a : applicationRepository.findByCustomerId(customerId)) {
            if (a.getLoanId() == null) {
                a.setEligibleLimit(eligible);
                applicationRepository.save(a);
            }
        }
    }
}
