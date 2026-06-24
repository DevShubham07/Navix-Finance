package com.navix.income.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Verified income profile for an applicant.
 *
 * <p>Captures salary signals used by risk scoring and limit calculation.
 *
 * TODO: wire fields to the verification module outputs (UAN/EPFO, bank
 * statement analysis) once those contracts are finalized.
 */
@Entity
@Table(name = "income_profile")
@Getter
@Setter
@NoArgsConstructor
public class IncomeProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Applicant / borrower reference (FK to IAM user). */
    @Column(name = "applicant_id", nullable = false)
    private Long applicantId;

    /** Verified gross monthly salary, in paise. */
    @Column(name = "monthly_salary", nullable = false)
    private Long monthlySalary;

    /** Day of month (1-31) on which salary is typically credited. */
    @Column(name = "salary_credit_day")
    private Integer salaryCreditDay;

    /** Current employer name. */
    @Column(name = "employer")
    private String employer;

    /** UAN/EPFO tenure in months (employment continuity signal). */
    @Column(name = "uan_tenure")
    private Integer uanTenure;
}
