package com.navix.income.service;

import com.navix.common.exception.ResourceNotFoundException;
import com.navix.income.dto.IncomeDtos.IncomeView;
import com.navix.income.dto.IncomeDtos.ProfileView;
import com.navix.income.dto.IncomeDtos.RiskView;
import com.navix.income.entity.IncomeProfile;
import com.navix.income.entity.RiskAssessment;
import com.navix.income.repository.IncomeProfileRepository;
import com.navix.income.repository.RiskAssessmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Income-profile persistence and the combined income+risk view used by the credit screens. */
@Service
@RequiredArgsConstructor
public class IncomeService {

    private final IncomeProfileRepository incomeProfileRepository;
    private final RiskAssessmentRepository riskAssessmentRepository;
    private final LimitCalculator limitCalculator;

    /** Create or update the customer's income profile (idempotent per customer). */
    @Transactional
    public IncomeProfile saveProfile(Long customerId, long monthlySalaryPaise, Integer salaryCreditDay,
                                     String employer, Integer uanTenure) {
        IncomeProfile profile = incomeProfileRepository.findByCustomerId(customerId)
                .orElseGet(IncomeProfile::new);
        profile.setCustomerId(customerId);
        profile.setMonthlySalary(monthlySalaryPaise);
        profile.setSalaryCreditDay(salaryCreditDay);
        profile.setEmployer(employer);
        profile.setUanTenure(uanTenure);
        return incomeProfileRepository.save(profile);
    }

    @Transactional(readOnly = true)
    public IncomeView view(Long customerId) {
        IncomeProfile profile = incomeProfileRepository.findByCustomerId(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("IncomeProfile", String.valueOf(customerId)));
        RiskAssessment risk = riskAssessmentRepository
                .findFirstByCustomerIdOrderByIdDesc(customerId).orElse(null);
        long eligibleLimit = (risk != null && risk.getLimitGranted() != null)
                ? risk.getLimitGranted()
                : limitCalculator.eligibleLimitPaise(profile.getMonthlySalary());
        return new IncomeView(ProfileView.of(profile), risk != null ? RiskView.of(risk) : null, eligibleLimit);
    }
}
