package com.navix.disbursement.entity;

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
import java.util.UUID;

/**
 * One step in the maker-checker approval trail for a {@link DisbursementRequest}.
 * Each step records WHO ({@code actorId}) acting in WHICH {@code role} made WHAT
 * {@code decision} and WHEN ({@code at}). Together the steps form the
 * separation-of-duties audit trail.
 *
 * TODO: add convenience constructors / decision enum if needed by services.
 */
@Entity
@Table(name = "approval_step")
@Getter
@Setter
@NoArgsConstructor
public class ApprovalStep {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "disbursement_request_id", nullable = false)
    private UUID disbursementRequestId;

    /** Role under which the actor acted, e.g. CREDIT_EXECUTIVE, CREDIT_HEAD, DISBURSEMENT_HEAD. */
    @Column(name = "role", nullable = false)
    private String role;

    /** User id of the person who took the action (maker-checker identity). */
    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    /** Decision taken, e.g. RECOMMENDED, APPROVED, REJECTED, RELEASED. */
    @Column(name = "decision", nullable = false)
    private String decision;

    @Column(name = "at", nullable = false)
    private Instant at = Instant.now();
}
