package com.navix.collections.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A single collections interaction with the borrower (call, SMS, field visit, etc.).
 * Records the outcome; a PAID outcome REQUIRES a proof reference (transaction id
 * or screenshot) in {@code proofRef}, and a promise-to-pay outcome carries a
 * {@code promiseToPayDate}.
 *
 * TODO: enforce "PAID requires proofRef" invariant in CollectionsService.
 */
@Entity
@Table(name = "interaction_log")
@Getter
@Setter
@NoArgsConstructor
public class InteractionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "collection_case_id", nullable = false)
    private UUID collectionCaseId;

    /** Interaction channel/type, e.g. CALL, SMS, EMAIL, FIELD_VISIT. */
    @Column(name = "type", nullable = false)
    private String type;

    /** Result of the interaction, e.g. PAID, PROMISE_TO_PAY, NO_ANSWER, DISPUTE. */
    @Column(name = "outcome", nullable = false)
    private String outcome;

    /** Set when outcome is PROMISE_TO_PAY. */
    @Column(name = "promise_to_pay_date")
    private LocalDate promiseToPayDate;

    /** Transaction id / screenshot reference; required when outcome is PAID. */
    @Column(name = "proof_ref")
    private String proofRef;

    @Column(name = "logged_at", nullable = false, updatable = false)
    private Instant loggedAt = Instant.now();

    /**
     * The staffer who logged this interaction (FK to {@code staff_user.id}); null on pre-V59 rows.
     * Without it a Collection Executive's call activity is unattributable, which is exactly the
     * "are they actually logging their calls?" signal the staff-performance dashboard reports on.
     */
    @Column(name = "logged_by_staff_id")
    private Long loggedByStaffId;
}
