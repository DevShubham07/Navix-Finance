package com.navix.loan.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Append-only audit trail for every ADMIN override on a {@link DsaCommission} — paid / voided /
 * reassigned / manually created (V55). Not a {@link com.navix.common.entity.BaseAuditEntity}: this
 * row is written once and never edited.
 */
@Entity
@Table(name = "dsa_commission_event")
@Getter
@Setter
@NoArgsConstructor
public class DsaCommissionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "commission_id", nullable = false)
    private Long commissionId;

    /** PAID | VOIDED | REASSIGNED | CREATED_MANUALLY */
    @Column(name = "action", nullable = false, length = 24)
    private String action;

    @Column(name = "from_dsa_staff_id")
    private Long fromDsaStaffId;

    @Column(name = "to_dsa_staff_id")
    private Long toDsaStaffId;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(name = "at", nullable = false)
    private Instant at = Instant.now();
}
