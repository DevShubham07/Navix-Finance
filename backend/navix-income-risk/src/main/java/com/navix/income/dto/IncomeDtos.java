package com.navix.income.dto;

import com.navix.income.domain.RiskCategory;
import com.navix.income.entity.IncomeProfile;
import com.navix.income.entity.RiskAssessment;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

/** Request/response DTOs for income & risk. Monetary fields are integer paise. */
public final class IncomeDtos {

    private IncomeDtos() {
    }

    public record ProfileRequest(
            @Positive long monthlySalaryPaise,
            @Min(1) @Max(31) Integer salaryCreditDay,
            String employer,
            Integer uanTenure) {
    }

    public record ProfileView(
            Long id,
            Long customerId,
            long monthlySalaryPaise,
            Integer salaryCreditDay,
            String employer,
            Integer uanTenure) {

        public static ProfileView of(IncomeProfile p) {
            return new ProfileView(p.getId(), p.getCustomerId(), p.getMonthlySalary(),
                    p.getSalaryCreditDay(), p.getEmployer(), p.getUanTenure());
        }
    }

    public record RiskView(
            Long id,
            Long customerId,
            RiskCategory category,
            Integer score,
            Long limitGrantedPaise,
            String factors) {

        public static RiskView of(RiskAssessment r) {
            return new RiskView(r.getId(), r.getCustomerId(), r.getCategory(), r.getScore(),
                    r.getLimitGranted(), r.getFactors());
        }
    }

    /** Combined income view: profile + latest risk (nullable) + current eligible limit. */
    public record IncomeView(ProfileView profile, RiskView risk, long eligibleLimitPaise) {
    }
}
