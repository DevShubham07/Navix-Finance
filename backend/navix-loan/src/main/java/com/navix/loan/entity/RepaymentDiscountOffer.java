package com.navix.loan.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * "Repay by {@code repayBy} and take {@code percent}% of your next loan's principal off the
 * processing fee" (V95). Unlocked by full repayment of {@code loanId} by the deadline; used once.
 */
@Entity
@Table(name = "repayment_discount_offer")
@Getter
@Setter
@NoArgsConstructor
public class RepaymentDiscountOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "loan_id", nullable = false)
    private Long loanId;

    @Column(name = "percent", nullable = false)
    private short percent;

    @Column(name = "repay_by", nullable = false)
    private LocalDate repayBy;

    @Column(name = "message_id")
    private Long messageId;

    @Column(name = "created_by_staff_id")
    private Long createdByStaffId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "used_application_id")
    private Long usedApplicationId;

    @Column(name = "used_loan_id")
    private Long usedLoanId;

    @Column(name = "used_at")
    private Instant usedAt;

    @PrePersist
    void stamp() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
