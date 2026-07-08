package com.navix.income.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.navix.income.domain.RiskCategory;
import com.navix.income.entity.IncomeProfile;
import com.navix.income.entity.RiskAssessment;
import com.navix.income.repository.IncomeProfileRepository;
import com.navix.income.repository.RiskAssessmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RiskScoringServiceTest {

    @Mock
    private IncomeProfileRepository incomeProfileRepository;
    @Mock
    private RiskAssessmentRepository riskAssessmentRepository;

    private RiskScoringService service;

    @BeforeEach
    void setUp() {
        service = new RiskScoringService(incomeProfileRepository, riskAssessmentRepository, new LimitCalculator());
    }

    private IncomeProfile profile(long salaryPaise, Integer uanTenure) {
        IncomeProfile p = new IncomeProfile();
        p.setCustomerId(7L);
        p.setMonthlySalary(salaryPaise);
        p.setUanTenure(uanTenure);
        return p;
    }

    @Test
    void scoreBandsMapToCategories() {
        assertThat(service.categoryForScore(80)).isEqualTo(RiskCategory.A);
        assertThat(service.categoryForScore(60)).isEqualTo(RiskCategory.B);
        assertThat(service.categoryForScore(40)).isEqualTo(RiskCategory.C);
        assertThat(service.categoryForScore(10)).isEqualTo(RiskCategory.D);
    }

    @Test
    void strongProfileScoresHigh() {
        // bureau 800 → 41pts, tenure 24m → 25pts, salary ₹100k → 25pts (capped) = 91
        int score = service.score(profile(10_000_000L, 24), 800);
        assertThat(score).isGreaterThanOrEqualTo(75);
        assertThat(service.categoryForScore(score)).isEqualTo(RiskCategory.A);
    }

    @Test
    void weakProfileScoresLow() {
        // bureau 350 → 4pts, tenure 0, salary ₹8k → 2pts = 6 → D
        int score = service.score(profile(800_000L, 0), 350);
        assertThat(score).isLessThan(35);
        assertThat(service.categoryForScore(score)).isEqualTo(RiskCategory.D);
    }

    @Test
    void assessPersistsCategoryAndGrantedLimit() {
        IncomeProfile p = profile(4_000_000L, 24); // ₹40k
        when(riskAssessmentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        RiskAssessment ra = service.assess(p, 800); // strong → category A

        assertThat(ra.getCategory()).isEqualTo(RiskCategory.A);
        // Instant-loan model: the granted limit is the flat ₹10,00,000 cap, not salary/category-scaled.
        assertThat(ra.getLimitGranted()).isEqualTo(LimitCalculator.MAX_INSTANT_LOAN_PAISE);
        assertThat(ra.getScore()).isGreaterThanOrEqualTo(75);
        assertThat(ra.getCustomerId()).isEqualTo(7L);
    }
}
