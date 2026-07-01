package com.navix.income.entity;

import com.navix.income.domain.RiskCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Result of running the risk engine for an customer.
 *
 * TODO: persist the structured factor breakdown (currently a free-text/JSON
 * blob) once the scoring model is defined.
 */
@Entity
@Table(name = "risk_assessment")
@Getter
@Setter
@NoArgsConstructor
public class RiskAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Customer / borrower reference (FK to IAM user). */
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /** Assigned risk category A/B/C/D. */
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 1)
    private RiskCategory category;

    /** Numeric risk score (scale TBD). */
    @Column(name = "score")
    private Integer score;

    /** Limit granted based on category + eligible limit, in paise. */
    @Column(name = "limit_granted")
    private Long limitGranted;

    /** Human/JSON readable explanation of contributing factors. */
    @Column(name = "factors", length = 2000)
    private String factors;
}
