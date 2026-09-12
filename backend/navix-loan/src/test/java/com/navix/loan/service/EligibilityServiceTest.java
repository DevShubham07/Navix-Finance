package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.navix.common.risk.RiskPort;
import com.navix.loan.entity.CustomerLimitOverride;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.CustomerLimitOverrideRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The ADMIN limit override (V69) must outrank the 25%-of-salary rule everywhere the limit is
 * resolved — that is the whole reason it is stored per customer instead of on an application row.
 */
@ExtendWith(MockitoExtension.class)
class EligibilityServiceTest {

    private static final long SALARY = 4_000_000L;        // ₹40,000/month
    private static final long SALARY_RULE_LIMIT = 1_000_000L; // 25% of it = ₹10,000
    private static final long OVERRIDE = 5_000_000L;      // admin grants ₹50,000

    @Mock private LoanApplicationRepository applicationRepository;
    @Mock private CustomerLimitOverrideRepository overrideRepository;
    @Mock private RiskPort risk;

    private EligibilityService service;

    @BeforeEach
    void setUp() {
        service = new EligibilityService(applicationRepository, overrideRepository, risk);
        lenient().when(risk.eligibleLimitPaise(SALARY)).thenReturn(SALARY_RULE_LIMIT);
    }

    private void overrideExists() {
        CustomerLimitOverride row = new CustomerLimitOverride();
        row.setCustomerId(7L);
        row.setLimitPaise(OVERRIDE);
        when(overrideRepository.findById(7L)).thenReturn(Optional.of(row));
    }

    @Test
    void fallsBackToTheSalaryRuleWhenNoOverrideIsSet() {
        when(overrideRepository.findById(7L)).thenReturn(Optional.empty());

        assertThat(service.effectiveLimitPaise(7L, SALARY)).isEqualTo(SALARY_RULE_LIMIT);
    }

    @Test
    void anAdminOverrideWinsOverTheSalaryRule() {
        overrideExists();

        assertThat(service.effectiveLimitPaise(7L, SALARY)).isEqualTo(OVERRIDE);
    }

    @Test
    void recomputePushesTheOverrideOntoLiveApplicationsAndSparesDisbursedOnes() {
        overrideExists();
        LoanApplication live = new LoanApplication();
        LoanApplication disbursed = new LoanApplication();
        disbursed.setLoanId(99L);
        disbursed.setEligibleLimit(SALARY_RULE_LIMIT);
        when(applicationRepository.findByCustomerId(7L)).thenReturn(List.of(live, disbursed));

        service.recomputeForCustomer(7L, SALARY);

        assertThat(live.getEligibleLimit()).isEqualTo(OVERRIDE);
        // a disbursed loan's limit is historical
        assertThat(disbursed.getEligibleLimit()).isEqualTo(SALARY_RULE_LIMIT);
    }

    /** An override applies to a customer with no salary on file, where the rule has nothing to say. */
    @Test
    void recomputeAppliesTheOverrideEvenWithNoSalary() {
        overrideExists();
        LoanApplication live = new LoanApplication();
        when(applicationRepository.findByCustomerId(7L)).thenReturn(List.of(live));

        service.recomputeForCustomer(7L, null);

        assertThat(live.getEligibleLimit()).isEqualTo(OVERRIDE);
    }

    /** Clearing the override hands the customer straight back to the salary rule. */
    @Test
    void recomputeRestoresTheSalaryRuleOnceTheOverrideIsCleared() {
        when(overrideRepository.findById(7L)).thenReturn(Optional.empty());
        LoanApplication live = new LoanApplication();
        live.setEligibleLimit(OVERRIDE);
        when(applicationRepository.findByCustomerId(7L)).thenReturn(List.of(live));

        service.recomputeForCustomer(7L, SALARY);

        assertThat(live.getEligibleLimit()).isEqualTo(SALARY_RULE_LIMIT);
    }

    /**
     * What the borrower's own reads surface as "available to borrow". Their newest application is a
     * CLOSED one whose stored limit is historical, so the override must win — otherwise an
     * admin-raised limit is invisible until they tap through the reborrow.
     */
    @Test
    void overrideOfIsTheBorrowerFacingAvailableLimit() {
        overrideExists();

        assertThat(service.overrideOf(7L)).contains(OVERRIDE);
    }

    @Test
    void overrideOfIsEmptyWithoutAnOverrideAndForANullCustomer() {
        when(overrideRepository.findById(7L)).thenReturn(Optional.empty());

        assertThat(service.overrideOf(7L)).isEmpty();
        assertThat(service.overrideOf(null)).isEmpty();
    }

    @Test
    void recomputeIsANoOpWhenThereIsNeitherOverrideNorSalary() {
        when(overrideRepository.findById(7L)).thenReturn(Optional.empty());

        service.recomputeForCustomer(7L, null);

        org.mockito.Mockito.verify(applicationRepository, org.mockito.Mockito.never()).save(any());
    }
}
