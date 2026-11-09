package com.navix.loan.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One-use ADMIN permission (V95) for a customer the repayment-history rule disqualifies to reach
 * credit review on their next reborrow. It never makes them eligible by itself: the new application
 * lands in KYC_PENDING and a credit executive still decides.
 */
@Entity
@Table(name = "reapply_allowance")
@Getter
@Setter
@NoArgsConstructor
public class ReapplyAllowance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "reason", nullable = false, columnDefinition = "text")
    private String reason;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "granted_by_staff_id")
    private Long grantedByStaffId;

    @Column(name = "granted_by_name", length = 200)
    private String grantedByName;

    @Column(name = "granted_at", nullable = false, updatable = false)
    private Instant grantedAt;

    @Column(name = "used_application_id")
    private Long usedApplicationId;

    @Column(name = "used_at")
    private Instant usedAt;

    @PrePersist
    void stamp() {
        if (grantedAt == null) {
            grantedAt = Instant.now();
        }
    }
}
