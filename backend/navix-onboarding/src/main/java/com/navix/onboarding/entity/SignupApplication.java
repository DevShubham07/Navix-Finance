package com.navix.onboarding.entity;

import com.navix.common.entity.BaseAuditEntity;
import com.navix.onboarding.domain.SignupStep;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Tracks a borrower's progress through the 12-step sign-up flow.
 * TODO: link to Borrower, persist per-step payload/state and completion flags.
 */
@Entity
@Table(name = "signup_application")
@Getter
@Setter
public class SignupApplication extends BaseAuditEntity {

    /** FK to the borrower this application belongs to. */
    private Long borrowerId;

    @Enumerated(EnumType.STRING)
    private SignupStep currentStep;

    private boolean completed;
}
