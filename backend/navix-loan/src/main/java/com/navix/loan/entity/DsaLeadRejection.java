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
 * Audit of every PAN a DSA tried to enter that was rejected at lead-creation time — because
 * another DSA already holds it, or it belongs to an existing DhanBoost customer (V55). Both cases
 * surface the same generic {@code LEAD_ALREADY_KNOWN} error to the DSA, so this table is what makes
 * bulk PAN enumeration visible to ADMIN instead of silent; it also backs the daily lead-creation
 * rate limit alongside successful creates.
 */
@Entity
@Table(name = "dsa_lead_rejection")
@Getter
@Setter
@NoArgsConstructor
public class DsaLeadRejection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dsa_staff_id", nullable = false)
    private Long dsaStaffId;

    @Column(name = "pan", nullable = false, length = 10)
    private String pan;

    /** OTHER_DSA | EXISTING_CUSTOMER */
    @Column(name = "reason", nullable = false, length = 24)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
