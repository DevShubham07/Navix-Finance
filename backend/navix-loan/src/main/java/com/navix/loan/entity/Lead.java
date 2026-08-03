package com.navix.loan.entity;

import com.navix.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Pre-customer telecaller lead — DSA-style intake with call disposition.
 * Not a {@code customer}; conversion to the loan lifecycle is out of scope for v1.
 */
@Entity
@Table(name = "lead")
@Getter
@Setter
@NoArgsConstructor
public class Lead extends BaseAuditEntity {

    @Column(nullable = false, length = 160)
    private String name;

    @Column(nullable = false, length = 10)
    private String mobile;

    @Column(length = 160)
    private String email;

    @Column(length = 120)
    private String city;

    @Column(length = 160)
    private String employer;

    @Column(name = "monthly_salary_paise")
    private Long monthlySalaryPaise;

    @Column(name = "loan_amount_interested_paise")
    private Long loanAmountInterestedPaise;

    /** DSA | REFERRAL | WALK_IN | OTHER */
    @Column(length = 24)
    private String source;

    @Column(name = "source_detail", length = 240)
    private String sourceDetail;

    /** NOT_CALLED | CALLED | CALLBACK | NO_ANSWER | NOT_INTERESTED | WRONG_NUMBER | CONNECTED */
    @Column(name = "call_status", nullable = false, length = 32)
    private String callStatus = "NOT_CALLED";

    /** 1–5 quality stars; null until rated. */
    @Column(name = "quality_rating")
    private Integer qualityRating;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(columnDefinition = "text")
    private String remarks;

    @Column(name = "created_by_staff_id", nullable = false)
    private Long createdByStaffId;
}
