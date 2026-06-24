package com.navix.loan.entity;

import com.navix.loan.domain.ApplicationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Append-only audit/approval-trail entry for one application state transition. Powers the
 * approval history and the separation-of-duties checks (who recommended vs who approved).
 */
@Entity
@Table(name = "application_event")
@Getter
@Setter
@NoArgsConstructor
public class ApplicationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    /** Status before this transition; null for the initial creation. */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 32)
    private ApplicationStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 32)
    private ApplicationStatus toStatus;

    /** Acting user id (demo actor id today; JWT subject at go-live). */
    @Column(name = "actor_id", nullable = false)
    private String actorId;

    @Column(name = "actor_role", length = 64)
    private String actorRole;

    /** The action label, e.g. RECOMMEND, APPROVE, REJECT, ASSIGN, VALIDATE. */
    @Column(name = "action", nullable = false, length = 64)
    private String action;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Column(name = "at", nullable = false)
    private Instant at;
}
