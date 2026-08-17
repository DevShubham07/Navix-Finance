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
 * Append-only audit of every SMS/email a DSA sends to their own lead (V55) — success or failure,
 * full body included, visible to ADMIN. Not a {@link com.navix.common.entity.BaseAuditEntity}: this
 * row is written once and never edited, so it carries only {@code createdAt}, not the full
 * created/updated-by audit shape.
 */
@Entity
@Table(name = "lead_outreach")
@Getter
@Setter
@NoArgsConstructor
public class LeadOutreach {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lead_id", nullable = false)
    private Long leadId;

    @Column(name = "dsa_staff_id", nullable = false)
    private Long dsaStaffId;

    /** SMS | EMAIL */
    @Column(name = "channel", nullable = false, length = 8)
    private String channel;

    /** The mobile number or email address the message was sent to. */
    @Column(name = "address", nullable = false, length = 160)
    private String address;

    @Column(name = "subject", length = 240)
    private String subject;

    @Column(columnDefinition = "text")
    private String body;

    /** SENT | FAILED | SKIPPED */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "provider_ref", length = 120)
    private String providerRef;

    @Column(name = "error", length = 240)
    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
