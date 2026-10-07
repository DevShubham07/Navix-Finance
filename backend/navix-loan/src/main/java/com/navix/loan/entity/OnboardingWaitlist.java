package com.navix.loan.entity;

import com.navix.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A would-be borrower who arrived while onboarding was paused (V76). Just what they typed — no
 * application, no profile, no vendor check — kept so the business can come back to them.
 */
@Entity
@Table(name = "onboarding_waitlist")
@Getter
@Setter
@NoArgsConstructor
public class OnboardingWaitlist extends BaseAuditEntity {

    @Column(name = "customer_id", nullable = false, unique = true)
    private Long customerId;

    @Column(nullable = false, length = 10)
    private String mobile;

    @Column(name = "full_name", nullable = false, length = 160)
    private String fullName;

    @Column(nullable = false, length = 200)
    private String email;

    @Column(nullable = false, length = 10)
    private String pan;

    @Column(nullable = false, length = 12)
    private String aadhaar;
}
