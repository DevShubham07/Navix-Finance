package com.navix.onboarding.entity;

import com.navix.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A borrower (customer) on the DhanBoost platform.
 * Salary is declared in-app (Account Aggregator is OUT of scope) and later
 * corroborated by official email verification.
 * TODO: add validation constraints, indexes and any derived risk fields.
 */
@Entity
@Table(name = "borrower")
@Getter
@Setter
public class Borrower extends BaseAuditEntity {

    @Column(length = 10)
    private String pan;

    @Column(length = 15)
    private String mobile;

    private String personalEmail;

    private String officialEmail;

    /** e.g. SALARIED / SELF_EMPLOYED. TODO: promote to enum once finalised. */
    private String employmentStatus;

    /** Universal Account Number (EPFO). */
    @Column(length = 12)
    private String uan;

    /** Self-declared monthly salary, in paise. */
    private Long declaredSalary;

    private String salaryBank;

    /** Day of month (1-31) on which salary is credited. */
    private Integer salaryCreditDay;

    /** Lifecycle status of the borrower record. TODO: promote to enum. */
    private String status;
}
