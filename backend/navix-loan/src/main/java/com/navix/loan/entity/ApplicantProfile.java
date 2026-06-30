package com.navix.loan.entity;

import com.navix.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    /** Stored in full; surfaced to staff masked. Unique across applicants. */
    @Column(name = "pan", length = 10)
    private String pan;

    /** Stored in full at the owner's request; surfaced masked. Unique across applicants. */
    @Column(name = "aadhaar", length = 12)
    private String aadhaar;

    /** Borrower's mobile (normalised to 10 digits); surfaced masked. Unique across applicants. */
    @Column(name = "mobile", length = 15)
    private String mobile;

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

    /** Recorded annual salary in paise (informational; defaults to monthly×12 when absent). V26. */
    @Column(name = "annual_salary_paise")
    private Long annualSalaryPaise;

    /** Recorded salary/eligibility percentage (HR datum; the firm eligibility cap is RiskPort's 25%). V26. */
    @Column(name = "salary_percentage", precision = 5, scale = 2)
    private BigDecimal salaryPercentage;

    /** Recorded expected annual increment percentage (HR datum). V26. */
    @Column(name = "increment_percentage", precision = 5, scale = 2)
    private BigDecimal incrementPercentage;

    @Column(name = "salary_bank", length = 120)
    private String salaryBank;

    /** Borrower's contact email for notifications (personal preferred, else verified official). V22. */
    @Column(name = "email", length = 255)
    private String email;

    // --- emergency contact (editable on the borrower profile; not verified). V27 ---

    @Column(name = "emergency_contact_name", length = 160)
    private String emergencyContactName;

    @Column(name = "emergency_contact_phone", length = 20)
    private String emergencyContactPhone;

    @Column(name = "emergency_contact_relation", length = 60)
    private String emergencyContactRelation;

    // --- derived verification fields (populated by ApplicationVerificationService; V16) ---

    /** Credit bureau score (never surfaced to the borrower). */
    @Column(name = "bureau_score")
    private Long bureauScore;

    /** Which bureau answered: EXPERIAN or CRIF. */
    @Column(name = "bureau_source", length = 40)
    private String bureauSource;

    /** A/B/C/D risk grade (staff/internal only). */
    @Column(name = "risk_category", length = 4)
    private String riskCategory;

    @Column(name = "pan_verified")
    private Boolean panVerified;

    /** Aadhaar↔PAN seeding link, read from pan_comprehensive. */
    @Column(name = "aadhaar_linked")
    private Boolean aadhaarLinked;

    /** Aadhaar verified via DigiLocker (consent completed + Aadhaar fetched + document ingested). V29. */
    @Column(name = "aadhaar_verified")
    private Boolean aadhaarVerified;

    @Column(name = "email_verified")
    private Boolean emailVerified;

    @Column(name = "address_verified")
    private Boolean addressVerified;

    @Column(name = "penny_drop_verified")
    private Boolean pennyDropVerified;

    /** 0..1 fuzzy name-match across PAN / Aadhaar / penny-drop (identity cross-match). */
    @Column(name = "name_match_score")
    private Double nameMatchScore;

    /** DigiLocker session client id (threaded through status/list/download). */
    @Column(name = "digilocker_client_id", length = 120)
    private String digilockerClientId;

    /** True once the borrower has accepted the agreement documents. */
    @Column(name = "agreement_accepted")
    private Boolean agreementAccepted;

    // --- credit brief (V20): 1–5★ "should we recommend" rating + parsed bureau facts.
    //     Staff/internal only — never surfaced to the borrower. Credit score = bureauScore. ---

    /** 1.0–5.0 in 0.5 steps. */
    @Column(name = "credit_star_rating")
    private BigDecimal creditStarRating;

    /** Verdict band, e.g. STRONGLY RECOMMEND / RECOMMEND / REFER — MANUAL REVIEW / NOT RECOMMENDED. */
    @Column(name = "credit_recommendation", length = 40)
    private String creditRecommendation;

    /** Generated 2–3 sentence underwriter summary. */
    @Column(name = "credit_brief_summary")
    private String creditBriefSummary;

    @Column(name = "credit_brief_generated_at")
    private Instant creditBriefGeneratedAt;

    /** JSON of the parsed {@code BureauReportFacts} (Categories A/B/C) backing the brief card + PDF. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "credit_brief_facts")
    private String creditBriefFacts;
}
