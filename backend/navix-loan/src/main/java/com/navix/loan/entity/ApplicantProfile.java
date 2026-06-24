package com.navix.loan.entity;

import com.navix.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The applicant's KYC snapshot captured for a single application, so staff reviewers
 * (KYC approver, credit executive, credit head, …) can see who they are deciding on.
 *
 * <p>One row per application (1:1). PAN is stored in full but only ever surfaced to staff
 * masked (see {@code ReviewDtos.ProfileView}). Demo-grade and self-contained inside the loan
 * module; unifying with the orphaned onboarding/KYC modules is a later step.
 */
@Entity
@Table(name = "applicant_profile")
@Getter
@Setter
@NoArgsConstructor
public class ApplicantProfile extends BaseAuditEntity {

    @Column(name = "application_id", nullable = false, unique = true)
    private Long applicationId;

    @Column(name = "full_name", length = 160)
    private String fullName;

    /** Stored in full; surfaced to staff masked. */
    @Column(name = "pan", length = 10)
    private String pan;

    @Column(name = "dob")
    private LocalDate dob;

    @Column(name = "address", length = 400)
    private String address;

    @Column(name = "employer", length = 160)
    private String employer;

    @Column(name = "employment_status", length = 64)
    private String employmentStatus;

    @Column(name = "monthly_salary_paise")
    private Long monthlySalaryPaise;

    @Column(name = "salary_bank", length = 120)
    private String salaryBank;
}
